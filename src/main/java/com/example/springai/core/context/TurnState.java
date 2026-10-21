package com.example.springai.core.context;

/**
 * 回合生命周期正式状态枚举（对齐 12 号架构文档：等待人工确认是回合的正式状态）。
 */
public enum TurnState {
    /** 正在运行推理或步骤调用 */
    RUNNING,
    /** 已下发待确认提案并保存检查点，回合挂起并释放会话执行槽位 */
    WAITING_APPROVAL,
    /** 审批确认通过，正在从检查点恢复原回合上下文 */
    RESUMING,
    /** 回合已完整结束（含审批后恢复完成） */
    COMPLETED,
    /** 用户主动取消回合或主动放弃提案 */
    CANCELLED,
    /** 等待审批超时未处理自动过期 */
    EXPIRED,
    /** 步骤或流水线执行失败 */
    FAILED
}
