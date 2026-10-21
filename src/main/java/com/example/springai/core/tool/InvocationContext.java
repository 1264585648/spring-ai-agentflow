package com.example.springai.core.tool;

import com.example.springai.core.card.action.ApprovedCardSubmission;
import com.example.springai.core.card.model.CardRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 统一调用入口的实时调用上下文契约。
 * 承载调用来源、用户/租户身份、会话与回合标识、智能体白名单、工具副作用分级、目标资源及审批凭证。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvocationContext {

    private static final ThreadLocal<CallScope> CURRENT_SCOPE = new ThreadLocal<>();

    private InvocationSource source;
    private String sessionId;
    private String turnId;
    private String actorId;
    private String tenantId;
    private String agentCode;
    @Builder.Default
    private Set<String> agentAllowedTools = Collections.emptySet();
    private String toolRef;
    @Builder.Default
    private SideEffectLevel sideEffectLevel = SideEffectLevel.READ;
    @Builder.Default
    private String toolVersion = "v1";
    @Builder.Default
    private String resourceType = "GENERAL";
    private String targetResource;
    @Builder.Default
    private Map<String, Object> arguments = Collections.emptyMap();
    private CardRecord proposalRecord;
    private ApprovedCardSubmission approvedSubmission;

    /**
     * 线程绑定的轻量回合身份作用域（用于在 Spring AI 回调或 L1 调用时自动传递当前回合的 sessionId/turnId/actorId）。
     * 支持嵌套作用域，关闭时自动恢复外层 CallScope。
     */
    public static final class CallScope implements AutoCloseable {
        private final String sessionId;
        private final String turnId;
        private final String actorId;
        private final String tenantId;
        private final CallScope previous;
        private boolean closed;

        private CallScope(String sessionId, String turnId, String actorId, String tenantId, CallScope previous) {
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.actorId = actorId;
            this.tenantId = tenantId;
            this.previous = previous;
        }

        public String sessionId() {
            return sessionId;
        }

        public String turnId() {
            return turnId;
        }

        public String actorId() {
            return actorId;
        }

        public String tenantId() {
            return tenantId;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous != null) {
                CURRENT_SCOPE.set(previous);
            } else {
                CURRENT_SCOPE.remove();
            }
        }
    }

    public static CallScope openScope(String sessionId, String turnId, String actorId, String tenantId) {
        CallScope previous = CURRENT_SCOPE.get();
        CallScope scope = new CallScope(
                sessionId != null ? sessionId : (previous != null ? previous.sessionId() : null),
                turnId != null ? turnId : (previous != null ? previous.turnId() : null),
                actorId != null ? actorId : (previous != null ? previous.actorId() : null),
                tenantId != null ? tenantId : (previous != null ? previous.tenantId() : null),
                previous
        );
        CURRENT_SCOPE.set(scope);
        return scope;
    }

    public static CallScope currentScope() {
        return CURRENT_SCOPE.get();
    }
}
