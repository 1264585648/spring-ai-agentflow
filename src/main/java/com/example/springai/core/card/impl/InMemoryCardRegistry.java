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
import com.example.springai.core.card.state.CardState;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 单机提案存储。终态保留为去重墓碑；不提供跨节点或进程重启后的幂等保障。 */
@Component
public class InMemoryCardRegistry implements CardRegistry {
    private static final long CARD_TTL_MS = 15 * 60 * 1000L;
    private final Map<String, CardRecord> store = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryCardRegistry() {
        this(Clock.systemUTC());
    }

    /** 可控时钟用于过期/执行竞态测试；默认装配不依赖外部服务。 */
    public InMemoryCardRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void issue(String sessionId, InteractiveCard card) {
        requireText(sessionId, "sessionId");
        if (card == null) {
            throw new IllegalArgumentException("card 不能为空");
        }
        requireText(card.getActionId(), "actionId");
        requireText(card.getCardType(), "cardType");
        InteractiveCard snapshot = copyCard(card);
        long now = clock.millis();
        CardRecord record = CardRecord.builder().actionId(card.getActionId()).sessionId(sessionId)
                .cardType(card.getCardType()).title(card.getTitle()).snapshot(snapshot)
                .state(CardState.ISSUED).issueTime(now).expireAt(now + CARD_TTL_MS).build();
        if (store.putIfAbsent(record.getActionId(), record) != null) {
            throw new CardStateConflictException("actionId 已签发，禁止覆盖");
        }
    }

    @Override
    public CardRecord claim(String actionId, String sessionId) {
        requireText(sessionId, "sessionId");
        CardRecord record = requireRecord(actionId);
        synchronized (record) {
            if (!sessionId.equals(record.getSessionId())) {
                throw new CardSecurityException("越权操作拦截：卡片不属于当前会话");
            }
            if (record.getState() == CardState.EXPIRED) {
                throw new CardNotFoundException("卡片已过期");
            }
            // 执行中/终态优先判断，不因 TTL 将它们覆盖为 EXPIRED。
            if (record.getState() != CardState.ISSUED) {
                throw new CardStateConflictException("卡片不可再次提交，当前状态: " + record.getState());
            }
            if (clock.millis() >= record.getExpireAt()) {
                record.setState(CardState.EXPIRED);
                throw new CardNotFoundException("卡片已过期");
            }
            record.setState(CardState.SUBMITTING);
            record.setSubmitTime(clock.millis());
            return copyRecord(record);
        }
    }

    @Override
    public void validate(CardRecord record, Map<String, Object> submittedValues) {
        CardFormValues.validateAndMerge(record, submittedValues);
    }

    @Override
    public void complete(String actionId, CardSubmitResult result) {
        if (result == null || !result.isSuccess()) {
            throw new IllegalArgumentException("只有明确成功的结果可以归档为 SUBMITTED");
        }
        CardRecord record = requireRecord(actionId);
        synchronized (record) {
            requireSubmitting(record);
            record.setState(CardState.SUBMITTED);
            record.setTicketId(result.getTicketId());
        }
    }

    @Override
    public void fail(String actionId, String reason, boolean allowRetry) {
        CardRecord record = requireRecord(actionId);
        synchronized (record) {
            requireSubmitting(record);
            record.setFailReason(reason);
            record.setState(!allowRetry ? CardState.FAILED
                    : clock.millis() >= record.getExpireAt() ? CardState.EXPIRED : CardState.ISSUED);
        }
    }

    @Override
    public void markExecutionUnknown(String actionId, String reason) {
        CardRecord record = requireRecord(actionId);
        synchronized (record) {
            requireSubmitting(record);
            record.setFailReason(reason);
            record.setState(CardState.EXECUTION_UNKNOWN);
        }
    }

    @Override
    public Optional<CardRecord> getRecord(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return Optional.empty();
        }
        CardRecord record = store.get(actionId);
        if (record == null) {
            return Optional.empty();
        }
        synchronized (record) {
            return Optional.of(copyRecord(record));
        }
    }

    private CardRecord requireRecord(String actionId) {
        requireText(actionId, "actionId");
        CardRecord record = store.get(actionId);
        if (record == null) {
            throw new CardNotFoundException("卡片不存在或已过期");
        }
        return record;
    }

    private static void requireSubmitting(CardRecord record) {
        if (record.getState() != CardState.SUBMITTING) {
            throw new CardStateConflictException("仅执行中的提案允许结果归档");
        }
    }

    private static void requireText(String text, String name) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static CardRecord copyRecord(CardRecord record) {
        return CardRecord.builder().actionId(record.getActionId()).sessionId(record.getSessionId())
                .cardType(record.getCardType()).title(record.getTitle()).snapshot(copyCard(record.getSnapshot()))
                .state(record.getState()).issueTime(record.getIssueTime()).expireAt(record.getExpireAt())
                .submitTime(record.getSubmitTime()).ticketId(record.getTicketId())
                .failReason(record.getFailReason()).build();
    }

    private static InteractiveCard copyCard(InteractiveCard source) {
        if (source == null || source.getFields() == null) {
            throw new IllegalArgumentException("卡片必须提供字段定义，纯确认卡片可使用空列表");
        }
        InteractiveCard copy = new InteractiveCard();
        copy.setActionId(source.getActionId());
        copy.setCardType(source.getCardType());
        copy.setTitle(source.getTitle());
        copy.setDescription(source.getDescription());
        copy.setConfirmButtonText(source.getConfirmButtonText());
        copy.setCancelButtonText(source.getCancelButtonText());
        var keys = new HashSet<String>();
        var fields = new ArrayList<CardFormField>();
        for (CardFormField field : source.getFields()) {
            if (field == null || field.getFieldKey() == null || field.getFieldKey().isBlank()
                    || !keys.add(field.getFieldKey())) {
                throw new IllegalArgumentException("卡片字段为空或重复");
            }
            CardFormField f = new CardFormField(field.getFieldKey(), field.getLabel(), field.getType(),
                    immutableScalar(field.getValue()), field.isEditable(), field.isRequired());
            f.setMinLimit(immutableScalar(field.getMinLimit()));
            f.setMaxLimit(immutableScalar(field.getMaxLimit()));
            f.setPlaceholder(field.getPlaceholder());
            fields.add(f);
        }
        copy.setFields(fields);
        return copy;
    }

    private static Object immutableScalar(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof BigDecimal || value instanceof BigInteger
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Double || value instanceof Float) {
            return value;
        }
        throw new IllegalArgumentException("当前卡片仅支持不可变 JSON 标量，不支持对象或数组字段");
    }
}
