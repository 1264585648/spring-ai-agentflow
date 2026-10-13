package com.example.springai.core.card.state;

/** 卡片生命周期；执行结果未知不是“执行失败”，不得重新提交。 */
public enum CardState {
    ISSUED,
    SUBMITTING,
    SUBMITTED,
    FAILED,
    EXPIRED,
    CANCELLED,
    EXECUTION_UNKNOWN
}
