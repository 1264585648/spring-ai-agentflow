package com.example.springai.core.context;

import java.util.Optional;

/**
 * 回合挂起检查点存储契约（core 层 SPI，不绑定具体数据库实现）。
 */
public interface TurnCheckpointStore {
    /**
     * 保存挂起等待人工审批的回合检查点。
     */
    void saveWaitingApproval(TurnCheckpoint checkpoint);

    /**
     * 按 turnId 查询检查点副本。
     */
    Optional<TurnCheckpoint> findByTurnId(String turnId);

    /**
     * 按待审批 actionId 查询关联的回合检查点副本。
     */
    Optional<TurnCheckpoint> findByActionId(String actionId);

    /**
     * 在 ActionGate 校验前核查关联回合检查点是否仍处于合法待确认状态（未取消、未过期、工具版本未变）。
     */
    void verifyCanExecute(String actionId, String sessionId, String expectedIssuanceId, String currentToolVersion);

    /**
     * 审批执行（或对账恢复）成功后，原子从检查点恢复原回合并将执行结果归入回合上下文，流转为 COMPLETED。
     */
    Optional<TurnCheckpoint> resumeOnApprovedExecution(String actionId, String sessionId, String issuanceId,
                                                       String toolVersion, String ticketId, String outcomeSummary);

    /**
     * 用户主动放弃卡片时，将关联挂起回合流转为 CANCELLED。
     */
    Optional<TurnCheckpoint> cancelByActionId(String actionId, String sessionId, String reason);

    /**
     * 用户主动取消回合时，若该回合正处于 WAITING_APPROVAL，将其流转为 CANCELLED 并返回检查点。
     */
    Optional<TurnCheckpoint> cancelByTurnId(String sessionId, String turnId, String reason);
}
