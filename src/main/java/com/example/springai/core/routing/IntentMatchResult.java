package com.example.springai.core.routing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 意图匹配结果通用封装模型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntentMatchResult {

    /**
     * 是否命中意图
     */
    private boolean matched;

    /**
     * 命中的层级: L1(规则), L2(向量), L3(LLM兜底)
     */
    private String matchedLevel;

    /**
     * 规则业务编码 (如 CMD_QUERY_ACCOUNT, CMD_HELP)
     */
    private String ruleCode;

    /**
     * 规则名称
     */
    private String ruleName;

    /**
     * 目标意图标识 (兼容原有逻辑)
     */
    private String targetIntent;

    /**
     * 执行目标类型: TOOL, STATIC_TEXT, INTERACTIVE_CARD, WORKFLOW
     */
    private String targetType;

    /**
     * 执行目标引用 (Tool方法/静态内容/卡片ID)
     */
    private String targetRef;

    /**
     * 提取并格式化后的入参 (JSON字符串)
     */
    private String extractedParams;

    /**
     * 匹配置信度 (0.0 ~ 1.0, 规则匹配通常为 1.0)
     */
    private double confidence;

    /**
     * 固定直接答复文本 (当 targetType 为 STATIC_TEXT 时使用，完全跳过大模型)
     */
    private String directReply;

    /**
     * 匹配耗时 (毫秒)
     */
    private long costMs;

    public IntentMatchResult(boolean matched, String matchedLevel, String targetIntent, double confidence, String directReply) {
        this.matched = matched;
        this.matchedLevel = matchedLevel;
        this.targetIntent = targetIntent;
        this.confidence = confidence;
        this.directReply = directReply;
    }

    /**
     * 快速构建基础命中结果 (向后兼容)
     */
    public static IntentMatchResult hit(String matchedLevel, String targetIntent, double confidence, String directReply) {
        return new IntentMatchResult(true, matchedLevel, targetIntent, confidence, directReply);
    }

    /**
     * 构建企业级 L1 命中结果
     */
    public static IntentMatchResult hitL1(String ruleCode, String ruleName, String targetType, String targetRef, String extractedParams, String directReply, long costMs) {
        IntentMatchResult result = new IntentMatchResult();
        result.setMatched(true);
        result.setMatchedLevel("L1");
        result.setRuleCode(ruleCode);
        result.setRuleName(ruleName);
        result.setTargetIntent(ruleCode);
        result.setTargetType(targetType);
        result.setTargetRef(targetRef);
        result.setExtractedParams(extractedParams);
        result.setDirectReply(directReply);
        result.setConfidence(1.0);
        result.setCostMs(costMs);
        return result;
    }

    /**
     * 快速构建未命中结果
     */
    public static IntentMatchResult miss() {
        IntentMatchResult result = new IntentMatchResult();
        result.setMatched(false);
        result.setConfidence(0.0);
        return result;
    }
}
