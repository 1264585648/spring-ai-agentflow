package com.example.springai.core.card.action;

import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.model.CardRecord;

import java.util.Map;

/**
 * 由宿主认证/授权系统实现：核验 actor 对会话、动作类型及最终目标/参数的权限。
 * actorId 只能来自服务端认证上下文，不能来自表单、sessionId 或任意请求头。
 * 提案是脱离存储的副本，values 已规范化且不可修改。拒绝时抛 CardSecurityException。
 */
@FunctionalInterface
public interface ActionAuthorizer {
    void authorize(String actorId, CardRecord proposal, Map<String, Object> values);

    ActionAuthorizer DENY_ALL = (actorId, proposal, values) -> {
        throw new CardSecurityException("未配置动作授权策略，拒绝真实写操作");
    };
}
