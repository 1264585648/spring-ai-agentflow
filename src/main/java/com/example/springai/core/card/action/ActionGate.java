package com.example.springai.core.card.action;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.CardExecutionUnknownException;
import com.example.springai.core.card.exception.CardNotFoundException;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.model.CardRecord;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 最小审批入口：可信快照、参数重建、授权、原子申领、受控执行及结果归档。 */
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
        if (request == null) {
            throw new IllegalArgumentException("提交请求不能为空");
        }
        String actionId = required(request.getActionId(), "actionId");
        String sessionId = required(request.getSessionId(), "sessionId");
        String cardType = required(request.getCardType(), "cardType");
        CardRecord proposal = registry.getRecord(actionId)
                .orElseThrow(() -> new CardNotFoundException("卡片不存在或已过期"));
        if (!sessionId.equals(proposal.getSessionId())) {
            throw new CardSecurityException("越权操作拦截：卡片不属于当前会话");
        }
        if (!cardType.equals(proposal.getCardType())) {
            throw new CardSecurityException("cardType 与服务端签发类型不一致");
        }
        Map<String, Object> values = CardFormValues.validateAndMerge(proposal, request.getFormValues());
        // 客户端类型只用于核对，绝不用于选择处理器。
        CardSubmitHandler handler = handlers.stream().filter(h -> h.supports(proposal.getCardType()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("不支持的卡片类型"));
        String actorId = principal == null ? null : principal.getName();
        if (handler.isSimulation()) {
            if (!allowMockSubmissions) {
                throw new CardSecurityException("当前环境未开放模拟卡片提交");
            }
            actorId = "mock-demo"; // 不冒充已认证身份，也不使用表单中的 operator。
        } else {
            if (!realWritesEnabled) {
                throw new CardSecurityException("真实写操作未启用");
            }
            requiredActor(actorId);
            authorizer.authorize(actorId, proposal, values);
        }
        ApprovedCardSubmission approved = new ApprovedCardSubmission(actionId, sessionId,
                cardType, actorId, values);
        handler.validate(approved); // 纯参数预检；失败不消耗提案，不得执行外部写操作。
        registry.claim(actionId, sessionId); // 到执行前才抢占；过期/重复/并发竞争由存储原子裁决。
        log.info("[ActionGate] 执行动作 actionId={}, cardType={}, actor={}", actionId, cardType, actorId);
        try {
            CardSubmitResult result = handler.handleSubmit(approved);
            if (result == null || !result.isSuccess()) {
                throw new IllegalStateException("执行器未返回确定的成功结果");
            }
            registry.complete(actionId, result);
            return new SubmissionResult(actionId, sessionId, cardType, result);
        } catch (RuntimeException ex) {
            // 已越过执行边界：异常/超时/失败响应都不证明外部未生效，绝不自动释放为 ISSUED。
            try {
                registry.markExecutionUnknown(actionId, "执行开始后未能确认成功，需人工核查");
            } catch (RuntimeException storageError) {
                ex.addSuppressed(storageError);
                log.error("[ActionGate] 结果归档失败，需核查 actionId={}", actionId, storageError);
            }
            log.error("[ActionGate] 执行结果未知 actionId={}", actionId, ex);
            throw new CardExecutionUnknownException(ex);
        }
    }

    @Data
    public static final class SubmissionResult {
        private final String actionId;
        private final String sessionId;
        private final String cardType;
        private final CardSubmitResult result;

        private SubmissionResult(String actionId, String sessionId, String cardType, CardSubmitResult result) {
            this.actionId = actionId;
            this.sessionId = sessionId;
            this.cardType = cardType;
            this.result = result;
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static void requiredActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new CardSecurityException("缺少服务端认证身份，拒绝真实写操作");
        }
    }
}
