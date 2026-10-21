package com.example.springai.core.card.action;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.dto.CardCancelRequest;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.*;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.state.ApprovalState;
import com.example.springai.core.card.state.CardState;
import com.example.springai.core.card.state.ExecutionState;
import com.example.springai.core.context.TurnCheckpointStore;
import com.example.springai.core.tool.InvocationContext;
import com.example.springai.core.tool.InvocationSource;
import com.example.springai.core.tool.RevocableInvocationPolicy;
import com.example.springai.core.tool.SideEffectLevel;
import com.example.springai.core.tool.UnifiedInvocationGateway;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 审批与写执行唯一的卡片关口（ActionGate）。
 * <p>
 * 遵循 12 号架构设计：
 * 1. 支持一键审批+执行（{@link #submit}）与分步先审批（{@link #approve}）、后执行（{@link #executeApproved}）；
 * 2. 所有写操作最终统一经由 {@link UnifiedInvocationGateway} 执行，并在进入底层执行器前的最后一刻实时复核权限；
 * 3. 若审批通过后、真正执行前权限被实时撤销（原型验收场景 5），在统一调用入口立即拦截并标记为权限拒绝（REJECTED + FAILED），
 *    绝不触发外部写操作，也不误标为 EXECUTION_UNKNOWN；
 * 4. 一旦进入底层执行器后发生异常，标记为 EXECUTION_UNKNOWN 走幂等对账（{@link #reconcile}），绝不盲目重试。
 */
@Slf4j
public final class ActionGate {
    private final CardRegistry registry;
    private final List<CardSubmitHandler> handlers;
    private final ActionAuthorizer authorizer;
    private final boolean allowMockSubmissions;
    private final boolean realWritesEnabled;
    private final TurnCheckpointStore checkpointStore;
    private final UnifiedInvocationGateway invocationGateway;

    public ActionGate(CardRegistry registry, List<CardSubmitHandler> handlers, ActionAuthorizer authorizer,
                      boolean allowMockSubmissions, boolean realWritesEnabled) {
        this(registry, handlers, authorizer, allowMockSubmissions, realWritesEnabled, null, null);
    }

    public ActionGate(CardRegistry registry, List<CardSubmitHandler> handlers, ActionAuthorizer authorizer,
                      boolean allowMockSubmissions, boolean realWritesEnabled,
                      TurnCheckpointStore checkpointStore) {
        this(registry, handlers, authorizer, allowMockSubmissions, realWritesEnabled, checkpointStore, null);
    }

    public ActionGate(CardRegistry registry, List<CardSubmitHandler> handlers, ActionAuthorizer authorizer,
                      boolean allowMockSubmissions, boolean realWritesEnabled,
                      TurnCheckpointStore checkpointStore,
                      UnifiedInvocationGateway invocationGateway) {
        this.registry = Objects.requireNonNull(registry);
        this.handlers = List.copyOf(handlers);
        this.authorizer = Objects.requireNonNull(authorizer);
        this.allowMockSubmissions = allowMockSubmissions;
        this.realWritesEnabled = realWritesEnabled;
        this.checkpointStore = checkpointStore;
        if (invocationGateway != null) {
            this.invocationGateway = invocationGateway;
        } else if (authorizer instanceof com.example.springai.core.tool.InvocationAuthorizer invAuth) {
            this.invocationGateway = new UnifiedInvocationGateway(invAuth);
        } else {
            this.invocationGateway = new UnifiedInvocationGateway();
        }
    }

    public UnifiedInvocationGateway getInvocationGateway() {
        return invocationGateway;
    }

    /**
     * 分步模式第一步：仅审批提案（状态流转为 ApprovalState.APPROVED + ExecutionState.NOT_STARTED），暂不触发外部执行。
     */
    public ApprovalResult approve(CardSubmitRequest request, Principal principal) {
        if (request == null) throw new IllegalArgumentException("审批请求不能为空");
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
        if (proposal.getState() != CardState.ISSUED || proposal.getApprovalState() != ApprovalState.PENDING) {
            throw new CardStateConflictException("卡片不可再次审批，当前审批态: " + proposal.getApprovalState());
        }

        Map<String, Object> values = CardFormValues.validateAndMerge(proposal, request.getFormValues());
        CardSubmitHandler handler = resolveHandler(cardType);
        String actualVersion = verifyToolVersionAndCheckpoint(proposal, handler, actionId, sessionId);
        String actorId = resolveAndAuthorizeAtApproval(proposal, handler, values, principal);

        ApprovedCardSubmission approvedPreview = new ApprovedCardSubmission(
                actionId, sessionId, cardType, actorId, values,
                proposal.getEffectiveIdempotencyKey(), actualVersion);
        handler.validate(approvedPreview);

        CardRecord approvedRecord = registry.approve(proposal, actorId, values);
        return new ApprovalResult(
                actionId,
                sessionId,
                approvedRecord.getTurnId(),
                cardType,
                actorId,
                ApprovalState.APPROVED.name(),
                ExecutionState.NOT_STARTED.name()
        );
    }

    /**
     * 分步模式第二步：执行已审批通过（ApprovalState.APPROVED）的提案。
     * 在真正调用底层执行器前的最后一刻，通过 {@link UnifiedInvocationGateway} 按实时策略重查权限：
     * 若审批后权限被撤销，立即拦截并标记为 REJECTED + FAILED，绝不触发外部写操作。
     */
    public SubmissionResult executeApproved(String actionId, String sessionId, Principal principal) {
        String validActionId = required(actionId, "actionId");
        String validSessionId = required(sessionId, "sessionId");
        CardRecord proposal = registry.getRecord(validActionId)
                .orElseThrow(() -> new CardNotFoundException("卡片不存在或已过期"));
        if (!validSessionId.equals(proposal.getSessionId())) {
            throw new CardSecurityException("会话与服务端签发记录不一致");
        }
        if (proposal.getState() == CardState.EXPIRED || registry.isExpired(proposal)) {
            throw new CardNotFoundException("卡片已过期");
        }
        if (proposal.getState() != CardState.ISSUED || proposal.getApprovalState() != ApprovalState.APPROVED) {
            throw new CardStateConflictException("仅处于已批准待执行（APPROVED + NOT_STARTED）状态的提案可执行，当前状态: "
                    + proposal.getApprovalState() + "/" + proposal.getExecutionState());
        }

        String cardType = proposal.getCardType();
        CardSubmitHandler handler = resolveHandler(cardType);
        String actualVersion = verifyToolVersionAndCheckpoint(proposal, handler, validActionId, validSessionId);

        Map<String, Object> values = CardFormValues.validateAndMerge(
                proposal,
                proposal.getSubmittedValues() != null ? proposal.getSubmittedValues() : Map.of()
        );

        String actorId;
        if (handler.isSimulation()) {
            if (!allowMockSubmissions) throw new CardSecurityException("当前环境未开放模拟提交");
            actorId = (principal != null && principal.getName() != null && !principal.getName().isBlank())
                    ? principal.getName().trim()
                    : (proposal.getActorId() != null && !proposal.getActorId().isBlank() ? proposal.getActorId() : "mock-demo");
        } else {
            if (!realWritesEnabled) throw new CardSecurityException("真实写操作未启用");
            if (proposal.getSnapshot().isSimulation()) throw new CardSecurityException("模拟证据不能批准真实写操作");
            actorId = (principal != null && principal.getName() != null && !principal.getName().isBlank())
                    ? principal.getName().trim()
                    : null;
            if (actorId == null || actorId.isBlank()) throw new CardSecurityException("缺少服务端认证身份");
        }

        ApprovedCardSubmission approved = new ApprovedCardSubmission(
                validActionId, validSessionId, cardType, actorId, values,
                proposal.getEffectiveIdempotencyKey(), actualVersion);
        handler.validate(approved);

        String persistedActor = (proposal.getActorId() != null && !proposal.getActorId().isBlank())
                ? proposal.getActorId()
                : actorId;
        CardRecord claimed = registry.claim(proposal, persistedActor, values);
        return executeThroughUnifiedGateway(claimed, handler, approved, !handler.isSimulation());
    }

    /**
     * 一键审批并执行提案（同时经过审批校验与统一调用入口的实时执行前权限核验）。
     */
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
        if (proposal.getState() != CardState.ISSUED || proposal.getApprovalState() != ApprovalState.PENDING) {
            throw new CardStateConflictException("卡片不可再次提交，当前状态: "
                    + proposal.getState() + "/" + proposal.getApprovalState());
        }
        Map<String, Object> values = CardFormValues.validateAndMerge(proposal, request.getFormValues());
        CardSubmitHandler handler = resolveHandler(cardType);
        String actualVersion = verifyToolVersionAndCheckpoint(proposal, handler, actionId, sessionId);
        String actorId = resolveAndAuthorizeAtApproval(proposal, handler, values, principal);

        ApprovedCardSubmission approved = new ApprovedCardSubmission(
                actionId, sessionId, cardType, actorId, values,
                proposal.getEffectiveIdempotencyKey(), actualVersion);
        handler.validate(approved);
        CardRecord claimed = registry.claim(proposal, actorId, values);
        return executeThroughUnifiedGateway(claimed, handler, approved, !handler.isSimulation());
    }

    private SubmissionResult executeThroughUnifiedGateway(
            CardRecord claimed,
            CardSubmitHandler handler,
            ApprovedCardSubmission approved,
            boolean enforceActionAuthorizer) {
        String actionId = claimed.getActionId();
        String sessionId = claimed.getSessionId();
        String cardType = claimed.getCardType();
        String actualVersion = approved.getToolVersion();
        String targetResource = RevocableInvocationPolicy.extractTargetResource(approved.getFormValues());

        InvocationContext invContext = InvocationContext.builder()
                .source(InvocationSource.ACTION_GATE_WRITE)
                .sessionId(sessionId)
                .turnId(claimed.getTurnId())
                .actorId(approved.getActorId())
                .toolRef(handler.getClass().getSimpleName() + ".handleSubmit")
                .sideEffectLevel(SideEffectLevel.CRITICAL)
                .toolVersion(actualVersion)
                .resourceType("OPS_RUNTIME")
                .targetResource(targetResource)
                .arguments(approved.getFormValues())
                .proposalRecord(claimed)
                .approvedSubmission(approved)
                .build();

        AtomicBoolean handlerEntered = new AtomicBoolean(false);
        try {
            CardSubmitResult result = invocationGateway.invokeApprovedWrite(
                    invContext,
                    authorizer,
                    enforceActionAuthorizer,
                    () -> {
                        handlerEntered.set(true);
                        return handler.handleSubmit(approved);
                    }
            );
            if (result == null || !result.isSuccess() || result.getTicketId() == null || result.getTicketId().isBlank()) {
                throw new IllegalStateException("执行器未返回完整的确定成功回执");
            }
            registry.complete(actionId, result);
            if (checkpointStore != null) {
                checkpointStore.resumeOnApprovedExecution(actionId, sessionId, claimed.getIssuanceId(),
                        actualVersion, result.getTicketId(), result.getMessage());
            }
            return new SubmissionResult(actionId, sessionId, claimed.getTurnId(), cardType, result);
        } catch (CardSecurityException secEx) {
            if (!handlerEntered.get()) {
                // 在统一调用入口执行前被实时授权拒绝（尚未进入外部系统，确定无外部副作用）
                registry.rejectBeforeExecution(actionId, secEx.getMessage());
                if (checkpointStore != null) {
                    checkpointStore.cancelByActionId(actionId, sessionId, "执行前实时授权拒绝: " + secEx.getMessage());
                }
                throw secEx;
            }
            // 若已进入外部执行器后才抛异常，按结果未知处理
            throw markUnknownAndThrow(actionId, secEx);
        } catch (RuntimeException ex) {
            if (!handlerEntered.get()) {
                registry.rejectBeforeExecution(actionId, ex.getMessage());
                if (checkpointStore != null) {
                    checkpointStore.cancelByActionId(actionId, sessionId, "执行前校验失败: " + ex.getMessage());
                }
                throw ex;
            }
            throw markUnknownAndThrow(actionId, ex);
        }
    }

    private CardExecutionUnknownException markUnknownAndThrow(String actionId, RuntimeException ex) {
        try {
            registry.markExecutionUnknown(actionId, "执行开始后未确认成功，需核查外部系统");
        } catch (RuntimeException storageError) {
            ex.addSuppressed(storageError);
        }
        log.error("[ActionGate] 执行结果未知 actionId={}", actionId, ex);
        return new CardExecutionUnknownException(ex);
    }

    /**
     * 对账恢复入口（对应 12 号架构文档验收场景 2）：
     * 当外部写成功但结果落库前崩溃（处于 SUBMITTING 或 EXECUTION_UNKNOWN）时，
     * 通过统一调用入口（{@link InvocationSource#ACTION_GATE_RECONCILE}）以幂等键查询外部系统已执行结果，
     * 识别已有结果并恢复回合，或保持 EXECUTION_UNKNOWN 进入人工核查，绝不盲目重做。
     */
    public SubmissionResult reconcile(String actionId, String sessionId) {
        return reconcile(actionId, sessionId, null);
    }

    public SubmissionResult reconcile(String actionId, String sessionId, Principal principal) {
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
        CardSubmitHandler handler = resolveHandler(record.getCardType());
        String actorId = (principal != null && principal.getName() != null && !principal.getName().isBlank())
                ? principal.getName().trim()
                : null;
        Map<String, Object> submittedValues = record.getSubmittedValues() != null ? record.getSubmittedValues() : Map.of();
        InvocationContext reconcileContext = InvocationContext.builder()
                .source(InvocationSource.ACTION_GATE_RECONCILE)
                .sessionId(validSessionId)
                .turnId(record.getTurnId())
                .actorId(actorId)
                .toolRef(handler.getClass().getSimpleName() + ".reconcile")
                .sideEffectLevel(SideEffectLevel.READ)
                .toolVersion(record.getEffectiveToolVersion())
                .resourceType("OPS_RUNTIME")
                .targetResource(RevocableInvocationPolicy.extractTargetResource(submittedValues))
                .arguments(submittedValues)
                .proposalRecord(record)
                .build();
        Optional<CardSubmitResult> reconciled = invocationGateway.invokeRead(
                reconcileContext, () -> handler.reconcile(record));
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

    private CardSubmitHandler resolveHandler(String cardType) {
        return handlers.stream().filter(h -> h.supports(cardType)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的卡片类型"));
    }

    private String verifyToolVersionAndCheckpoint(
            CardRecord proposal,
            CardSubmitHandler handler,
            String actionId,
            String sessionId) {
        String expectedVersion = proposal.getEffectiveToolVersion();
        String actualVersion = normalizeVersion(handler.toolVersion());
        if (!expectedVersion.equals(actualVersion)) {
            throw new CardStateConflictException("等待审批期间工具版本已变化（提案版本=" + expectedVersion
                    + ", 当前版本=" + actualVersion + "），拒绝隐式切换实现，请重新发起审批或取消");
        }
        if (checkpointStore != null) {
            checkpointStore.verifyCanExecute(actionId, sessionId, proposal.getIssuanceId(), actualVersion);
        }
        return actualVersion;
    }

    private String resolveAndAuthorizeAtApproval(
            CardRecord proposal,
            CardSubmitHandler handler,
            Map<String, Object> values,
            Principal principal) {
        if (handler.isSimulation()) {
            if (!allowMockSubmissions) throw new CardSecurityException("当前环境未开放模拟提交");
            String actorId = (principal != null && principal.getName() != null && !principal.getName().isBlank())
                    ? principal.getName().trim()
                    : "mock-demo";
            if (authorizer != ActionAuthorizer.DENY_ALL && principal != null) {
                authorizer.authorize(actorId, proposal, values);
            }
            return actorId;
        }
        if (!realWritesEnabled) throw new CardSecurityException("真实写操作未启用");
        if (proposal.getSnapshot().isSimulation()) throw new CardSecurityException("模拟证据不能批准真实写操作");
        String actorId = principal == null ? null : principal.getName();
        if (actorId == null || actorId.isBlank()) throw new CardSecurityException("缺少服务端认证身份");
        authorizer.authorize(actorId, proposal, values);
        return actorId;
    }

    @Data
    public static final class ApprovalResult {
        private final String actionId;
        private final String sessionId;
        private final String turnId;
        private final String cardType;
        private final String actorId;
        private final String approvalState;
        private final String executionState;
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
