package com.example.springai.core.card.action;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.dto.CardCancelRequest;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.*;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.state.CardState;
import com.example.springai.core.context.TurnCheckpointStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 审批与写执行唯一的卡片入口。未知结果不是失败回滚，不重新开放提案。 */
@Slf4j
public final class ActionGate {
    private final CardRegistry registry;
    private final List<CardSubmitHandler> handlers;
    private final ActionAuthorizer authorizer;
    private final boolean allowMockSubmissions;
    private final boolean realWritesEnabled;
    private final TurnCheckpointStore checkpointStore;

    public ActionGate(CardRegistry registry, List<CardSubmitHandler> handlers, ActionAuthorizer authorizer,
                      boolean allowMockSubmissions, boolean realWritesEnabled) {
        this(registry, handlers, authorizer, allowMockSubmissions, realWritesEnabled, null);
    }

    public ActionGate(CardRegistry registry, List<CardSubmitHandler> handlers, ActionAuthorizer authorizer,
                      boolean allowMockSubmissions, boolean realWritesEnabled,
                      TurnCheckpointStore checkpointStore) {
        this.registry = Objects.requireNonNull(registry);
        this.handlers = List.copyOf(handlers);
        this.authorizer = Objects.requireNonNull(authorizer);
        this.allowMockSubmissions = allowMockSubmissions;
        this.realWritesEnabled = realWritesEnabled;
        this.checkpointStore = checkpointStore;
    }

