package com.example.springai.core.context;

import com.example.springai.core.routing.dto.DispatchStep;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 等待人工确认时的回合持久化检查点（TurnCheckpoint）。
 * 职责：
 * 1. 保存挂起回合的会话身份、原始/改写问题、已完成步骤结果（StepResult）与待审批动作版本锁；
 * 2. 进程重启后可从检查点恢复原回合上下文，将审批执行结果归入原回合；
 * 3. 拒绝、过期、取消或工具版本变化时阻止非法恢复。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnCheckpoint {
    private String turnId;
    private String sessionId;
    private String userId;
    @Builder.Default
    private TurnState turnState = TurnState.WAITING_APPROVAL;
    private String originalQuery;
    private String effectiveQuery;
    private String pendingActionId;
    private String expectedIssuanceId;
    @Builder.Default
    private String expectedToolVersion = "v1";
    @Builder.Default
    private List<DispatchStep> routeSteps = new ArrayList<>();
    @Builder.Default
    private List<StepResult> completedSteps = new ArrayList<>();
    @Builder.Default
    private int suspendedStepOrder = 1;
    @Builder.Default
    private int totalSteps = 1;
    private String ticketId;
    private String resumeOutcome;
    private long expireAt;
    private long createdAt;
    private long updatedAt;

    public boolean isExpired(long nowMillis) {
        return expireAt > 0 && nowMillis >= expireAt;
    }

    public TurnCheckpoint copy() {
        return TurnCheckpoint.builder()
                .turnId(turnId)
                .sessionId(sessionId)
                .userId(userId)
                .turnState(turnState)
                .originalQuery(originalQuery)
                .effectiveQuery(effectiveQuery)
                .pendingActionId(pendingActionId)
                .expectedIssuanceId(expectedIssuanceId)
                .expectedToolVersion(expectedToolVersion)
                .routeSteps(routeSteps == null ? new ArrayList<>() : new ArrayList<>(routeSteps))
                .completedSteps(completedSteps == null ? new ArrayList<>() : new ArrayList<>(completedSteps))
                .suspendedStepOrder(suspendedStepOrder)
                .totalSteps(totalSteps)
                .ticketId(ticketId)
                .resumeOutcome(resumeOutcome)
                .expireAt(expireAt)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
