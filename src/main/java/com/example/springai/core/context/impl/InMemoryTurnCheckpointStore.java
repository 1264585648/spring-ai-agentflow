package com.example.springai.core.context.impl;

import com.example.springai.core.card.exception.CardNotFoundException;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.core.context.StepResult;
import com.example.springai.core.context.TurnCheckpoint;
import com.example.springai.core.context.TurnCheckpointStore;
import com.example.springai.core.context.TurnState;

import java.time.Clock;
import java.util.*;

/**
 * 线程安全的回合检查点内存存储实现（可独立用于单测，或作为持久化存储的一级缓存）。
 */
public class InMemoryTurnCheckpointStore implements TurnCheckpointStore {
    private final Map<String, TurnCheckpoint> byTurnId = new LinkedHashMap<>();
    private final Map<String, String> actionToTurnId = new LinkedHashMap<>();
    private final Clock clock;

    public InMemoryTurnCheckpointStore() {
        this(Clock.systemUTC());
    }

    public InMemoryTurnCheckpointStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public synchronized void saveWaitingApproval(TurnCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.getTurnId() == null || checkpoint.getTurnId().isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
        if (checkpoint.getSessionId() == null || checkpoint.getSessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (checkpoint.getPendingActionId() == null || checkpoint.getPendingActionId().isBlank()) {
            throw new IllegalArgumentException("pendingActionId 不能为空");
        }
        long now = clock.millis();
        TurnCheckpoint copy = checkpoint.copy();
        copy.setTurnState(TurnState.WAITING_APPROVAL);
        if (copy.getCreatedAt() <= 0) {
            copy.setCreatedAt(now);
        }
        copy.setUpdatedAt(now);
        byTurnId.put(copy.getTurnId(), copy);
        actionToTurnId.put(copy.getPendingActionId(), copy.getTurnId());
    }

    @Override
    public synchronized Optional<TurnCheckpoint> findByTurnId(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return Optional.empty();
        }
        TurnCheckpoint cp = byTurnId.get(turnId);
        return cp == null ? Optional.empty() : Optional.of(cp.copy());
    }

    @Override
    public synchronized Optional<TurnCheckpoint> findByActionId(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return Optional.empty();
        }
        String turnId = actionToTurnId.get(actionId);
        if (turnId == null) {
            return Optional.empty();
        }
        return findByTurnId(turnId);
    }

    @Override
    public synchronized void verifyCanExecute(String actionId, String sessionId, String expectedIssuanceId, String currentToolVersion) {
        String turnId = actionToTurnId.get(actionId);
        if (turnId == null) {
            return;
        }
        TurnCheckpoint cp = byTurnId.get(turnId);
        if (cp == null) {
            return;
        }
        validateCheckpointForExecution(cp, sessionId, expectedIssuanceId, currentToolVersion, clock.millis());
    }

    public static void validateCheckpointForExecution(TurnCheckpoint cp, String sessionId,
                                                      String expectedIssuanceId, String currentToolVersion,
                                                      long nowMillis) {
        if (!Objects.equals(cp.getSessionId(), sessionId)) {
            throw new CardSecurityException("越权操作拦截：检查点不属于当前会话");
        }
        if (cp.getTurnState() == TurnState.EXPIRED || cp.isExpired(nowMillis)) {
            cp.setTurnState(TurnState.EXPIRED);
            cp.setUpdatedAt(nowMillis);
            throw new CardNotFoundException("等待确认的回合已过期，禁止继续执行");
        }
        if (cp.getTurnState() == TurnState.CANCELLED) {
            throw new CardStateConflictException("关联回合已取消，审批不能继续执行");
        }
        if (cp.getTurnState() != TurnState.WAITING_APPROVAL && cp.getTurnState() != TurnState.RESUMING) {
            throw new CardStateConflictException("回合当前状态不支持审批执行: " + cp.getTurnState());
        }
        if (expectedIssuanceId != null && cp.getExpectedIssuanceId() != null
                && !expectedIssuanceId.equals(cp.getExpectedIssuanceId())) {
            throw new CardStateConflictException("检查点签发版本与当前提案不一致");
        }
        String lockedVersion = (cp.getExpectedToolVersion() == null || cp.getExpectedToolVersion().isBlank())
                ? "v1" : cp.getExpectedToolVersion().trim();
        String actualVersion = (currentToolVersion == null || currentToolVersion().isBlank())
                ? "v1" : currentToolVersion.trim();
        if (!lockedVersion.equals(actualVersion)) {
            throw new CardStateConflictException("等待审批期间工具版本已变化（检查点版本=" + lockedVersion
                    + ", 当前版本=" + actualVersion + "），拒绝隐式切换实现，请重新审批或取消");
        }
    }

    private static String currentToolVersion() {
        return "v1";
    }

    @Override
    public synchronized Optional<TurnCheckpoint> resumeOnApprovedExecution(String actionId, String sessionId,
                                                                           String issuanceId, String toolVersion,
                                                                           String ticketId, String outcomeSummary) {
        String turnId = actionToTurnId.get(actionId);
        if (turnId == null) {
            return Optional.empty();
        }
        TurnCheckpoint cp = byTurnId.get(turnId);
        if (cp == null) {
            return Optional.empty();
        }
        if (cp.getTurnState() == TurnState.COMPLETED && Objects.equals(cp.getTicketId(), ticketId)) {
            return Optional.of(cp.copy());
        }
        long now = clock.millis();
        validateCheckpointForExecution(cp, sessionId, issuanceId, toolVersion, now);
        cp.setTurnState(TurnState.COMPLETED);
        cp.setTicketId(ticketId);
        cp.setResumeOutcome(outcomeSummary != null ? outcomeSummary : ("已执行审批动作，单号: " + ticketId));
        cp.setUpdatedAt(now);

        List<StepResult> steps = cp.getCompletedSteps() == null ? new ArrayList<>() : new ArrayList<>(cp.getCompletedSteps());
        steps.add(StepResult.builder()
                .stepOrder(steps.size() + 1)
                .agentCode("ACTION_GATE_RESUME")
                .status(StepResult.Status.SUCCESS)
                .summary(cp.getResumeOutcome())
                .structuredData(Map.of("actionId", actionId, "ticketId", ticketId != null ? ticketId : ""))
                .build());
        cp.setCompletedSteps(steps);
        return Optional.of(cp.copy());
    }

    @Override
    public synchronized Optional<TurnCheckpoint> cancelByActionId(String actionId, String sessionId, String reason) {
        String turnId = actionToTurnId.get(actionId);
        if (turnId == null) {
            return Optional.empty();
        }
        return cancelByTurnId(sessionId, turnId, reason);
    }

    @Override
    public synchronized Optional<TurnCheckpoint> cancelByTurnId(String sessionId, String turnId, String reason) {
        if (turnId == null || turnId.isBlank()) {
            return Optional.empty();
        }
        TurnCheckpoint cp = byTurnId.get(turnId);
        if (cp == null) {
            return Optional.empty();
        }
        if (sessionId != null && !Objects.equals(cp.getSessionId(), sessionId)) {
            throw new CardSecurityException("越权操作拦截：回合检查点不属于当前会话");
        }
        if (cp.getTurnState() == TurnState.WAITING_APPROVAL) {
            cp.setTurnState(TurnState.CANCELLED);
            cp.setResumeOutcome(reason != null && !reason.isBlank() ? reason : "回合或待审批提案已取消");
            cp.setUpdatedAt(clock.millis());
        }
        return Optional.of(cp.copy());
    }
}
