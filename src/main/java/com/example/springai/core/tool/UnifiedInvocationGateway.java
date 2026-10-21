package com.example.springai.core.tool;

import com.example.springai.core.card.action.ActionAuthorizer;
import com.example.springai.core.card.action.ApprovedCardSubmission;
import com.example.springai.core.card.exception.CardSecurityException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 统一调用入口（UnifiedInvocationGateway）。
 * <p>
 * 遵循 12 号架构设计：
 * 1. 模型工具调用（AGENT_MODEL_TOOL）、L1 规则直通（L1_DIRECT）、Workflow 直调（WORKFLOW_DIRECT）、
 *    卡片提交写执行（ACTION_GATE_WRITE）全部收敛经由本入口执行；
 * 2. 强制执行副作用分级校验：非审批路径仅允许执行 {@link SideEffectLevel#READ} 工具；
 *    {@link SideEffectLevel#WRITE} 与 {@link SideEffectLevel#CRITICAL} 必须持有 ActionGate 签发的 {@link ApprovedCardSubmission}；
 * 3. 强制执行智能体工具白名单与用户/资源实时授权双重校验：在实际执行前的最后一刻按实时策略核验权限，
 *    确保“审批后权限被撤销”在执行前被实时拦截（原型验收场景 5）。
 */
@Component
@Slf4j
public class UnifiedInvocationGateway {

    private static final int MAX_AUDIT_HISTORY = 256;

    private volatile InvocationAuthorizer authorizer;
    private final AtomicLong totalInvocations = new AtomicLong();
    private final AtomicLong readInvocations = new AtomicLong();
    private final AtomicLong writeInvocations = new AtomicLong();
    private final AtomicLong rejectedInvocations = new AtomicLong();
    private final Deque<AuditEntry> recentAudits = new ArrayDeque<>(MAX_AUDIT_HISTORY);

    public record AuditEntry(
            InvocationSource source,
            String toolRef,
            SideEffectLevel sideEffectLevel,
            String actorId,
            String sessionId,
            String targetResource,
            boolean allowed,
            String rejectReason,
            long timestamp
    ) {
    }

    public UnifiedInvocationGateway() {
        this(InvocationAuthorizer.DEFAULT_POLICY);
    }

    @Autowired
    public UnifiedInvocationGateway(@Autowired(required = false) InvocationAuthorizer authorizer) {
        this.authorizer = authorizer != null ? authorizer : InvocationAuthorizer.DEFAULT_POLICY;
    }

    public void setAuthorizer(InvocationAuthorizer authorizer) {
        this.authorizer = authorizer != null ? authorizer : InvocationAuthorizer.DEFAULT_POLICY;
    }

    public InvocationAuthorizer getAuthorizer() {
        return authorizer;
    }

    /**
     * 统一只读工具调用入口（承接 L1 规则直通、大模型 Function Calling、Workflow 只读节点）。
     */
    public <T> T invokeRead(InvocationContext context, Supplier<T> execution) {
        Objects.requireNonNull(context, "InvocationContext 不能为空");
        Objects.requireNonNull(execution, "execution 不能为空");
        enrichFromThreadScope(context);

        totalInvocations.incrementAndGet();
        SideEffectLevel level = context.getSideEffectLevel() != null
                ? context.getSideEffectLevel()
                : SideEffectLevel.WRITE;

        // 1. 强制校验副作用分级：只读路径严禁执行 WRITE / CRITICAL 工具
        if (!level.isReadOnly()) {
            rejectedInvocations.incrementAndGet();
            String msg = String.format("统一调用入口拦截：工具 [%s] 副作用级别为 %s，禁止通过只读入口 (%s) 直接调用",
                    context.getToolRef(), level, context.getSource());
            recordAudit(context, false, msg);
            throw new IllegalArgumentException(msg);
        }

        // 2. 若来自大模型智能体调用，强制核验智能体工具白名单
        if (context.getSource() == InvocationSource.AGENT_MODEL_TOOL) {
            if (!isAllowedForAgent(context.getToolRef(), context.getAgentAllowedTools())) {
                rejectedInvocations.incrementAndGet();
                String msg = String.format("统一调用入口拦截：智能体 [%s] 未获授权调用工具 [%s]",
                        context.getAgentCode(), context.getToolRef());
                recordAudit(context, false, msg);
                throw new CardSecurityException(msg);
            }
        }

        // 3. 在实际调用前执行实时用户/会话/目标资源权限检查
        try {
            authorizer.authorize(context);
        } catch (RuntimeException ex) {
            rejectedInvocations.incrementAndGet();
            recordAudit(context, false, ex.getMessage());
            throw ex;
        }

        readInvocations.incrementAndGet();
        recordAudit(context, true, null);
        log.debug("[UnifiedInvocationGateway] 放行只读调用: source={}, tool={}, resource={}",
                context.getSource(), context.getToolRef(), context.getTargetResource());
        return execution.get();
    }

    /**
     * 统一写/高危操作调用入口（仅接受 ActionGate 传入的已批准凭证 {@link ApprovedCardSubmission}，并在执行前最后一刻实时复核权限）。
     */
    public <T> T invokeApprovedWrite(
            InvocationContext context,
            ActionAuthorizer actionAuthorizer,
            boolean enforceActionAuthorizer,
            Supplier<T> execution) {
        Objects.requireNonNull(context, "InvocationContext 不能为空");
        Objects.requireNonNull(execution, "execution 不能为空");
        enrichFromThreadScope(context);

        totalInvocations.incrementAndGet();
        ApprovedCardSubmission approved = context.getApprovedSubmission();
        if (approved == null) {
            rejectedInvocations.incrementAndGet();
            String msg = "统一调用入口拦截：写操作缺少 ApprovedCardSubmission 审批凭证，严禁绕过 ActionGate 执行";
            recordAudit(context, false, msg);
            throw new CardSecurityException(msg);
        }

        // 1. 在真正触发外部副作用前的最后一刻，执行实时权限复核（防审批后权限被撤销）
        try {
            String originalApprover = context.getProposalRecord() != null ? context.getProposalRecord().getActorId() : null;
            if (enforceActionAuthorizer && actionAuthorizer != null) {
                actionAuthorizer.authorize(approved.getActorId(), context.getProposalRecord(), approved.getFormValues());
                if (!isBlank(originalApprover) && !originalApprover.equals(approved.getActorId())) {
                    actionAuthorizer.authorize(originalApprover, context.getProposalRecord(), approved.getFormValues());
                }
            }
            if (authorizer != null && (!enforceActionAuthorizer || authorizer != actionAuthorizer)) {
                authorizer.authorize(context);
            }
            if (authorizer != null && !isBlank(originalApprover) && !originalApprover.equals(context.getActorId())) {
                InvocationContext approverCtx = InvocationContext.builder()
                        .source(context.getSource())
                        .sessionId(context.getSessionId())
                        .turnId(context.getTurnId())
                        .actorId(originalApprover)
                        .tenantId(context.getTenantId())
                        .toolRef(context.getToolRef())
                        .sideEffectLevel(context.getSideEffectLevel())
                        .toolVersion(context.getToolVersion())
                        .resourceType(context.getResourceType())
                        .targetResource(context.getTargetResource())
                        .arguments(context.getArguments())
                        .proposalRecord(context.getProposalRecord())
                        .approvedSubmission(approved)
                        .build();
                authorizer.authorize(approverCtx);
            }
        } catch (RuntimeException ex) {
            rejectedInvocations.incrementAndGet();
            recordAudit(context, false, ex.getMessage());
            log.warn("[UnifiedInvocationGateway] 写操作在执行前被实时授权拒绝: actionId={}, actor={}, reason={}",
                    approved.getActionId(), approved.getActorId(), ex.getMessage());
            throw ex;
        }

        writeInvocations.incrementAndGet();
        recordAudit(context, true, null);
        log.info("[UnifiedInvocationGateway] 实时授权核验通过，执行已批准写动作: actionId={}, tool={}, actor={}, resource={}",
                approved.getActionId(), context.getToolRef(), approved.getActorId(), context.getTargetResource());
        return execution.get();
    }

    public long getTotalInvocations() {
        return totalInvocations.get();
    }

    public long getReadInvocations() {
        return readInvocations.get();
    }

    public long getWriteInvocations() {
        return writeInvocations.get();
    }

    public long getRejectedInvocations() {
        return rejectedInvocations.get();
    }

    public List<AuditEntry> getRecentAudits() {
        synchronized (recentAudits) {
            return List.copyOf(recentAudits);
        }
    }

    private static void enrichFromThreadScope(InvocationContext context) {
        InvocationContext.CallScope scope = InvocationContext.currentScope();
        if (scope == null) {
            return;
        }
        if (isBlank(context.getSessionId())) {
            context.setSessionId(scope.sessionId());
        }
        if (isBlank(context.getTurnId())) {
            context.setTurnId(scope.turnId());
        }
        if (isBlank(context.getActorId())) {
            context.setActorId(scope.actorId());
        }
        if (isBlank(context.getTenantId())) {
            context.setTenantId(scope.tenantId());
        }
    }

    private static boolean isAllowedForAgent(String toolRef, Set<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty() || isBlank(toolRef)) {
            return false;
        }
        String normalizedRef = toolRef.trim().toLowerCase(Locale.ROOT);
        int dot = normalizedRef.indexOf('.');
        String beanPart = dot > 0 ? normalizedRef.substring(0, dot) : normalizedRef;
        String methodPart = dot > 0 ? normalizedRef.substring(dot + 1) : normalizedRef;
        for (String candidate : allowedTools) {
            if (candidate == null) continue;
            String norm = candidate.trim().toLowerCase(Locale.ROOT);
            if ("*".equals(norm) || norm.equals(normalizedRef) || norm.equals(beanPart) || norm.equals(methodPart)) {
                return true;
            }
        }
        return false;
    }

    private void recordAudit(InvocationContext ctx, boolean allowed, String rejectReason) {
        AuditEntry entry = new AuditEntry(
                ctx.getSource(),
                ctx.getToolRef(),
                ctx.getSideEffectLevel(),
                ctx.getActorId(),
                ctx.getSessionId(),
                ctx.getTargetResource(),
                allowed,
                rejectReason,
                System.currentTimeMillis()
        );
        synchronized (recentAudits) {
            while (recentAudits.size() >= MAX_AUDIT_HISTORY) {
                recentAudits.pollFirst();
            }
            recentAudits.addLast(entry);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
