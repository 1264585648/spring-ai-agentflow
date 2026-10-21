package com.example.springai.core.card.impl;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.action.CardFormValues;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.CardNotFoundException;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.port.ActionLedgerPort;
import com.example.springai.core.card.state.ApprovalState;
import com.example.springai.core.card.state.CardState;
import com.example.springai.core.card.state.ExecutionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.util.*;

/**
 * 动作提案注册表实现。
 * 1. 单机有界存储：签发、申领、清理共享短临界区；
 * 2. 支持可选挂载 ActionLedgerPort 持久化端口，实现重启恢复与跨实例 CAS 校验；
 * 3. EXECUTION_UNKNOWN 永不自动淘汰，避免未知写操作因 TTL 而被重新开放。
 */
@Component
public class InMemoryCardRegistry implements CardRegistry {
    public static final long DEFAULT_CARD_TTL_MS = 15 * 60 * 1000L;
    public static final long DEFAULT_TERMINAL_RETENTION_MS = 60 * 60 * 1000L;
    public static final int DEFAULT_MAX_CAPACITY = 10000;
    private final Map<String, CardRecord> store = new LinkedHashMap<>();
    private final Clock clock;
    private final int maxCapacity;
    private final long cardTtlMs;
    private final long terminalRetentionMs;
    private final ActionLedgerPort ledgerPort;

    public InMemoryCardRegistry() {
        this(Clock.systemUTC(), DEFAULT_MAX_CAPACITY, DEFAULT_CARD_TTL_MS, DEFAULT_TERMINAL_RETENTION_MS, null);
    }

    @Autowired
    public InMemoryCardRegistry(@Autowired(required = false) ActionLedgerPort ledgerPort) {
        this(Clock.systemUTC(), DEFAULT_MAX_CAPACITY, DEFAULT_CARD_TTL_MS, DEFAULT_TERMINAL_RETENTION_MS, ledgerPort);
    }

    public InMemoryCardRegistry(Clock clock) {
        this(clock, DEFAULT_MAX_CAPACITY, DEFAULT_CARD_TTL_MS, DEFAULT_TERMINAL_RETENTION_MS, null);
    }

    public InMemoryCardRegistry(Clock clock, ActionLedgerPort ledgerPort) {
        this(clock, DEFAULT_MAX_CAPACITY, DEFAULT_CARD_TTL_MS, DEFAULT_TERMINAL_RETENTION_MS, ledgerPort);
    }

    public InMemoryCardRegistry(Clock clock, int maxCapacity, long cardTtlMs, long terminalRetentionMs) {
        this(clock, maxCapacity, cardTtlMs, terminalRetentionMs, null);
    }

    public InMemoryCardRegistry(Clock clock, int maxCapacity, long cardTtlMs, long terminalRetentionMs, ActionLedgerPort ledgerPort) {
        this.clock = Objects.requireNonNull(clock);
        if (maxCapacity <= 0 || cardTtlMs <= 0 || terminalRetentionMs <= 0) {
            throw new IllegalArgumentException("容量和保留时间必须为正数");
        }
        this.maxCapacity = maxCapacity;
        this.cardTtlMs = cardTtlMs;
        this.terminalRetentionMs = terminalRetentionMs;
        this.ledgerPort = ledgerPort;
    }

    @Override
    public synchronized void issue(String sessionId, InteractiveCard card) {
        requireText(sessionId, "sessionId");
        Objects.requireNonNull(card, "card");
        requireText(card.getActionId(), "actionId");
        requireText(card.getCardType(), "cardType");
        if (store.containsKey(card.getActionId())
                || (ledgerPort != null && ledgerPort.findByActionId(card.getActionId()).isPresent())) {
            throw new CardStateConflictException("actionId 已签发，禁止覆盖");
        }
        InteractiveCard snapshot = copyCard(card);
        evictExpired(clock.millis());
        if (store.size() >= maxCapacity) {
            throw new IllegalStateException("CARD_CAPACITY_EXCEEDED: 提案存储已满，拒绝新签发");
        }
        long now = clock.millis();
        String issuanceId = UUID.randomUUID().toString();
        CardRecord record = CardRecord.builder()
                .actionId(card.getActionId())
                .sessionId(sessionId)
                .turnId(card.getTurnId())
                .cardType(card.getCardType())
                .title(card.getTitle())
                .snapshot(snapshot)
                .issuanceId(issuanceId)
                .idempotencyKey(card.getActionId())
                .toolVersion(snapshot.getEffectiveToolVersion())
                .state(CardState.ISSUED)
                .approvalState(ApprovalState.PENDING)
                .executionState(ExecutionState.NOT_STARTED)
                .issueTime(now)
                .expireAt(Math.addExact(now, cardTtlMs))
                .build();
        if (ledgerPort != null && !ledgerPort.insert(copyRecord(record))) {
            throw new CardStateConflictException("actionId 已在持久账本签发，禁止覆盖");
        }
        store.put(card.getActionId(), record);
    }

