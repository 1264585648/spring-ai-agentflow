package com.example.springai.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 等待人工确认回合的持久化检查点实体 (对应 sys_turn_checkpoint 表)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sys_turn_checkpoint")
public class TurnCheckpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "turn_id", nullable = false, unique = true, length = 128)
    private String turnId;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "turn_state", nullable = false, length = 32)
    private String turnState = "WAITING_APPROVAL";

    @Column(name = "original_query", nullable = false, columnDefinition = "TEXT")
    private String originalQuery;

    @Column(name = "effective_query", columnDefinition = "TEXT")
    private String effectiveQuery;

    @Column(name = "pending_action_id", nullable = false, unique = true, length = 128)
    private String pendingActionId;

    @Column(name = "expected_issuance_id", nullable = false, length = 64)
    private String expectedIssuanceId;

    @Column(name = "expected_tool_version", nullable = false, length = 64)
    private String expectedToolVersion = "v1";

    @Column(name = "route_steps_json", columnDefinition = "MEDIUMTEXT")
    private String routeStepsJson;

    @Column(name = "completed_steps_json", columnDefinition = "MEDIUMTEXT")
    private String completedStepsJson;

    @Column(name = "suspended_step_order", nullable = false)
    private Integer suspendedStepOrder = 1;

    @Column(name = "total_steps", nullable = false)
    private Integer totalSteps = 1;

    @Column(name = "ticket_id", length = 128)
    private String ticketId;

    @Column(name = "resume_outcome", length = 512)
    private String resumeOutcome;

    @Column(name = "expire_at", nullable = false)
    private Long expireAt;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
