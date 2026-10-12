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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用交互卡片服务端状态机内存实现 (InMemoryCardRegistry)
 * 核心特性:
 * 1. 严格快照隔离: 下发时深拷贝 InteractiveCard，杜绝引用传递篡改;
 * 2. 线程安全原子状态流转: 基于 synchronized 细粒度同步块保障同一 actionId 的状态跃迁强一致性;
 * 3. 严格安全三防线: 防 actionId 伪造、防跨 session 越权、只读字段防篡改比对;
 * 4. 自动超时淘汰: 支持 TTL 过期感知与过期卡片自动拦截。
 */
@Component
@Slf4j
public class InMemoryCardRegistry implements CardRegistry {

    private static final long DEFAULT_CARD_TTL_MS = 15 * 60 * 1000L; // 15分钟有效期

    private final Map<String, CardRecord> store = new ConcurrentHashMap<>();

    @Override
    public void issue(String sessionId, InteractiveCard card) {
        if (card == null || !StringUtils.hasText(card.getActionId())) {
            log.warn("[CardRegistry] 下发卡片缺失 actionId，跳过服务端快照登记");
            return;
        }

        String actionId = card.getActionId();
        InteractiveCard deepCopy = copyCardSnapshot(card);

        long now = System.currentTimeMillis();
        CardRecord record = CardRecord.builder()
                .actionId(actionId)
                .sessionId(sessionId)
                .cardType(card.getCardType())
                .title(card.getTitle())
                .snapshot(deepCopy)
                .state(CardState.ISSUED)
                .issueTime(now)
                .expireAt(now + DEFAULT_CARD_TTL_MS)
                .build();

        store.put(actionId, record);
        log.info("[CardRegistry] 📋 交互卡片快照成功签发登记, actionId: {}, sessionId: {}, cardType: {}, 字段数: {}",
                actionId, sessionId, card.getCardType(), deepCopy.getFields().size());
    }

    @Override
    public CardRecord claim(String actionId, String sessionId) {
        if (!StringUtils.hasText(actionId)) {
            throw new IllegalArgumentException("actionId 不能为空");
        }

        CardRecord record = store.get(actionId);
        if (record == null) {
            log.warn("[CardRegistry] ⚠️ 拦截非法提交: 未在服务端找到 actionId 登记记录: {}", actionId);
            throw new CardNotFoundException("非法或不存在的卡片 actionId: " + actionId);
        }

        if (record.isExpired()) {
            record.setState(CardState.EXPIRED);
            log.warn("[CardRegistry] ⚠️ 拦截过期提交: actionId: {}, 签发时间: {}", actionId, new Date(record.getIssueTime()));
            throw new CardNotFoundException("卡片已超过有效期 (15分钟) 失效，请重新发起诊断");
        }

        // 防线 1: 防跨会话越权
        if (StringUtils.hasText(sessionId) && StringUtils.hasText(record.getSessionId())) {
            if (!record.getSessionId().equals(sessionId)) {
                log.error("[CardRegistry] 🚨 检测到跨会话越权操作！actionId: {}, 归属会话: {}, 提交会话: {}",
                        actionId, record.getSessionId(), sessionId);
                throw new CardSecurityException("越权操作拦截：该卡片不属于当前会话 (" + sessionId + ")");
            }
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
            if (state == CardState.EXPIRED) {
                throw new CardNotFoundException("卡片已超时失效");
            }
            if (state != CardState.ISSUED) {
                throw new CardStateConflictException("卡片处于不可提交状态: " + state);
            }

            // 原子跃迁至 SUBMITTING
            record.setState(CardState.SUBMITTING);
            record.setSubmitTime(System.currentTimeMillis());
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
            Object submittedVal = (submittedValues != null) ? submittedValues.get(key) : null;

            String originalStr = originalVal != null ? String.valueOf(originalVal).trim() : "";
            String submittedStr = submittedVal != null ? String.valueOf(submittedVal).trim() : "";

            // 防线 3: 核心只读防篡改核验
            if (!field.isEditable()) {
                if (!Objects.equals(originalStr, submittedStr)) {
                    log.error("[CardRegistry] 🚨 检测到只读字段恶意篡改！actionId: {}, 字段: {}, 原始值: [{}], 提交值: [{}]",
                            record.getActionId(), key, originalStr, submittedStr);
                    throw new CardSecurityException("安全警告：只读参数 [" + field.getLabel() + " (" + key + ")] 受系统锁定保护，严禁篡改！(原始值: " + originalStr + ", 提交值: " + submittedStr + ")");
                }
            }

            // 必填项核验
            if (field.isRequired()) {
                if (!StringUtils.hasText(submittedStr)) {
                    throw new IllegalArgumentException("必填字段 [" + field.getLabel() + "] 不能为空");
                }
            }
        }
    }

    @Override
    public void complete(String actionId, CardSubmitResult result) {
        CardRecord record = store.get(actionId);
        if (record != null) {
            synchronized (record) {
                record.setState(CardState.SUBMITTED);
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
     * 深拷贝 InteractiveCard 快照
     */
    private InteractiveCard copyCardSnapshot(InteractiveCard original) {
        InteractiveCard copy = new InteractiveCard();
        copy.setActionId(original.getActionId());
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
