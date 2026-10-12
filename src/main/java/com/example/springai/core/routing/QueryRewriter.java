package com.example.springai.core.routing;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.memory.ConversationMessage;

import java.util.List;

/**
 * 多轮会话指代消除与意图重写器契约 (QueryRewriter)
 * 职责:
 * 1. 在 AgentContext 进入 IntentResolver 责任链前执行意图预处理;
 * 2. 结合历史会话消除代词（如“它”、“刚才那个慢SQL”、“继续查日志”）、补齐关键服务名与TraceId;
 * 3. 产出规范、自包含的独立提问文本 (effectiveQuery)。
 */
public interface QueryRewriter {

    /**
     * 执行查询改写
     *
     * @param context 当前回合上下文
     * @param history 最近上下文历史消息
     * @return 改写后的有效文本 (effectiveQuery)；若无需改写或无历史则返回原 query
     */
    String rewrite(AgentContext context, List<ConversationMessage> history);
}