    public SubmissionResult submit(CardSubmitRequest request, Principal principal) {
        if (request == null) throw new IllegalArgumentException("提交请求不能为空");
        String actionId = required(request.getActionId(), "actionId");
        String sessionId = required(request.getSessionId(), "sessionId");
        String cardType = required(request.getCardType(), "cardType");
        CardRecord proposal = registry.getRecord(actionId).orElseThrow(() -> new CardNotFoundException("卡片不存在或已过期"));
        if (!sessionId.equals(proposal.getSessionId()) || !cardType.equals(proposal.getCardType())) {
            throw new CardSecurityException("会话或 cardType 与服务端签发记录不一致");
        }
        if (proposal.getState() == CardState.EXPIRED || registry.isExpired(proposal)) {
            throw new CardNotFoundException("卡片已过期");
        }
        if (proposal.getState() != CardState.ISSUED) {
            throw new CardStateConflictException("卡片不可再次提交，当前状态: " + proposal.getState());
        }
        Map<String, Object> values = CardFormValues.validateAndMerge(proposal, request.getFormValues());
        CardSubmitHandler handler = handlers.stream().filter(h -> h.supports(cardType)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的卡片类型"));

        // 校验工具/处理器版本一致性：等待确认期间若工具版本变化，拒绝隐式切换实现
        String expectedVersion = proposal.getEffectiveToolVersion();
        String actualVersion = normalizeVersion(handler.toolVersion());
        if (!expectedVersion.equals(actualVersion)) {
            throw new CardStateConflictException("等待审批期间工具版本已变化（提案版本=" + expectedVersion
                    + ", 当前版本=" + actualVersion + "），拒绝隐式切换实现，请重新发起审批或取消");
        }
        if (checkpointStore != null) {
            checkpointStore.verifyCanExecute(actionId, sessionId, proposal.getIssuanceId(), actualVersion);
        }

        String actorId;
        if (handler.isSimulation()) {
            if (!allowMockSubmissions) throw new CardSecurityException("当前环境未开放模拟提交");
            actorId = "mock-demo";
        } else {
            if (!realWritesEnabled) throw new CardSecurityException("真实写操作未启用");
            if (proposal.getSnapshot().isSimulation()) throw new CardSecurityException("模拟证据不能批准真实写操作");
            actorId = principal == null ? null : principal.getName();
            if (actorId == null || actorId.isBlank()) throw new CardSecurityException("缺少服务端认证身份");
            authorizer.authorize(actorId, proposal, values);
        }
        ApprovedCardSubmission approved = new ApprovedCardSubmission(
                actionId, sessionId, cardType, actorId, values,
                proposal.getEffectiveIdempotencyKey(), actualVersion);
        handler.validate(approved);
        CardRecord claimed = registry.claim(proposal, actorId, values);
        try {
            CardSubmitResult result = handler.handleSubmit(approved);
            if (result == null || !result.isSuccess() || result.getTicketId() == null || result.getTicketId().isBlank()) {
                throw new IllegalStateException("执行器未返回完整的确定成功回执");
            }
            registry.complete(actionId, result);
            if (checkpointStore != null) {
                checkpointStore.resumeOnApprovedExecution(actionId, sessionId, claimed.getIssuanceId(),
                        actualVersion, result.getTicketId(), result.getMessage());
            }
            return new SubmissionResult(actionId, sessionId, claimed.getTurnId(), cardType, result);
        } catch (RuntimeException ex) {
            try { registry.markExecutionUnknown(actionId, "执行开始后未确认成功，需核查外部系统"); }
            catch (RuntimeException storageError) { ex.addSuppressed(storageError); }
            log.error("[ActionGate] 执行结果未知 actionId={}", actionId, ex);
            throw new CardExecutionUnknownException(ex);
        }
    }

    /**
     * 对账恢复入口（对应 12 号架构文档验收场景 2）：
     * 当外部写成功但结果落库前崩溃（处于 SUBMITTING 或 EXECUTION_UNKNOWN）时，
     * 通过幂等键查询外部系统已执行结果，识别已有结果并恢复回合，或保持 EXECUTION_UNKNOWN 进入人工核查，绝不盲目重做。
     */
    public SubmissionResult reconcile(String actionId, String sessionId) {
        String validActionId = required(actionId, "actionId");
        String validSessionId = required(sessionId, "sessionId");
        CardRecord record = registry.getRecord(validActionId)
                .orElseThrow(() -> new CardNotFoundException("卡片不存在或已过期"));
        if (!validSessionId.equals(record.getSessionId())) {
            throw new CardSecurityException("会话与服务端签发记录不一致");
        }
        if (record.getState() == CardState.SUBMITTED) {
            CardSubmitResult doneResult = CardSubmitResult.ok(record.getTicketId(), "动作已归档完成");
            return new SubmissionResult(validActionId, validSessionId, record.getTurnId(), record.getCardType(), doneResult);
        }
        if (record.getState() != CardState.SUBMITTING && record.getState() != CardState.EXECUTION_UNKNOWN) {
            throw new CardStateConflictException("当前状态不支持对账恢复: " + record.getState());
        }
        CardSubmitHandler handler = handlers.stream().filter(h -> h.supports(record.getCardType())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的卡片类型"));
        Optional<CardSubmitResult> reconciled = handler.reconcile(record);
        if (reconciled.isPresent() && reconciled.get().isSuccess()
                && reconciled.get().getTicketId() != null && !reconciled.get().getTicketId().isBlank()) {
            CardSubmitResult recovered = reconciled.get();
            CardRecord updated = registry.reconcile(validActionId, recovered);
            if (checkpointStore != null) {
                checkpointStore.resumeOnApprovedExecution(validActionId, validSessionId, updated.getIssuanceId(),
                        updated.getEffectiveToolVersion(), recovered.getTicketId(), recovered.getMessage());
            }
            log.info("[ActionGate] 对账恢复成功，识别已有外部结果 actionId={}, ticketId={}", validActionId, recovered.getTicketId());
            return new SubmissionResult(validActionId, validSessionId, updated.getTurnId(), updated.getCardType(), recovered);
        }
        if (record.getState() == CardState.SUBMITTING) {
            registry.markExecutionUnknown(validActionId, "进程恢复后对账未发现确定成功回执，转人工核查");
        }
        throw new CardExecutionUnknownException(new IllegalStateException("外部系统未返回确定执行回执，保持 EXECUTION_UNKNOWN 待人工核查"));
    }

    public CancellationResult cancel(CardCancelRequest request, Principal principal) {
        if (request == null) throw new IllegalArgumentException("取消请求不能为空");
        String actionId = required(request.getActionId(), "actionId");
        String sessionId = required(request.getSessionId(), "sessionId");
        CardRecord proposal = registry.getRecord(actionId).orElseThrow(() -> new CardNotFoundException("卡片不存在或已过期"));
        if (!sessionId.equals(proposal.getSessionId())) {
            throw new CardSecurityException("会话与服务端签发记录不一致");
        }
        if (proposal.getState() != CardState.ISSUED) {
            throw new CardStateConflictException("卡片不可放弃，当前状态: " + proposal.getState());
        }
        CardRecord cancelled = registry.cancel(proposal, request.getReason());
        if (checkpointStore != null) {
            checkpointStore.cancelByActionId(actionId, sessionId, request.getReason());
        }
        return new CancellationResult(actionId, sessionId, cancelled.getTurnId(), cancelled.getCardType(), cancelled.getState().name());
    }

    @Data
    public static final class SubmissionResult {
        private final String actionId;
        private final String sessionId;
        private final String turnId;
        private final String cardType;
        private final CardSubmitResult result;
        private SubmissionResult(String actionId, String sessionId, String turnId, String cardType, CardSubmitResult result) {
            this.actionId = actionId;
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.cardType = cardType;
            this.result = result;
        }
    }

    @Data
    public static final class CancellationResult {
        private final String actionId;
        private final String sessionId;
        private final String turnId;
        private final String cardType;
        private final String state;
        public CancellationResult(String actionId, String sessionId, String turnId, String cardType, String state) {
            this.actionId = actionId;
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.cardType = cardType;
            this.state = state;
        }
    }

    private static String normalizeVersion(String version) {
        return (version == null || version.isBlank()) ? "v1" : version.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException(field + " 不能为空或超长");
        return value;
    }
}
