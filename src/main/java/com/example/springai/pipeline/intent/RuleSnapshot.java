package com.example.springai.pipeline.intent;

import java.util.List;

/**
 * 一次 L1 规则内存快照。skipped 非空时 complete 为 false，但 rules 仍是可执行的那部分。
 */
public final class RuleSnapshot {

    private final List<RuleItem> rules;
    private final List<String> skippedRuleCodes;
    private final boolean complete;
    private final long loadedAtEpochMs;

    public RuleSnapshot(List<RuleItem> rules, List<String> skippedRuleCodes, boolean complete, long loadedAtEpochMs) {
        this.rules = rules;
        this.skippedRuleCodes = skippedRuleCodes;
        this.complete = complete;
        this.loadedAtEpochMs = loadedAtEpochMs;
    }

    public static RuleSnapshot empty() {
        return new RuleSnapshot(List.of(), List.of(), false, 0L);
    }

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
