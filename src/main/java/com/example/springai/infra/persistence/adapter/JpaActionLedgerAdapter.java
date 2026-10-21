package com.example.springai.infra.persistence.adapter;

import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.port.ActionLedgerPort;
import com.example.springai.core.card.state.ApprovalState;
import com.example.springai.core.card.state.CardState;
import com.example.springai.core.card.state.ExecutionState;
import com.example.springai.infra.persistence.entity.ActionLedgerEntity;
import com.example.springai.infra.persistence.repository.ActionLedgerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 JPA (sys_action_ledger) 的动作账本持久化适配器。
 * 支持真实 MySQL 落盘与 CAS 原子状态迁移；当 CI 无数据库或表尚未初始化时平滑降级至进程内共享存储。
 */
@Component
@Slf4j
public class JpaActionLedgerAdapter implements ActionLedgerPort {

    private final ActionLedgerRepository repository;
    private final ObjectMapper objectMapper;
    private final Map<String, CardRecord> fallbackStore = new ConcurrentHashMap<>();
    private final Object casLock = new Object();

    public JpaActionLedgerAdapter() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public JpaActionLedgerAdapter(@Autowired(required = false) ActionLedgerRepository repository) {
        this(repository, new ObjectMapper());
    }

    public JpaActionLedgerAdapter(ActionLedgerRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = (objectMapper != null ? objectMapper.copy() : new ObjectMapper())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public boolean insert(CardRecord record) {
        Objects.requireNonNull(record, "record");
        synchronized (casLock) {
            if (fallbackStore.containsKey(record.getActionId())) {
                return false;
            }
            if (repository != null) {
                try {
                    if (repository.findByActionId(record.getActionId()).isPresent()) {
                        return false;
                    }
                    repository.save(toEntity(record));
                } catch (Exception ex) {
                    log.debug("[ActionLedger] 数据库写入不可用，降级写入共享账本缓存: {}", ex.getMessage());
                }
            }
            fallbackStore.put(record.getActionId(), InMemoryCardRegistry.copyRecord(record));
            return true;
        }
    }

    @Override
    public Optional<CardRecord> findByActionId(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return Optional.empty();
        }
        synchronized (casLock) {
            if (repository != null) {
                try {
                    Optional<ActionLedgerEntity> entityOpt = repository.findByActionId(actionId);
                    if (entityOpt.isPresent()) {
                        CardRecord loaded = toRecord(entityOpt.get());
                        fallbackStore.put(actionId, InMemoryCardRegistry.copyRecord(loaded));
                        return Optional.of(loaded);
                    }
                } catch (Exception ex) {
                    log.debug("[ActionLedger] 数据库查询不可用，使用共享账本缓存: {}", ex.getMessage());
                }
            }
            CardRecord cached = fallbackStore.get(actionId);
            return cached == null ? Optional.empty() : Optional.of(InMemoryCardRegistry.copyRecord(cached));
        }
    }

    @Override
    public boolean casTransition(String actionId, String expectedIssuanceId, CardState expectedState, CardRecord updated) {
        Objects.requireNonNull(updated, "updated");
        synchronized (casLock) {
            boolean dbHandled = false;
            if (repository != null) {
                try {
                    int rows = repository.casTransition(
                            actionId,
                            expectedIssuanceId,
                            expectedState.name(),
                            updated.getApprovalState().name(),
                            updated.getExecutionState().name(),
                            updated.getState().name(),
                            updated.getActorId(),
                            serializeMap(updated.getSubmittedValues()),
                            updated.getTicketId(),
                            updated.getFailReason(),
                            updated.getSubmitTime()
                    );
                    dbHandled = true;
                    if (rows <= 0) {
                        return false;
                    }
                } catch (Exception ex) {
                    log.debug("[ActionLedger] 数据库 CAS 不可用，使用本地原子 CAS: {}", ex.getMessage());
                }
            }
            CardRecord current = fallbackStore.get(actionId);
            if (!dbHandled) {
                if (current == null
                        || !Objects.equals(current.getIssuanceId(), expectedIssuanceId)
                        || current.getState() != expectedState) {
                    return false;
                }
            }
            fallbackStore.put(actionId, InMemoryCardRegistry.copyRecord(updated));
            return true;
        }
    }

