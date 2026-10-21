package com.example.springai.infra.persistence.checkpoint;

import com.example.springai.core.context.StepResult;
import com.example.springai.core.context.TurnCheckpoint;
import com.example.springai.core.context.TurnCheckpointStore;
import com.example.springai.core.context.TurnState;
import com.example.springai.core.context.impl.InMemoryTurnCheckpointStore;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.infra.persistence.entity.TurnCheckpointEntity;
import com.example.springai.infra.persistence.repository.TurnCheckpointRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 持久化回合挂起检查点存储实现（支持 JPA 落盘与进程重启恢复；无数据库环境自动降级至内存存储）。
 */
@Component
@Slf4j
public class PersistentTurnCheckpointStore implements TurnCheckpointStore {

    private final TurnCheckpointRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final InMemoryTurnCheckpointStore memoryDelegate;

    public PersistentTurnCheckpointStore() {
        this(null, new ObjectMapper(), Clock.systemUTC());
    }

    @Autowired
    public PersistentTurnCheckpointStore(@Autowired(required = false) TurnCheckpointRepository repository) {
        this(repository, new ObjectMapper(), Clock.systemUTC());
    }

    public PersistentTurnCheckpointStore(TurnCheckpointRepository repository, Clock clock) {
        this(repository, new ObjectMapper(), clock);
    }

    public PersistentTurnCheckpointStore(TurnCheckpointRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = (objectMapper != null ? objectMapper.copy() : new ObjectMapper())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.clock = Objects.requireNonNull(clock);
        this.memoryDelegate = new InMemoryTurnCheckpointStore(clock);
    }

    @Override
    public synchronized void saveWaitingApproval(TurnCheckpoint checkpoint) {
        memoryDelegate.saveWaitingApproval(checkpoint);
        TurnCheckpoint saved = memoryDelegate.findByTurnId(checkpoint.getTurnId()).orElse(checkpoint);
        persistToDb(saved);
    }

