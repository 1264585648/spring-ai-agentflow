package com.example.springai.core.routing;

import java.util.List;

/**
 * 一次 L1 规则内存快照 (Java 21 Record)。
 * skipped 非空时 complete 为 false，但 rules 仍是可执行的那部分。
 */
public record RuleSnapshot(
        List<RuleItem> rules,
        List<String> skippedRuleCodes,
        boolean complete,
        long loadedAtEpochMs
) {

    public static RuleSnapshot empty() {
        return new RuleSnapshot(List.of(), List.of(), false, 0L);
    }

    // 兼容原有 getter 调用习惯
    public List<RuleItem> getRules() {
        return rules;
    }

    public List<String> getSkippedRuleCodes() {
        return skippedRuleCodes;
    }

    public boolean isComplete() {
        return complete;
    }

    public long getLoadedAtEpochMs() {
        return loadedAtEpochMs;
    }
}