    @Override
    public synchronized CardRecord claim(String actionId, String sessionId) {
        requireText(sessionId, "sessionId");
        CardRecord current = requireRecord(actionId);
        return claimInternal(current, sessionId, null, null);
    }

    @Override
    public synchronized CardRecord claim(CardRecord expected) {
        return claim(expected, null, null);
    }

    @Override
    public synchronized CardRecord claim(CardRecord expected, String actorId, Map<String, Object> submittedValues) {
        Objects.requireNonNull(expected, "expected");
        CardRecord current = requireRecord(expected.getActionId());
        if (expected.getIssuanceId() == null || !expected.getIssuanceId().equals(current.getIssuanceId())) {
            throw new CardStateConflictException("提案签发版本已变化，拒绝执行过时的批准");
        }
        return claimInternal(current, expected.getSessionId(), actorId, submittedValues);
    }

    private CardRecord claimInternal(CardRecord record, String sessionId, String actorId, Map<String, Object> submittedValues) {
        if (!Objects.equals(record.getSessionId(), sessionId)) {
            throw new CardSecurityException("越权操作拦截：卡片不属于当前会话");
        }
        if (record.getState() == CardState.EXPIRED) throw new CardNotFoundException("卡片已过期");
        if (record.getState() != CardState.ISSUED) {
            throw new CardStateConflictException("卡片不可再次提交，当前状态: " + record.getState());
        }
        long now = clock.millis();
        if (record.isExpired(now)) {
            record.setState(CardState.EXPIRED);
            if (ledgerPort != null) {
                ledgerPort.casTransition(record.getActionId(), record.getIssuanceId(), CardState.ISSUED, copyRecord(record));
            }
            throw new CardNotFoundException("卡片已过期");
        }
        CardRecord updated = copyRecord(record);
        updated.setState(CardState.SUBMITTING);
        updated.setSubmitTime(now);
        if (actorId != null) updated.setActorId(actorId);
        if (submittedValues != null) updated.setSubmittedValues(new LinkedHashMap<>(submittedValues));
        if (ledgerPort != null && !ledgerPort.casTransition(record.getActionId(), record.getIssuanceId(), CardState.ISSUED, updated)) {
            throw new CardStateConflictException("并发申领冲突：提案状态已变更");
        }
        record.setState(CardState.SUBMITTING);
        record.setSubmitTime(now);
        if (actorId != null) record.setActorId(actorId);
        if (submittedValues != null) record.setSubmittedValues(new LinkedHashMap<>(submittedValues));
        return copyRecord(record);
    }

    @Override
    public synchronized CardRecord cancel(String actionId, String sessionId, String reason) {
        requireText(sessionId, "sessionId");
        return cancelInternal(requireRecord(actionId), sessionId, reason);
    }

    @Override
    public synchronized CardRecord cancel(CardRecord expected, String reason) {
        Objects.requireNonNull(expected, "expected");
        CardRecord current = requireRecord(expected.getActionId());
        if (expected.getIssuanceId() == null || !expected.getIssuanceId().equals(current.getIssuanceId())) {
            throw new CardStateConflictException("提案签发版本已变化，拒绝执行过时的放弃操作");
        }
        return cancelInternal(current, expected.getSessionId(), reason);
    }

