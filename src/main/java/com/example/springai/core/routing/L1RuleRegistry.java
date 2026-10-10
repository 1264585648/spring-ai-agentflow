package com.example.springai.core.routing;

import lombok.extern.slf4j.Slf4j;
import com.example.springai.core.routing.dto.CommandPaletteItem;
import com.example.springai.infra.persistence.entity.RuleDefinitionEntity;
import com.example.springai.core.event.L1RuleReloadEvent;
import com.example.springai.infra.persistence.repository.RuleDefinitionRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * L1 规则运行时高并发内存注册表 (L1RuleRegistry)
 * 核心设计:
 * 1. 读写分离: 读走 JVM 内存快照 (无锁并发)，写走 MySQL 事务持久化；
 * 2. 零停机热重载: 基于 AtomicReference 实现 Copy-On-Write 原子快照替换；
 * 3. 极速响应: 内存完成正则预编译，全流程耗时 < 1ms；
 * 4. 旁路异步审计: 命中统计交由 agentAsyncPostExecutor 线程池异步落库，绝不阻塞用户通信主线程。
 */
@Component
@Slf4j
public class L1RuleRegistry {


    private final RuleDefinitionRepository ruleRepository;
    private final Executor asyncExecutor;

    /**
     * 运行态规则快照容器 (无锁原子引用)
     */
    private final AtomicReference<RuleSnapshot> activeRulesHolder = new AtomicReference<>(RuleSnapshot.empty());

    private volatile boolean lastReloadSuccessful;
    private volatile List<String> lastSkippedRuleCodes = List.of();
    private volatile String lastReloadError;

