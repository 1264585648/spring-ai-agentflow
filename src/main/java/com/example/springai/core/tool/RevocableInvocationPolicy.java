package com.example.springai.core.tool;

import com.example.springai.core.card.action.ActionAuthorizer;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.model.CardRecord;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支持实时权限授予与撤销的动态授权策略实现（同时实现 {@link InvocationAuthorizer} 与 {@link ActionAuthorizer}）。
 * 用于在统一调用入口验证：
 * 1. 用户/会话对特定目标资源（如 order-service）的实时读/写权限；
 * 2. 原型验收场景 5：提案审批通过后、真正执行前权限被实时撤销，统一调用入口立即拒绝执行。
 */
public class RevocableInvocationPolicy implements InvocationAuthorizer, ActionAuthorizer {

    private final Set<String> revokedActors = ConcurrentHashMap.newKeySet();
    private final Set<String> revokedActorResources = ConcurrentHashMap.newKeySet();
    private final Set<String> revokedSessionResources = ConcurrentHashMap.newKeySet();
    private final Set<String> grantedWriteActorResources = ConcurrentHashMap.newKeySet();
    private final Set<String> grantedWriteActors = ConcurrentHashMap.newKeySet();
    private final boolean requireExplicitWriteGrant;

    public RevocableInvocationPolicy() {
        this(false);
    }

    public RevocableInvocationPolicy(boolean requireExplicitWriteGrant) {
        this.requireExplicitWriteGrant = requireExplicitWriteGrant;
    }

    /**
     * 授予指定操作人对目标资源的写操作权限。
     */
    public void grantWrite(String actorId, String targetResource) {
        String actor = normalize(actorId);
        String res = normalize(targetResource);
        if (actor.isEmpty() || res.isEmpty()) {
            return;
        }
        revokedActors.remove(actor);
        revokedActorResources.remove(key(actor, "*"));
        if ("*".equals(res)) {
            revokedActorResources.removeIf(k -> k.startsWith(actor + "::"));
            grantedWriteActors.add(actor);
        } else {
            revokedActorResources.remove(key(actor, res));
            grantedWriteActorResources.add(key(actor, res));
        }
    }

    /**
     * 实时撤销指定操作人对目标资源的权限（读与写均立即失效）。
     */
    public void revokeResource(String actorId, String targetResource) {
        String actor = normalize(actorId);
        String res = normalize(targetResource);
        if (actor.isEmpty() || res.isEmpty()) {
            return;
        }
        if ("*".equals(res)) {
            grantedWriteActors.remove(actor);
            grantedWriteActorResources.removeIf(k -> k.startsWith(actor + "::"));
            revokedActorResources.add(key(actor, "*"));
        } else {
            grantedWriteActorResources.remove(key(actor, res));
            revokedActorResources.add(key(actor, res));
        }
    }

    /**
     * 实时撤销指定操作人的全部权限（同时清除此前已授予的所有资源写权限，防止重新局部授权时旧权限复活）。
     */
    public void revokeActor(String actorId) {
        String actor = normalize(actorId);
        if (actor.isEmpty()) {
            return;
        }
        grantedWriteActors.remove(actor);
        grantedWriteActorResources.removeIf(k -> k.startsWith(actor + "::"));
        revokedActors.add(actor);
    }

    /**
     * 实时撤销指定会话对某目标资源的访问权限（适用于匿名或会话级 L1/模型工具拦截）。
     */
    public void revokeSessionResource(String sessionId, String targetResource) {
        revokedSessionResources.add(key(normalize(sessionId), normalize(targetResource)));
    }

    public void restoreSessionResource(String sessionId, String targetResource) {
        revokedSessionResources.remove(key(normalize(sessionId), normalize(targetResource)));
    }

    @Override
    public void authorize(InvocationContext context) {
        Objects.requireNonNull(context, "InvocationContext 不能为空");
        String actor = normalize(context.getActorId());
        String session = normalize(context.getSessionId());
        String resource = normalize(context.getTargetResource());

        if (!actor.isEmpty() && revokedActors.contains(actor)) {
            throw new CardSecurityException("操作人 [" + context.getActorId() + "] 的权限已被实时撤销，拒绝执行");
        }
        if (!actor.isEmpty() && !resource.isEmpty()
                && (revokedActorResources.contains(key(actor, resource))
                || revokedActorResources.contains(key(actor, "*")))) {
            throw new CardSecurityException("操作人 [" + context.getActorId() + "] 对目标资源 ["
                    + context.getTargetResource() + "] 的权限已被实时撤销，拒绝执行");
        }
        if (!session.isEmpty() && !resource.isEmpty()
                && (revokedSessionResources.contains(key(session, resource))
                || revokedSessionResources.contains(key(session, "*")))) {
            throw new CardSecurityException("当前会话 [" + context.getSessionId() + "] 对目标资源 ["
                    + context.getTargetResource() + "] 的访问权限已被实时撤销，拒绝执行");
        }

        SideEffectLevel level = context.getSideEffectLevel() != null
                ? context.getSideEffectLevel()
                : SideEffectLevel.WRITE;
        if (level.requiresApproval() && requireExplicitWriteGrant) {
            boolean allowed = !actor.isEmpty() && (grantedWriteActors.contains(actor)
                    || (!resource.isEmpty() && grantedWriteActorResources.contains(key(actor, resource))));
            if (!allowed) {
                throw new CardSecurityException("操作人 [" + context.getActorId() + "] 未持有目标资源 ["
                        + context.getTargetResource() + "] 的实时写权限，拒绝执行");
            }
        }
    }

    @Override
    public void authorize(String actorId, CardRecord proposal, Map<String, Object> values) {
        String resource = extractTargetResource(values);
        InvocationContext ctx = InvocationContext.builder()
                .source(InvocationSource.ACTION_GATE_WRITE)
                .sessionId(proposal != null ? proposal.getSessionId() : null)
                .turnId(proposal != null ? proposal.getTurnId() : null)
                .actorId(actorId)
                .toolRef(proposal != null ? proposal.getCardType() : "UNKNOWN")
                .sideEffectLevel(SideEffectLevel.CRITICAL)
                .targetResource(resource)
                .arguments(values != null ? values : Map.of())
                .proposalRecord(proposal)
                .build();
        authorize(ctx);
    }

    public static String extractTargetResource(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        for (String key : new String[]{"service", "target", "target_identifier", "userId", "resource"}) {
            Object val = values.get(key);
            if (val != null && !val.toString().isBlank()) {
                return val.toString().trim();
            }
        }
        if (values.size() == 1) {
            Object singleVal = values.values().iterator().next();
            if (singleVal instanceof CharSequence || singleVal instanceof Number) {
                String text = singleVal.toString().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String key(String prefix, String suffix) {
        return prefix + "::" + suffix;
    }
}