    private CardRecord cancelInternal(CardRecord record, String sessionId, String reason) {
        if (!Objects.equals(record.getSessionId(), sessionId)) {
            throw new CardSecurityException("越权操作拦截：卡片不属于当前会话");
        }
        if (record.getState() == CardState.EXPIRED) {
            throw new CardNotFoundException("卡片已过期");
        }
        long now = clock.millis();
        if (record.isExpired(now)) {
            record.setState(CardState.EXPIRED);
            if (ledgerPort != null) {
                ledgerPort.casTransition(record.getActionId(), record.getIssuanceId(), CardState.ISSUED, copyRecord(record));
            }
            throw new CardNotFoundException("卡片已过期");
        }
        if (record.getState() != CardState.ISSUED) {
            throw new CardStateConflictException("卡片不可放弃，当前状态: " + record.getState());
        }
        String finalReason = reason != null && !reason.isBlank() ? reason : "用户主动放弃";
        CardRecord updated = copyRecord(record);
        updated.setState(CardState.CANCELLED);
        updated.setFailReason(finalReason);
        updated.setSubmitTime(now);
        if (ledgerPort != null && !ledgerPort.casTransition(record.getActionId(), record.getIssuanceId(), CardState.ISSUED, updated)) {
            throw new CardStateConflictException("并发状态冲突：卡片已被消费或取消");
        }
        record.setState(CardState.CANCELLED);
        record.setFailReason(finalReason);
        record.setSubmitTime(now);
        return copyRecord(record);
    }

    @Override
    public void validate(CardRecord record, Map<String, Object> values) {
        CardFormValues.validateAndMerge(record, values);
    }

    @Override
    public synchronized void complete(String actionId, CardSubmitResult result) {
        if (result == null || !result.isSuccess() || result.getTicketId() == null || result.getTicketId().isBlank()) {
            throw new IllegalArgumentException("只有带有效回执的确定成功才能归档");
        }
        CardRecord record = requireSubmitting(actionId);
        long now = clock.millis();
        record.setState(CardState.SUBMITTED);
        record.setTicketId(result.getTicketId());
        record.setSubmitTime(now);
        if (ledgerPort != null) {
            ledgerPort.updateState(actionId, CardState.SUBMITTING, copyRecord(record));
        }
    }

    @Override
    public synchronized CardRecord reconcile(String actionId, CardSubmitResult recoveredResult) {
        if (recoveredResult == null || !recoveredResult.isSuccess()
                || recoveredResult.getTicketId() == null || recoveredResult.getTicketId().isBlank()) {
            throw new IllegalArgumentException("对账恢复必须提供带有效 ticketId 的成功结果");
        }
        CardRecord record = requireRecord(actionId);
        if (record.getState() == CardState.SUBMITTED) {
            return copyRecord(record);
        }
        if (record.getState() != CardState.SUBMITTING && record.getState() != CardState.EXECUTION_UNKNOWN) {
            throw new CardStateConflictException("仅处于 SUBMITTING 或 EXECUTION_UNKNOWN 的提案支持对账收敛，当前状态: " + record.getState());
        }
        CardState previousState = record.getState();
        record.setState(CardState.SUBMITTED);
        record.setTicketId(recoveredResult.getTicketId());
        record.setFailReason(null);
        record.setSubmitTime(clock.millis());
        if (ledgerPort != null) {
            ledgerPort.updateState(actionId, previousState, copyRecord(record));
        }
        return copyRecord(record);
    }

    @Override
    public synchronized void fail(String actionId, String reason, boolean allowRetry) {
        CardRecord record = requireSubmitting(actionId);
        long now = clock.millis();
        record.setFailReason(reason);
        record.setSubmitTime(now);
        record.setState(!allowRetry ? CardState.FAILED
                : record.isExpired(now) ? CardState.EXPIRED : CardState.ISSUED);
        if (ledgerPort != null) {
            ledgerPort.updateState(actionId, CardState.SUBMITTING, copyRecord(record));
        }
    }

    @Override
    public synchronized void markExecutionUnknown(String actionId, String reason) {
        CardRecord record = requireRecord(actionId);
        if (record.getState() != CardState.SUBMITTING && record.getState() != CardState.EXECUTION_UNKNOWN) {
            throw new CardStateConflictException("仅执行中的提案允许标记为结果未知");
        }
        CardState previousState = record.getState();
        record.setState(CardState.EXECUTION_UNKNOWN);
        record.setFailReason(reason);
        record.setSubmitTime(clock.millis());
        if (ledgerPort != null) {
            ledgerPort.updateState(actionId, previousState, copyRecord(record));
        }
    }

    @Override
    public synchronized Optional<CardRecord> getRecord(String actionId) {
        if (ledgerPort != null) {
            Optional<CardRecord> loaded = ledgerPort.findByActionId(actionId);
            if (loaded.isPresent()) {
                store.put(actionId, copyRecord(loaded.get()));
                return loaded.map(InMemoryCardRegistry::copyRecord);
            }
        }
        CardRecord record = store.get(actionId);
        return record == null ? Optional.empty() : Optional.of(copyRecord(record));
    }

