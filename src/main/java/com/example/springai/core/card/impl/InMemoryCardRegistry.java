package com.example.springai.core.card.impl;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.CardNotFoundException;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.state.CardState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用交互卡片服务端状态机内存实现 (InMemoryCardRegistry)
 * 核心特性:
 * 1. 严格快照隔离: 下发时深拷贝 InteractiveCard，杜绝引用传递篡改;
 * 2. 线程安全原子状态流转: 基于 synchronized 细粒度同步块保障同一 actionId 的状态跃迁强一致性;
 * 3. 严格安全防线: 防 actionId 伪造、强制非空 sessionId 归属核验、只读字段防篡改比对、数值范围校验、白名单参数重建;
 * 4. 生命周期与容量治理: 支持可控时钟、过期/终态卡片淘汰与最大容量保护（保护执行中 SUBMITTING 与近期幂等记录）。
 */
@Component
@Slf4j
public class InMemoryCardRegistry implements CardRegistry {

    public static final long DEFAULT_CARD_TTL_MS = 15 * 60 * 1000L; // 15分钟有效期
    public static final long DEFAULT_TERMINAL_RETENTION_MS = 60 * 60 * 1000L; // 终态记录保留60分钟供幂等拦截
    public static final int DEFAULT_MAX_CAPACITY = 10000;

    private final Map<String, CardRecord> store = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxCapacity;
    private final long cardTtlMs;
    private final long terminalRetentionMs;

    @Autowired
    public InMemoryCardRegistry() {
        this(Clock.systemUTC(), DEFAULT_MAX_CAPACITY, DEFAULT_CARD_TTL_MS, DEFAULT_TERMINAL_RETENTION_MS);
    }

    public InMemoryCardRegistry(Clock clock, int maxCapacity, long cardTtlMs, long terminalRetentionMs) {
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.maxCapacity = maxCapacity > 0 ? maxCapacity : DEFAULT_MAX_CAPACITY;
        this.cardTtlMs = cardTtlMs > 0 ? cardTtlMs : DEFAULT_CARD_TTL_MS;
        this.terminalRetentionMs = terminalRetentionMs > 0 ? terminalRetentionMs : DEFAULT_TERMINAL_RETENTION_MS;
    }

    @Override
    public void issue(String sessionId, InteractiveCard card) {
        if (card == null || !StringUtils.hasText(card.getActionId())) {
            log.warn("[CardRegistry] 下发卡片缺失 actionId，跳过服务端快照登记");
            return;
        }

        long now = clock.millis();
        evictExpired(now);

        String actionId = card.getActionId();
        InteractiveCard deepCopy = copyCardSnapshot(card);

        CardRecord record = CardRecord.builder()
                .actionId(actionId)
                .sessionId(sessionId)
                .turnId(card.getTurnId())
                .cardType(card.getCardType())
                .title(card.getTitle())
                .snapshot(deepCopy)
                .state(CardState.ISSUED)
                .issueTime(now)
                .expireAt(now + cardTtlMs)
                .build();

        store.put(actionId, record);
        log.info("[CardRegistry] 📋 交互卡片快照成功签发登记, actionId: {}, sessionId: {}, turnId: {}, cardType: {}, 字段数: {}",
                actionId, sessionId, card.getTurnId(), card.getCardType(), deepCopy.getFields().size());
    }

    @Override
    public CardRecord claim(String actionId, String sessionId) {
        if (!StringUtils.hasText(actionId)) {
            throw new IllegalArgumentException("actionId 不能为空");
        }
        if (!StringUtils.hasText(sessionId)) {
            log.error("[CardRegistry] 🚨 检测到缺失会话标识的卡片提交！actionId: {}", actionId);
            throw new CardSecurityException("越权操作拦截：缺少有效的会话标识 (sessionId)");
        }

        long now = clock.millis();
        CardRecord record = store.get(actionId);
        if (record == null) {
            log.warn("[CardRegistry] ⚠️ 拦截非法提交: 未在服务端找到 actionId 登记记录: {}", actionId);
            throw new CardNotFoundException("非法或不存在的卡片 actionId: " + actionId);
        }

        // 防线 1: 强制核验会话归属，不允许空会话绕过
        if (StringUtils.hasText(record.getSessionId()) && !record.getSessionId().equals(sessionId.trim())) {
            log.error("[CardRegistry] 🚨 检测到跨会话越权操作！actionId: {}, 归属会话: {}, 提交会话: {}",
                    actionId, record.getSessionId(), sessionId);
            throw new CardSecurityException("越权操作拦截：该卡片不属于当前会话 (" + sessionId + ")");
        }

        // 防线 2: 状态机抢锁流转 (ISSUED -> SUBMITTING)
        synchronized (record) {
            CardState state = record.getState();
            if (state == CardState.SUBMITTED) {
                log.warn("[CardRegistry] 重复提交已被拦截: actionId: {}, 已关联工单: {}", actionId, record.getTicketId());
                throw new CardStateConflictException("该卡片已成功提交处理，请勿重复操作 (工单号: " + record.getTicketId() + ")");
            }
            if (state == CardState.SUBMITTING) {
                log.warn("[CardRegistry] 正在处理中的连击已被拦截: actionId: {}", actionId);
                throw new CardStateConflictException("该卡片正在提交处理中，请勿重复点击");
            }
            if (state == CardState.CANCELLED) {
                throw new CardStateConflictException("该卡片已被放弃取消");
            }
            if (state == CardState.EXPIRED || record.isExpired(now)) {
                record.setState(CardState.EXPIRED);
                log.warn("[CardRegistry] ⚠️ 拦截过期提交: actionId: {}, 签发时间: {}", actionId, new Date(record.getIssueTime()));
                throw new CardNotFoundException("卡片已超过有效期 (15分钟) 失效，请重新发起诊断");
            }
            if (state != CardState.ISSUED) {
                throw new CardStateConflictException("卡片处于不可提交状态: " + state);
            }

            // 原子跃迁至 SUBMITTING
            record.setState(CardState.SUBMITTING);
            record.setSubmitTime(now);
            log.info("[CardRegistry] 🔒 成功抢占提交锁, actionId: {}, 状态跃迁: ISSUED ➔ SUBMITTING", actionId);
            return record;
        }
    }

