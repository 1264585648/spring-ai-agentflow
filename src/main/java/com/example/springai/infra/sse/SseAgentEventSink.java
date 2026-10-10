package com.example.springai.infra.sse;

import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.event.AgentEventSink;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 基于 SSE 的智能体下行事件出口实现 (SseAgentEventSink)
 * 职责:
 * 绑定会话 SessionId，将 AgentEventSink 调用转发至 SseEventPublisher。
 */
@RequiredArgsConstructor
public class SseAgentEventSink implements AgentEventSink {

    private final String sessionId;
    private final SseEventPublisher publisher;

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
