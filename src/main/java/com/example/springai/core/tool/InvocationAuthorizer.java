package com.example.springai.core.tool;

import com.example.springai.core.card.exception.CardSecurityException;

/**
 * 统一调用入口实时授权契约（12 号架构设计：在实际执行前检查用户、租户、目标资源和操作权限，按执行时的实时策略判断）。
 */
@FunctionalInterface
public interface InvocationAuthorizer {

    /**
     * 在调用底层工具或外部端口前的最后一刻执行实时授权核验。
     * 若权限不足或已被撤销，抛出 {@link CardSecurityException}。
     */
    void authorize(InvocationContext context);

    /**
     * 默认策略：
     * 1. 只读工具（READ）在已通过注册表与智能体白名单校验后默认放行；
     * 2. 写/高危工具（WRITE/CRITICAL）若未显式配置授权策略或非受控模拟模式，默认拒绝（Fail-Closed）。
     */
    InvocationAuthorizer DEFAULT_POLICY = context -> {
        if (context == null) {
            throw new CardSecurityException("调用上下文不能为空");
        }
        SideEffectLevel level = context.getSideEffectLevel() != null
                ? context.getSideEffectLevel()
                : SideEffectLevel.WRITE;
        if (level.requiresApproval() && context.getApprovedSubmission() == null) {
            throw new CardSecurityException("写/高危操作缺少 ActionGate 审批凭证，拒绝执行");
        }
    };
}