    @Override
    public boolean updateState(String actionId, CardState expectedCurrentState, CardRecord updated) {
        Objects.requireNonNull(updated, "updated");
        synchronized (casLock) {
            boolean dbHandled = false;
            if (repository != null) {
                try {
                    int rows = repository.updateState(
                            actionId,
                            expectedCurrentState.name(),
                            updated.getApprovalState().name(),
                            updated.getExecutionState().name(),
                            updated.getState().name(),
                            updated.getTicketId(),
                            updated.getFailReason(),
                            updated.getSubmitTime()
                    );
                    dbHandled = true;
                    if (rows <= 0) {
                        return false;
                    }
                } catch (Exception ex) {
                    log.debug("[ActionLedger] 数据库状态更新不可用，使用本地原子状态更新: {}", ex.getMessage());
                }
            }
            CardRecord current = fallbackStore.get(actionId);
            if (!dbHandled) {
                if (current == null || current.getState() != expectedCurrentState) {
                    return false;
                }
            }
            fallbackStore.put(actionId, InMemoryCardRegistry.copyRecord(updated));
            return true;
        }
    }

    private ActionLedgerEntity toEntity(CardRecord record) {
        ActionLedgerEntity entity = new ActionLedgerEntity();
        entity.setActionId(record.getActionId());
        entity.setSessionId(record.getSessionId());
        entity.setTurnId(record.getTurnId());
        entity.setCardType(record.getCardType());
        entity.setTitle(record.getTitle());
        entity.setIssuanceId(record.getIssuanceId());
        entity.setIdempotencyKey(record.getEffectiveIdempotencyKey());
        entity.setToolVersion(record.getEffectiveToolVersion());
        entity.setApprovalState(record.getApprovalState().name());
        entity.setExecutionState(record.getExecutionState().name());
        entity.setCardState(record.getState().name());
        entity.setSnapshotJson(serializeCard(record.getSnapshot()));
        entity.setSubmittedValuesJson(serializeMap(record.getSubmittedValues()));
        entity.setActorId(record.getActorId());
        entity.setTicketId(record.getTicketId());
        entity.setFailReason(record.getFailReason());
        entity.setIssueTime(record.getIssueTime());
        entity.setExpireAt(record.getExpireAt());
        entity.setSubmitTime(record.getSubmitTime());
        return entity;
    }

    private CardRecord toRecord(ActionLedgerEntity entity) {
        CardState cardState = CardState.valueOf(entity.getCardState());
        ApprovalState approvalState = entity.getApprovalState() != null
                ? ApprovalState.valueOf(entity.getApprovalState())
                : ApprovalState.fromCardState(cardState);
        ExecutionState executionState = entity.getExecutionState() != null
                ? ExecutionState.valueOf(entity.getExecutionState())
                : ExecutionState.fromCardState(cardState);
        return CardRecord.builder()
                .actionId(entity.getActionId())
                .sessionId(entity.getSessionId())
                .turnId(entity.getTurnId())
                .cardType(entity.getCardType())
                .title(entity.getTitle())
                .issuanceId(entity.getIssuanceId())
                .idempotencyKey(entity.getIdempotencyKey())
                .toolVersion(entity.getToolVersion())
                .state(cardState)
                .approvalState(approvalState)
                .executionState(executionState)
                .snapshot(deserializeCard(entity.getSnapshotJson()))
                .submittedValues(deserializeMap(entity.getSubmittedValuesJson()))
                .actorId(entity.getActorId())
                .ticketId(entity.getTicketId())
                .failReason(entity.getFailReason())
                .issueTime(entity.getIssueTime() != null ? entity.getIssueTime() : 0L)
                .expireAt(entity.getExpireAt() != null ? entity.getExpireAt() : 0L)
                .submitTime(entity.getSubmitTime())
                .build();
    }

    private String serializeCard(InteractiveCard card) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化卡片快照失败", ex);
        }
    }

    private InteractiveCard deserializeCard(String json) {
        try {
            return objectMapper.readValue(json, InteractiveCard.class);
        } catch (Exception ex) {
            throw new IllegalStateException("反序列化卡片快照失败", ex);
        }
    }

    private String serializeMap(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化提交参数失败", ex);
        }
    }

    private Map<String, Object> deserializeMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { }));
        } catch (Exception ex) {
            throw new IllegalStateException("反序列化提交参数失败", ex);
        }
    }
}
