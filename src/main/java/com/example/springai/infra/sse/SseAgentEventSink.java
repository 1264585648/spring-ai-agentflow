package com.example.springai.infra.sse;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.event.AgentEventSink;

import java.util.List;

/**
 * 基于 SSE 的智能体下行事件出口实现 (SseAgentEventSink)
 * 职责:
 * 1. 绑定会话 SessionId，将 AgentEventSink 调用转发至 SseEventPublisher;
 * 2. 下发交互卡片 (card) 时自动向 CardRegistry 登记原始快照，锁定只读字段与下发时间。
 */
public class SseAgentEventSink implements AgentEventSink {

    private final String sessionId;
    private final SseEventPublisher publisher;
    private final CardRegistry cardRegistry;

    public SseAgentEventSink(String sessionId, SseEventPublisher publisher, CardRegistry cardRegistry) {
        this.sessionId = sessionId;
        this.publisher = publisher;
        this.cardRegistry = cardRegistry;
    }

    public SseAgentEventSink(String sessionId, SseEventPublisher publisher) {
        this(sessionId, publisher, null);
    }

    @Override
    public boolean thinking(String content) {
        return publisher.sendThinking(sessionId, content);
    }

    @Override
    public boolean progress(String stage, String description) {
        return publisher.sendProgress(sessionId, stage, description);
    }

    @Override
    public boolean message(String chunk) {
        return publisher.sendMessage(sessionId, chunk);
    }

    @Override
    public boolean card(InteractiveCard card) {
        if (card != null && cardRegistry != null) {
            cardRegistry.issue(sessionId, card);
        }
        return publisher.sendInteractiveCard(sessionId, card);
    }

    @Override
    public boolean recommend(List<String> questions) {
        return publisher.sendRecommendQuestions(sessionId, questions);
    }

    @Override
    public boolean done() {
        return publisher.sendDone(sessionId);
    }

    @Override
    public boolean error(String message) {
        return publisher.sendError(sessionId, message);
    }
}
