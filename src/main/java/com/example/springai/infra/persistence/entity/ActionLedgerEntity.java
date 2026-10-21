package com.example.springai.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ActionGate 动作提案审批与执行持久化账本实体 (对应 sys_action_ledger 表)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sys_action_ledger")
public class ActionLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_id", nullable = false, unique = true, length = 128)
    private String actionId;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "turn_id", length = 128)
    private String turnId;

    @Column(name = "card_type", nullable = false, length = 64)
    private String cardType;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "issuance_id", nullable = false, length = 64)
    private String issuanceId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "tool_version", nullable = false, length = 64)
    private String toolVersion = "v1";

    @Column(name = "approval_state", nullable = false, length = 32)
    private String approvalState = "PENDING";

    @Column(name = "execution_state", nullable = false, length = 32)
    private String executionState = "NOT_STARTED";

    @Column(name = "card_state", nullable = false, length = 32)
    private String cardState = "ISSUED";

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String snapshotJson;

    @Column(name = "submitted_values_json", columnDefinition = "MEDIUMTEXT")
    private String submittedValuesJson;

    @Column(name = "actor_id", length = 128)
    private String actorId;

    @Column(name = "ticket_id", length = 128)
    private String ticketId;

    @Column(name = "fail_reason", length = 512)
    private String failReason;

    @Column(name = "issue_time", nullable = false)
    private Long issueTime;

    @Column(name = "expire_at", nullable = false)
    private Long expireAt;

    @Column(name = "submit_time")
    private Long submitTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void onPreUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