    @Override
    public void validate(CardRecord record, Map<String, Object> submittedValues) {
        if (record == null || record.getSnapshot() == null) {
            return;
        }

        InteractiveCard snapshot = record.getSnapshot();
        List<CardFormField> fields = snapshot.getFields();
        if (fields == null || fields.isEmpty()) {
            return;
        }

        for (CardFormField field : fields) {
            String key = field.getFieldKey();
            Object originalVal = field.getValue();
            boolean hasSubmittedKey = submittedValues != null && submittedValues.containsKey(key);
            Object submittedVal = hasSubmittedKey ? submittedValues.get(key) : null;

            String originalStr = originalVal != null ? String.valueOf(originalVal).trim() : "";
            String submittedStr = submittedVal != null ? String.valueOf(submittedVal).trim() : "";

            // 防线 3: 核心只读防篡改核验（若客户端显式传参则必须与快照完全一致；若未传则由 reconstructSafeFormValues 恢复快照值）
            if (!field.isEditable()) {
                if (hasSubmittedKey && !Objects.equals(originalStr, submittedStr)) {
                    log.error("[CardRegistry] 🚨 检测到只读字段恶意篡改！actionId: {}, 字段: {}, 原始值: [{}], 提交值: [{}]",
                            record.getActionId(), key, originalStr, submittedStr);
                    throw new CardSecurityException("安全警告：只读参数 [" + field.getLabel() + " (" + key + ")] 受系统锁定保护，严禁篡改！(原始值: " + originalStr + ", 提交值: " + submittedStr + ")");
                }
                continue;
            }

            // 可编辑字段：若客户端提交了该字段则校验提交值，否则校验默认快照值
            String effectiveStr = hasSubmittedKey ? submittedStr : originalStr;

            // 必填项核验
            if (field.isRequired() && !StringUtils.hasText(effectiveStr)) {
                throw new IllegalArgumentException("必填字段 [" + field.getLabel() + "] 不能为空");
            }

            // 数值类型与范围边界核验
            boolean isNumberField = "number".equalsIgnoreCase(field.getType())
                    || field.getMinLimit() != null
                    || field.getMaxLimit() != null;
            if (isNumberField && StringUtils.hasText(effectiveStr)) {
                double numValue;
                try {
                    numValue = Double.parseDouble(effectiveStr);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("字段 [" + field.getLabel() + " (" + key + ")] 必须为合法数值: " + effectiveStr);
                }
                if (field.getMinLimit() != null) {
                    double minVal = Double.parseDouble(String.valueOf(field.getMinLimit()));
                    if (numValue < minVal) {
                        throw new IllegalArgumentException("字段 [" + field.getLabel() + " (" + key + ")] 超出最小范围限制: " + field.getMinLimit());
                    }
                }
                if (field.getMaxLimit() != null) {
                    double maxVal = Double.parseDouble(String.valueOf(field.getMaxLimit()));
                    if (numValue > maxVal) {
                        throw new IllegalArgumentException("字段 [" + field.getLabel() + " (" + key + ")] 超出最大范围限制: " + field.getMaxLimit());
                    }
                }
            }
        }
    }

    @Override
    public Map<String, Object> reconstructSafeFormValues(CardRecord record, Map<String, Object> submittedValues) {
        if (record == null || record.getSnapshot() == null || record.getSnapshot().getFields() == null) {
            return Map.of();
        }
        Map<String, Object> safeValues = new LinkedHashMap<>();
        for (CardFormField field : record.getSnapshot().getFields()) {
            String key = field.getFieldKey();
            if (!StringUtils.hasText(key)) {
                continue;
            }
            if (!field.isEditable()) {
                // 只读字段一律使用服务端签发快照中的不可变值
                safeValues.put(key, field.getValue());
            } else {
                // 白名单内的可编辑字段：仅当客户端提供非空值时合并，否则保留快照默认值
                if (submittedValues != null && submittedValues.containsKey(key) && submittedValues.get(key) != null) {
                    safeValues.put(key, submittedValues.get(key));
                } else {
                    safeValues.put(key, field.getValue());
                }
            }
        }
        return Collections.unmodifiableMap(safeValues);
    }

