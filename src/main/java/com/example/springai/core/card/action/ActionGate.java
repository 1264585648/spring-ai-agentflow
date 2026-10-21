package com.example.springai.core.card.action;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.*;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.state.CardState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 审批与写执行唯一的卡片入口。未知结果不是失败回滚，不重新开放提案。 */
@Slf4j
public final class ActionGate {
    private final CardRegistry registry;
    private final List<CardSubmitHandler> handlers;
    private final ActionAuthorizer authorizer;
    private final boolean allowMockSubmissions;
    private final boolean realWritesEnabled;
    public ActionGate(CardRegistry registry, List<CardSubmitHandler> handlers, ActionAuthorizer authorizer,
                      boolean allowMockSubmissions, boolean realWritesEnabled) {
        this.registry = Objects.requireNonNull(registry);
        this.handlers = List.copyOf(handlers);
        this.authorizer = Objects.requireNonNull(authorizer);
        this.allowMockSubmissions = allowMockSubmissions;
        this.realWritesEnabled = realWritesEnabled;
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
        if (proposal.getState() != CardState.ISSUED) {
            throw new CardStateConflictException("卡片不可再次提交，当前状态: " + proposal.getState());
        }
        Map<String, Object> values = CardFormValues.validateAndMerge(proposal, request.getFormValues());
        CardSubmitHandler handler = handlers.stream().filter(h -> h.supports(cardType)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的卡片类型"));
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
        ApprovedCardSubmission approved = new ApprovedCardSubmission(actionId, sessionId, cardType, actorId, values);
        handler.validate(approved);
        CardRecord claimed = registry.claim(proposal);
        try {
            CardSubmitResult result = handler.handleSubmit(approved);
            if (result == null || !result.isSuccess() || result.getTicketId() == null || result.getTicketId().isBlank()) {
                throw new IllegalStateException("执行器未返回完整的确定成功回执");
            }
            registry.complete(actionId, result);
            return new SubmissionResult(actionId, sessionId, claimed.getTurnId(), cardType, result);
        } catch (RuntimeException ex) {
            try { registry.markExecutionUnknown(actionId, "执行开始后未确认成功，需核查外部系统"); }
            catch (RuntimeException storageError) { ex.addSuppressed(storageError); }
            log.error("[ActionGate] 执行结果未知 actionId={}", actionId, ex);
            throw new CardExecutionUnknownException(ex);
        }
    }
    public CancellationResult cancel(com.example.springai.core.card.dto.CardCancelRequest request, Principal principal) {
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
    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException(field + " 不能为空或超长");
        return value;
    }
}