    @Override
    public boolean isExpired(CardRecord record) {
        return record != null && record.isExpired(clock.millis());
    }

    public synchronized int size() { return store.size(); }
    public synchronized void evictExpired() { evictExpired(clock.millis()); }
    public synchronized void evictExpired(long now) {
        store.values().removeIf(r -> switch (r.getState()) {
            case SUBMITTING, EXECUTION_UNKNOWN -> false;
            case ISSUED, EXPIRED -> r.isExpired(now);
            case SUBMITTED, FAILED, CANCELLED -> r.getSubmitTime() != null
                    && now - r.getSubmitTime() >= terminalRetentionMs;
        });
    }

    private CardRecord requireRecord(String actionId) {
        requireText(actionId, "actionId");
        if (ledgerPort != null) {
            Optional<CardRecord> fromPort = ledgerPort.findByActionId(actionId);
            if (fromPort.isPresent()) {
                CardRecord latest = copyRecord(fromPort.get());
                store.put(actionId, latest);
                return latest;
            }
        }
        CardRecord record = store.get(actionId);
        if (record == null) throw new CardNotFoundException("卡片不存在或已过期");
        return record;
    }

    private CardRecord requireSubmitting(String actionId) {
        CardRecord record = requireRecord(actionId);
        if (record.getState() != CardState.SUBMITTING) {
            throw new CardStateConflictException("仅执行中的提案允许结果归档");
        }
        return record;
    }

    private static void requireText(String text, String name) {
        if (text == null || text.isBlank() || text.length() > 128) {
            throw new IllegalArgumentException(name + " 必须为 1~128 字符的非空标识");
        }
    }

    public static CardRecord copyRecord(CardRecord r) {
        return CardRecord.builder()
                .actionId(r.getActionId())
                .sessionId(r.getSessionId())
                .turnId(r.getTurnId())
                .cardType(r.getCardType())
                .title(r.getTitle())
                .snapshot(copyCard(r.getSnapshot()))
                .issuanceId(r.getIssuanceId())
                .idempotencyKey(r.getEffectiveIdempotencyKey())
                .toolVersion(r.getEffectiveToolVersion())
                .state(r.getState())
                .approvalState(r.getApprovalState())
                .executionState(r.getExecutionState())
                .issueTime(r.getIssueTime())
                .expireAt(r.getExpireAt())
                .submitTime(r.getSubmitTime())
                .ticketId(r.getTicketId())
                .failReason(r.getFailReason())
                .actorId(r.getActorId())
                .submittedValues(r.getSubmittedValues() == null ? null : new LinkedHashMap<>(r.getSubmittedValues()))
                .build();
    }

    public static InteractiveCard copyCard(InteractiveCard source) {
        if (source == null || source.getFields() == null || source.getFields().size() > 64) {
            throw new IllegalArgumentException("卡片必须提供不超过 64 项的字段定义");
        }
        InteractiveCard copy = new InteractiveCard(source.getActionId(), source.getCardType(), source.getTitle(),
                source.getDescription(), new ArrayList<>(), source.getConfirmButtonText(), source.getCancelButtonText(),
                source.getTurnId(), source.isSimulation(), source.getEffectiveToolVersion());
        Set<String> keys = new HashSet<>();
        for (CardFormField f : source.getFields()) {
            if (f == null || f.getFieldKey() == null || f.getFieldKey().isBlank() || !keys.add(f.getFieldKey())) {
                throw new IllegalArgumentException("卡片字段为空或重复");
            }
            CardFormField field = new CardFormField(f.getFieldKey(), f.getLabel(), f.getType(),
                    scalar(f.getValue()), f.isEditable(), f.isRequired());
            field.setMinLimit(scalar(f.getMinLimit()));
            field.setMaxLimit(scalar(f.getMaxLimit()));
            field.setPlaceholder(f.getPlaceholder());
            copy.getFields().add(field);
        }
        return copy;
    }

    private static Object scalar(Object value) {
        if (value instanceof String text && text.length() > 8192) throw new IllegalArgumentException("字段内容过长");
        if (value instanceof Double d && !Double.isFinite(d) || value instanceof Float f && !Float.isFinite(f)) {
            throw new IllegalArgumentException("非有限数字不可签发");
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigDecimal
                || value instanceof BigInteger || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Double || value instanceof Float) return value;
        throw new IllegalArgumentException("卡片只支持不可变标量，不支持对象或数组");
    }
}