    public L1RuleRegistry(RuleDefinitionRepository ruleRepository,
                          @Qualifier("agentAsyncPostExecutor") Executor asyncExecutor) {
        this.ruleRepository = ruleRepository;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * 服务启动时预热加载全部有效规则
     */
    @PostConstruct
    public void init() {
        log.info("[L1RuleRegistry] 正在从 MySQL 预热加载 L1 规则快照...");
        reload();
    }

    /**
     * 监听 Spring 内部热重载事件
     */
    @EventListener(L1RuleReloadEvent.class)
    public void onRuleReload(L1RuleReloadEvent event) {
        log.info("[L1RuleRegistry] 接收到规则重载事件，触发原因: {}", event.getReason());
        reload();
    }

    /**
     * 执行全量重载 (Copy-On-Write 原子替换)
     */
    public synchronized void reload() {
        long startTime = System.currentTimeMillis();
        try {
            List<RuleDefinitionEntity> entities = ruleRepository.findByIsEnabledOrderByPriorityAsc(1);
            List<RuleItem> newRules = new ArrayList<>();
            List<String> skipped = new ArrayList<>();

            for (RuleDefinitionEntity entity : entities) {
                try {
                    RuleItem.MatchType matchType = parseMatchType(entity.getMatchType());
                    int priority = entity.getPriority() != null ? entity.getPriority() : 100;
                    RuleItem item = new RuleItem(
                            entity.getRuleCode(),
                            entity.getRuleName(),
                            matchType,
                            entity.getPatternExpr(),
                            entity.getTargetType(),
                            entity.getTargetRef(),
                            entity.getParamTemplate(),
                            priority,
                            entity.getDescription()
                    );
                    newRules.add(item);
                } catch (Exception ruleError) {
                    String ruleCode = entity.getRuleCode() != null ? entity.getRuleCode() : "UNKNOWN";
                    skipped.add(ruleCode);
                    log.error("[L1RuleRegistry] 跳过无法加载的规则 {}: {}", ruleCode, ruleError.getMessage());
                }
            }

            RuleSnapshot snapshot = new RuleSnapshot(
                    Collections.unmodifiableList(newRules),
                    Collections.unmodifiableList(skipped),
                    skipped.isEmpty(),
                    System.currentTimeMillis()
            );
            activeRulesHolder.set(snapshot);
            lastSkippedRuleCodes = snapshot.getSkippedRuleCodes();
            lastReloadSuccessful = snapshot.isComplete();
            lastReloadError = null;

            long cost = System.currentTimeMillis() - startTime;
            log.info("[L1RuleRegistry] 规则快照重载完成，生效规则数: {}, 跳过: {}, 耗时: {}ms",
                    newRules.size(), skipped, cost);
        } catch (Exception e) {
            lastReloadSuccessful = false;
            lastReloadError = e.getMessage();
            log.error("[L1RuleRegistry] 规则快照重载失败，保持上一次内存快照运行: {}", e.getMessage(), e);
        }
    }

    /**
     * 极速匹配方法 (供数据面主链路调用，零锁零阻塞)
     *
     * @param query 用户提问
     * @return 匹配结果
     */
    public IntentMatchResult match(String query) {
        if (query == null || query.trim().isEmpty()) {
            return IntentMatchResult.miss();
        }

        long startTime = System.currentTimeMillis();
        List<RuleItem> currentRules = activeRulesHolder.get().getRules();

        for (RuleItem rule : currentRules) {
            if (rule.matches(query)) {
                long cost = System.currentTimeMillis() - startTime;
                String extractedParams = rule.extractParams(query);
                String directReply = "STATIC_TEXT".equalsIgnoreCase(rule.getTargetType()) ? rule.getTargetRef() : null;

                log.info("[L1RuleRegistry] 🎯 命中规则! code={}, name={}, type={}, target={}, params={}, cost: {}ms",
                        rule.getRuleCode(), rule.getRuleName(), rule.getTargetType(), rule.getTargetRef(), extractedParams, cost);

                // 旁路异步递增命中次数，绝不影响主流程响应
                recordHitCountAsync(rule.getRuleCode());

                return IntentMatchResult.hitL1(
                        rule.getRuleCode(),
                        rule.getRuleName(),
                        rule.getTargetType(),
                        rule.getTargetRef(),
                        extractedParams,
                        directReply,
                        cost
                );
            }
        }

        long cost = System.currentTimeMillis() - startTime;
        log.debug("[L1RuleRegistry] 未命中 L1 规则，耗时: {}ms，放行至下一级", cost);
        return IntentMatchResult.miss();
    }

    /**
     * 异步记录命中次数
     */
    private void recordHitCountAsync(String ruleCode) {
        try {
            asyncExecutor.execute(() -> {
                try {
                    ruleRepository.incrementHitCount(ruleCode);
                } catch (Exception e) {
                    log.warn("[L1RuleRegistry] 异步递增命中数失败 (ruleCode={}): {}", ruleCode, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[L1RuleRegistry] 提交异步命中计数任务失败: {}", e.getMessage());
        }
    }

    private RuleItem.MatchType parseMatchType(String typeStr) {
        if (typeStr == null) return RuleItem.MatchType.REGEX;
        try {
            return RuleItem.MatchType.valueOf(typeStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[L1RuleRegistry] 未知 matchType: {}, 默认使用 REGEX", typeStr);
            return RuleItem.MatchType.REGEX;
        }
    }

    public List<RuleItem> getActiveRules() {
        return activeRulesHolder.get().getRules();
    }

    public RuleSnapshot getSnapshot() {
        return activeRulesHolder.get();
    }

    public boolean isLastReloadSuccessful() {
        return lastReloadSuccessful;
    }

    public List<String> getSkippedRuleCodes() {
        return lastSkippedRuleCodes;
    }

    public String getLastReloadError() {
        return lastReloadError;
    }

    /**
     * 获取全量快捷指令面板列表 (供前端斜杠悬浮菜单调用，纯内存零延迟)
     */
    public List<CommandPaletteItem> getCommandPalette() {
        List<RuleItem> rules = activeRulesHolder.get().getRules();
        List<CommandPaletteItem> palette = new ArrayList<>();
        for (RuleItem rule : rules) {
            String prefix = rule.getCommandPrefix();
            // 只要是以 / 或 # 开头的快捷指令，均进入指令面板
            if (prefix.startsWith("/") || prefix.startsWith("#")) {
                String icon = prefix.startsWith("#") ? "Zap" :
                        ("TOOL".equalsIgnoreCase(rule.getTargetType()) ? "Wrench" : "HelpCircle");
                palette.add(CommandPaletteItem.builder()
                        .code(rule.getRuleCode())
                        .prefix(prefix)
                        .name(rule.getRuleName())
                        .template(rule.getCommandTemplate())
                        .description(rule.getDescription())
                        .targetType(rule.getTargetType())
                        .icon(icon)
                        .build());
            }
        }
        return Collections.unmodifiableList(palette);
    }
}
