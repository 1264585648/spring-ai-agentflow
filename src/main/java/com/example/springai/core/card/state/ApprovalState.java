package com.example.springai.core.card.state;

/**
 * 动作提案的审批状态枚举（与执行状态 ExecutionState 显式分离）。
 * 遵循 12 号架构设计：批准了不等于执行了，审批态与执行态独立维护。
 */
public enum ApprovalState {
    /** 待人工确认 */
    PENDING,
    /** 人工已确认批准（进入执行态或已完成执行） */
    APPROVED,
    /** 审批校验未通过或被拒绝 */
    REJECTED,
    /** 超时未审批自动过期 */
    EXPIRED,
    /** 用户主动放弃或关联回合已取消 */
    CANCELLED;

    public static ApprovalState fromCardState(CardState state) {
        if (state == null) {
            return PENDING;
        }
        return switch (state) {
            case ISSUED -> PENDING;
            case SUBMITTING, SUBMITTED, FAILED, EXECUTION_UNKNOWN -> APPROVED;
            case EXPIRED -> EXPIRED;
            case CANCELLED -> CANCELLED;
        };
    }
}
