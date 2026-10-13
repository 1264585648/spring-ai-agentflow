package com.example.springai.core.card;

import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.model.InteractiveCard;

import java.util.Map;
import java.util.Optional;

/** 提案签发、快照及原子状态流转。实现方不得向调用方泄露可修改的存储对象。 */
public interface CardRegistry {
    /** actionId 必须唯一，禁止覆盖已签发记录；sessionId/cardType 必须非空。 */
    void issue(String sessionId, InteractiveCard card);

    /** 仅 ISSUED 且未过期的同会话提案可以跃迁为 SUBMITTING。 */
    CardRecord claim(String actionId, String sessionId);

    /** 兼容的纯参数校验入口；执行时由 ActionGate 重建最终参数。 */
    void validate(CardRecord record, Map<String, Object> submittedValues);

    /** 只允许 SUBMITTING -> SUBMITTED，返回值必须明确成功。 */
    void complete(String actionId, CardSubmitResult result);

    /**
     * 仅用于能够证明尚未执行的前置失败。allowRetry 不得用于执行超时。
     * ActionGate 的执行后异常一律调用 markExecutionUnknown。
     */
    void fail(String actionId, String failReason, boolean allowRetry);

    /** 执行已开始但结果无法确认，禁止自动重试或重新激活该 actionId。 */
    void markExecutionUnknown(String actionId, String reason);

    /** 返回脱离存储的快照副本。 */
    Optional<CardRecord> getRecord(String actionId);
}