    @Override
    public void complete(String actionId, CardSubmitResult result) {
        CardRecord record = store.get(actionId);
        if (record != null) {
            synchronized (record) {
                record.setState(CardState.SUBMITTED);
                record.setSubmitTime(clock.millis());
                record.setTicketId(result != null ? result.getTicketId() : null);
                log.info("[CardRegistry] ✅ 卡片已成功流转至终态 SUBMITTED, actionId: {}, 关联单号: {}",
                        actionId, record.getTicketId());
            }
        }
    }

    @Override
    public void fail(String actionId, String failReason, boolean allowRetry) {
        CardRecord record = store.get(actionId);
        if (record != null) {
            synchronized (record) {
                record.setFailReason(failReason);
                if (allowRetry) {
                    record.setState(CardState.ISSUED); // 释放锁，允许修改重试
                    log.warn("[CardRegistry] 🔄 卡片处理异常，已释放回 ISSUED 状态允许重试: actionId: {}, 原因: {}",
                            actionId, failReason);
                } else {
                    record.setState(CardState.FAILED);
                    record.setSubmitTime(clock.millis());
                    log.warn("[CardRegistry] ❌ 卡片处理失败且不允许重试，流转为 FAILED: actionId: {}, 原因: {}",
                            actionId, failReason);
                }
            }
        }
    }

    @Override
    public Optional<CardRecord> getRecord(String actionId) {
        if (!StringUtils.hasText(actionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(actionId));
    }

    /**
     * 当前内存中的卡片记录数（用于监控与容量测试）
     */
    public int size() {
        return store.size();
    }

    /**
     * 触发过期与超量卡片清理（绝不清理处于 SUBMITTING 执行中的记录）
     */
    public void evictExpired() {
        evictExpired(clock.millis());
    }

    public void evictExpired(long now) {
        Iterator<Map.Entry<String, CardRecord>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CardRecord> entry = it.next();
            CardRecord r = entry.getValue();
            if (r == null) {
                it.remove();
                continue;
            }
            CardState st = r.getState();
            if (st == CardState.SUBMITTING) {
                // 保护执行中记录
                continue;
            }
            if (st == CardState.EXPIRED || st == CardState.CANCELLED || st == CardState.FAILED) {
                long refTime = r.getSubmitTime() > 0 ? r.getSubmitTime() : r.getExpireAt();
                if (now >= refTime) {
                    it.remove();
                }
            } else if (st == CardState.ISSUED && r.isExpired(now)) {
                it.remove();
            } else if (st == CardState.SUBMITTED) {
                long completedAt = r.getSubmitTime() > 0 ? r.getSubmitTime() : r.getIssueTime();
                if (now - completedAt > terminalRetentionMs) {
                    it.remove();
                }
            }
        }

        // 容量硬上限保护：按签发时间淘汰最旧的非 SUBMITTING 记录
        if (store.size() >= maxCapacity) {
            List<CardRecord> candidates = new ArrayList<>();
            for (CardRecord r : store.values()) {
                if (r != null && r.getState() != CardState.SUBMITTING) {
                    candidates.add(r);
                }
            }
            candidates.sort(Comparator
                    .comparingInt((CardRecord r) -> r.getState() == CardState.SUBMITTED ? 1 : 0)
                    .thenComparingLong(CardRecord::getIssueTime));
            int toRemove = (store.size() - maxCapacity) + 1;
            for (int i = 0; i < candidates.size() && i < toRemove; i++) {
                store.remove(candidates.get(i).getActionId());
            }
        }
    }

    /**
     * 深拷贝 InteractiveCard 快照
     */
    private InteractiveCard copyCardSnapshot(InteractiveCard original) {
        InteractiveCard copy = new InteractiveCard();
        copy.setActionId(original.getActionId());
        copy.setTurnId(original.getTurnId());
        copy.setCardType(original.getCardType());
        copy.setTitle(original.getTitle());
        copy.setDescription(original.getDescription());
        copy.setConfirmButtonText(original.getConfirmButtonText());
        copy.setCancelButtonText(original.getCancelButtonText());

        List<CardFormField> fieldsCopy = new ArrayList<>();
        if (original.getFields() != null) {
            for (CardFormField f : original.getFields()) {
                CardFormField fieldCopy = new CardFormField(
                        f.getFieldKey(),
                        f.getLabel(),
                        f.getType(),
                        f.getValue(),
                        f.isEditable(),
                        f.isRequired()
                );
                fieldCopy.setMinLimit(f.getMinLimit());
                fieldCopy.setMaxLimit(f.getMaxLimit());
                fieldCopy.setPlaceholder(f.getPlaceholder());
                fieldsCopy.add(fieldCopy);
            }
        }
        copy.setFields(fieldsCopy);
        return copy;
    }
}
