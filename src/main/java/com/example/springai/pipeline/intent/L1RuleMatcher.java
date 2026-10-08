package com.example.springai.pipeline.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 流水线第一道防线: L1 规则匹配器 (L1RuleMatcher)
 * 职责: 基于高性能内存注册表 (L1RuleRegistry) 拦截高频固定指令，耗时 <30ms，零大模型 Token 开销。
 */
@Component
public class L1RuleMatcher {

    private static final Logger log = LoggerFactory.getLogger(L1RuleMatcher.class);

    private final L1RuleRegistry ruleRegistry;

    public L1RuleMatcher(L1RuleRegistry ruleRegistry) {
        this.ruleRegistry = ruleRegistry;
    }

    /**
     * 执行 L1 规则匹配
     *
     * @param query 用户提问文本
     * @return 匹配结果
     */
    public IntentMatchResult match(String query) {
        if (query == null || query.trim().isEmpty()) {
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