    @Override
    public synchronized Optional<TurnCheckpoint> findByTurnId(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return Optional.empty();
        }
        Optional<TurnCheckpoint> fromDb = loadByTurnIdFromDb(turnId);
        if (fromDb.isPresent()) {
            return fromDb;
        }
        return memoryDelegate.findByTurnId(turnId);
    }

    @Override
    public synchronized Optional<TurnCheckpoint> findByActionId(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return Optional.empty();
        }
        Optional<TurnCheckpoint> fromDb = loadByActionIdFromDb(actionId);
        if (fromDb.isPresent()) {
            return fromDb;
        }
        return memoryDelegate.findByActionId(actionId);
    }

    @Override
    public synchronized void verifyCanExecute(String actionId, String sessionId, String expectedIssuanceId, String currentToolVersion) {
        Optional<TurnCheckpoint> cpOpt = findByActionId(actionId);
        if (cpOpt.isEmpty()) {
            return;
        }
        TurnCheckpoint cp = cpOpt.get();
        long now = clock.millis();
        try {
            InMemoryTurnCheckpointStore.validateCheckpointForExecution(cp, sessionId, expectedIssuanceId, currentToolVersion, now);
        } catch (RuntimeException ex) {
            if (cp.getTurnState() == TurnState.EXPIRED) {
                persistToDb(cp);
            }
            throw ex;
        }
    }

    @Override
    public synchronized Optional<TurnCheckpoint> resumeOnApprovedExecution(String actionId, String sessionId,
                                                                           String issuanceId, String toolVersion,
                                                                           String ticketId, String outcomeSummary) {
        Optional<TurnCheckpoint> cpOpt = findByActionId(actionId);
        if (cpOpt.isEmpty()) {
            return Optional.empty();
        }
        TurnCheckpoint cp = cpOpt.get();
        if (cp.getTurnState() == TurnState.COMPLETED && Objects.equals(cp.getTicketId(), ticketId)) {
            return Optional.of(cp.copy());
        }
        long now = clock.millis();
        InMemoryTurnCheckpointStore.validateCheckpointForExecution(cp, sessionId, issuanceId, toolVersion, now);
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
                .structuredData(java.util.Map.of("actionId", actionId, "ticketId", ticketId != null ? ticketId : ""))
                .build());
        cp.setCompletedSteps(steps);
        persistToDb(cp);
        memoryDelegate.resumeOnApprovedExecution(actionId, sessionId, issuanceId, toolVersion, ticketId, outcomeSummary);
        return Optional.of(cp.copy());
    }

    @Override
    public synchronized Optional<TurnCheckpoint> cancelByActionId(String actionId, String sessionId, String reason) {
        Optional<TurnCheckpoint> cpOpt = findByActionId(actionId);
        if (cpOpt.isEmpty()) {
            return Optional.empty();
        }
        return cancelInternal(cpOpt.get(), sessionId, reason);
    }

    @Override
    public synchronized Optional<TurnCheckpoint> cancelByTurnId(String sessionId, String turnId, String reason) {
        Optional<TurnCheckpoint> cpOpt = findByTurnId(turnId);
        if (cpOpt.isEmpty()) {
            return Optional.empty();
        }
        return cancelInternal(cpOpt.get(), sessionId, reason);
    }

    private Optional<TurnCheckpoint> cancelInternal(TurnCheckpoint cp, String sessionId, String reason) {
        if (sessionId != null && !Objects.equals(cp.getSessionId(), sessionId)) {
            throw new com.example.springai.core.card.exception.CardSecurityException("越权操作拦截：回合检查点不属于当前会话");
        }
        if (cp.getTurnState() == TurnState.WAITING_APPROVAL) {
            cp.setTurnState(TurnState.CANCELLED);
            cp.setResumeOutcome(reason != null && !reason.isBlank() ? reason : "回合或待审批提案已取消");
            cp.setUpdatedAt(clock.millis());
            persistToDb(cp);
            memoryDelegate.cancelByTurnId(sessionId, cp.getTurnId(), reason);
        }
        return Optional.of(cp.copy());
    }

    private void persistToDb(TurnCheckpoint cp) {
        if (repository == null) {
            return;
        }
        try {
            TurnCheckpointEntity entity = repository.findByTurnId(cp.getTurnId()).orElseGet(TurnCheckpointEntity::new);
            entity.setTurnId(cp.getTurnId());
            entity.setSessionId(cp.getSessionId());
            entity.setUserId(cp.getUserId());
            entity.setTurnState(cp.getTurnState().name());
            entity.setOriginalQuery(cp.getOriginalQuery() != null ? cp.getOriginalQuery() : "");
            entity.setEffectiveQuery(cp.getEffectiveQuery());
            entity.setPendingActionId(cp.getPendingActionId());
            entity.setExpectedIssuanceId(cp.getExpectedIssuanceId() != null ? cp.getExpectedIssuanceId() : "");
            entity.setExpectedToolVersion(cp.getExpectedToolVersion() != null ? cp.getExpectedToolVersion() : "v1");
            entity.setRouteStepsJson(writeJson(cp.getRouteSteps()));
            entity.setCompletedStepsJson(writeJson(cp.getCompletedSteps()));
            entity.setSuspendedStepOrder(cp.getSuspendedStepOrder());
            entity.setTotalSteps(cp.getTotalSteps());
            entity.setTicketId(cp.getTicketId());
            entity.setResumeOutcome(cp.getResumeOutcome());
            entity.setExpireAt(cp.getExpireAt());
            entity.setCreatedAt(cp.getCreatedAt() > 0 ? cp.getCreatedAt() : clock.millis());
            entity.setUpdatedAt(cp.getUpdatedAt() > 0 ? cp.getUpdatedAt() : clock.millis());
            repository.save(entity);
        } catch (Exception ex) {
            log.debug("[TurnCheckpoint] 数据库存储不可用，使用进程内检查点缓存: {}", ex.getMessage());
        }
    }

    private Optional<TurnCheckpoint> loadByTurnIdFromDb(String turnId) {
        if (repository == null) {
            return Optional.empty();
        }
        try {
            return repository.findByTurnId(turnId).map(this::toDomain);
        } catch (Exception ex) {
            log.debug("[TurnCheckpoint] 数据库按 turnId 查询不可用: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<TurnCheckpoint> loadByActionIdFromDb(String actionId) {
        if (repository == null) {
            return Optional.empty();
        }
        try {
            return repository.findByPendingActionId(actionId).map(this::toDomain);
        } catch (Exception ex) {
            log.debug("[TurnCheckpoint] 数据库按 actionId 查询不可用: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private TurnCheckpoint toDomain(TurnCheckpointEntity entity) {
        return TurnCheckpoint.builder()
                .turnId(entity.getTurnId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .turnState(TurnState.valueOf(entity.getTurnState()))
                .originalQuery(entity.getOriginalQuery())
                .effectiveQuery(entity.getEffectiveQuery())
                .pendingActionId(entity.getPendingActionId())
                .expectedIssuanceId(entity.getExpectedIssuanceId())
                .expectedToolVersion(entity.getExpectedToolVersion())
                .routeSteps(readRouteSteps(entity.getRouteStepsJson()))
                .completedSteps(readStepResults(entity.getCompletedStepsJson()))
                .suspendedStepOrder(entity.getSuspendedStepOrder() != null ? entity.getSuspendedStepOrder() : 1)
                .totalSteps(entity.getTotalSteps() != null ? entity.getTotalSteps() : 1)
                .ticketId(entity.getTicketId())
                .resumeOutcome(entity.getResumeOutcome())
                .expireAt(entity.getExpireAt() != null ? entity.getExpireAt() : 0L)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt() : 0L)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt() : 0L)
                .build();
    }

    private String writeJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化检查点字段失败", ex);
        }
    }

    private List<DispatchStep> readRouteSteps(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<DispatchStep>>() { }));
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private List<StepResult> readStepResults(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<StepResult>>() { }));
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }
}
