package com.example.springai.core.card.state;

/**
 * 动作提案的执行状态枚举（与审批状态 ApprovalState 显式分离）。
 * 遵循 12 号架构设计：执行结果未知（EXECUTION_UNKNOWN）不等同于执行失败，须走查询对账或人工处置，绝不盲目重试。
 */
public enum ExecutionState {
    /** 尚未进入执行器 */
    NOT_STARTED,
    /** 已通过 CAS 原子领取执行权，正在调用外部系统 */
    EXECUTING,
    /** 外部系统返回确定成功回执并已归档 */
    SUCCEEDED,
    /** 在进入外部副作用前确定失败 */
    FAILED,
    /** 已进入执行器但未拿到确定成功回执（超时、断连或落库前崩溃），需查询对账 */
    EXECUTION_UNKNOWN;

    public static ExecutionState fromCardState(CardState state) {
        if (state == null) {
            return NOT_STARTED;
        }
        return switch (state) {
            case ISSUED, EXPIRED, CANCELLED -> NOT_STARTED;
            case SUBMITTING -> EXECUTING;
            case SUBMITTED -> SUCCEEDED;
            case FAILED -> FAILED;
            case EXECUTION_UNKNOWN -> EXECUTION_UNKNOWN;
        };
    }
}
