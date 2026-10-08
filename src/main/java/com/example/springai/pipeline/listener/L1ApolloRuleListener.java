package com.example.springai.pipeline.listener;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigService;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.example.springai.pipeline.intent.L1RuleRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Apollo 分布式配置中心 L1 规则热重载监听器 (L1ApolloRuleListener)
 * 核心设计:
 * 1. 解决分布式多 Pod/节点部署下的“脑裂与数据不一致”问题；
 * 2. 基于 Apollo HTTP 长轮询机制（60s 挂起保持），毫秒级捕获全集群配置变更；
 * 3. 接收到变更通知后，调用 L1RuleRegistry.reload() 执行无锁原子快照替换；
 * 4. 通过 @ConditionalOnProperty 控制开关，未配置 Apollo 时零开销、零侵入、平滑降级；
 * 5. 天然自带 Apollo 本地磁盘物理缓存（Local Cache File）容灾能力。
 */
@Component
@ConditionalOnProperty(name = "apollo.bootstrap.enabled", havingValue = "true")
public class L1ApolloRuleListener {

    private static final Logger log = LoggerFactory.getLogger(L1ApolloRuleListener.class);

    private final L1RuleRegistry ruleRegistry;

    @Value("${apollo.rules.namespace:agent.l1.rules}")
    private String rulesNamespace;

    public L1ApolloRuleListener(L1RuleRegistry ruleRegistry) {
        this.ruleRegistry = ruleRegistry;
    }

    @PostConstruct
    public void init() {
        try {
            log.info("[Apollo] 正在注册 L1 规则分布式监听器, 监听 Namespace: {}", rulesNamespace);
            Config config = ConfigService.getConfig(rulesNamespace);
            config.addChangeListener((ConfigChangeEvent changeEvent) -> {
                handleConfigChange(changeEvent.getNamespace(), changeEvent.changedKeys());
            });
            log.info("[Apollo] ✅ L1 规则分布式监听器已成功启动并就绪");
        } catch (Exception e) {
            log.error("[Apollo] ❌ 注册 Apollo 规则监听器失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理配置变更，触发内存快照热更新
     *
     * @param namespace 发生变更的命名空间
     * @param changedKeys 变更的配置键集合
     */
    public void handleConfigChange(String namespace, Set<String> changedKeys) {
        log.info("[Apollo] ⚡ 监听到全集群配置变更通知, Namespace: {}, 变更键: {}", namespace, changedKeys);
        ruleRegistry.reload();
    }

    public String getRulesNamespace() {
        return rulesNamespace;
    }

    public void setRulesNamespace(String rulesNamespace) {
        this.rulesNamespace = rulesNamespace;
    }
}
