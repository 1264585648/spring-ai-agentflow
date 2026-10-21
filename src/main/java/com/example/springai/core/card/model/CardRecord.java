package com.example.springai.core.card.model;

import com.example.springai.core.card.state.ApprovalState;
import com.example.springai.core.card.state.CardState;
import com.example.springai.core.card.state.ExecutionState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 服务端签发与执行记录；由存储实现保护，不得将内部实例交给调用方。审批态与执行态独立维护。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardRecord {
    private String actionId;
    private String sessionId;
    private String turnId;
    private String cardType;
    private String title;
    private InteractiveCard snapshot;
    @Builder.Default
    private CardState state = CardState.ISSUED;
    @Builder.Default
    private ApprovalState approvalState = ApprovalState.PENDING;
    @Builder.Default
    private ExecutionState executionState = ExecutionState.NOT_STARTED;
    private long issueTime;
    private long expireAt;
    private Long submitTime;
    private String ticketId;
    private String failReason;
    /** 每次签发的唯一版本，不使用毫秒时间戳充当版本。 */
    private String issuanceId;
    /** 传给外部系统的稳定幂等键（默认等于 actionId）。 */
    private String idempotencyKey;
    /** 签发时锁定的工具版本（默认 v1）。 */
    @Builder.Default
    private String toolVersion = InteractiveCard.DEFAULT_TOOL_VERSION;
    /** 审批执行操作人标识 */
    private String actorId;
    /** 经校验合并后的最终提交参数快照 */
    private Map<String, Object> submittedValues;

    public void setState(CardState state) {
        this.state = state == null ? CardState.ISSUED : state;
        this.approvalState = ApprovalState.fromCardState(this.state);
        this.executionState = ExecutionState.fromCardState(this.state);
    }

    public ApprovalState getApprovalState() {
        return approvalState != null ? approvalState : ApprovalState.fromCardState(state);
    }

    public ExecutionState getExecutionState() {
        return executionState != null ? executionState : ExecutionState.fromCardState(state);
    }

    public String getEffectiveIdempotencyKey() {
        return (idempotencyKey != null && !idempotencyKey.isBlank()) ? idempotencyKey : actionId;
    }

    public String getEffectiveToolVersion() {
        if (toolVersion != null && !toolVersion.isBlank()) {
            return toolVersion.trim();
        }
        return snapshot != null ? snapshot.getEffectiveToolVersion() : InteractiveCard.DEFAULT_TOOL_VERSION;
    }

    public boolean isExpired() { return isExpired(System.currentTimeMillis()); }
    public boolean isExpired(long nowMillis) { return nowMillis >= expireAt; }
}
