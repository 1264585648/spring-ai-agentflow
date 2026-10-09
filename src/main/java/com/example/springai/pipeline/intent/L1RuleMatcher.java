package com.example.springai.pipeline.intent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 流水线第一道防线: L1 规则匹配器 (L1RuleMatcher)
 * 职责: 基于高性能内存注册表 (L1RuleRegistry) 拦截高频固定指令，耗时 <30ms，零大模型 Token 开销。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class L1RuleMatcher {

    private final L1RuleRegistry ruleRegistry;

    /**
     * 执行 L1 规则匹配
     *
     * @param query 用户提问文本
     * @return 匹配结果
     */
    public IntentMatchResult match(String query) {
        if (!StringUtils.hasText(query)) {
            return IntentMatchResult.miss();
        }

        // 直接委托给基于 MySQL 预热 + 内存快照无锁架构的 L1RuleRegistry
        return ruleRegistry.match(query);
    }

    /**
     * 获取当前生效的规则快照
     */
    public List<RuleItem> getRules() {
        return ruleRegistry.getActiveRules();
    }
}
