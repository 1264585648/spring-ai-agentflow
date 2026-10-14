package com.example.springai.infra.sse;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.event.AgentEventSink;
import com.example.springai.core.memory.ConversationMemory;
import com.example.springai.core.memory.ConversationMessage;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 基于 SSE 的智能体下行事件出口实现 (SseAgentEventSink)
 * 职责:
 * 1. 绑定会话 SessionId，将 AgentEventSink 调用转发至 SseEventPublisher;
 * 2. 下发交互卡片 (card) 时自动向 CardRegistry 登记原始快照，锁定只读字段与下发时间;
 * 3. 收集本轮助手的完整流式输出文本，在回合结束 (done) 时自动归档至 ConversationMemory。
 */
public class SseAgentEventSink implements AgentEventSink {

    private final String sessionId;
    private final String turnId;
    private final SseEventPublisher publisher;
    private final CardRegistry cardRegistry;
    private final ConversationMemory conversationMemory;
    private final String originalQuery;
    private final StringBuilder messageAccumulator = new StringBuilder();

    public SseAgentEventSink(
            String sessionId,
            String turnId,
            SseEventPublisher publisher,
            CardRegistry cardRegistry,
            ConversationMemory conversationMemory,
            String originalQuery) {
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.publisher = publisher;
        this.cardRegistry = cardRegistry;
        this.conversationMemory = conversationMemory;
        this.originalQuery = originalQuery;
        if (this.publisher != null && StringUtils.hasText(sessionId) && StringUtils.hasText(turnId)) {
            this.publisher.bindTurn(sessionId, turnId);
        }
    }

    public SseAgentEventSink(
            String sessionId,
            SseEventPublisher publisher,
            CardRegistry cardRegistry,
            ConversationMemory conversationMemory,
            String originalQuery) {
        this(sessionId, null, publisher, cardRegistry, conversationMemory, originalQuery);
    }

    public SseAgentEventSink(String sessionId, SseEventPublisher publisher, CardRegistry cardRegistry) {
        this(sessionId, null, publisher, cardRegistry, null, null);
    }

    public SseAgentEventSink(String sessionId, SseEventPublisher publisher) {
        this(sessionId, null, publisher, null, null, null);
    }

    private boolean isTurnSuperseded() {
        return publisher != null && StringUtils.hasText(turnId) && publisher.isStaleTurn(sessionId, turnId);
    }

    @Override
    public boolean thinking(String content) {
        if (isTurnSuperseded()) {
            return false;
        }
        return publisher.sendThinking(sessionId, content);
    }

    @Override
    public boolean progress(String stage, String description) {
        if (isTurnSuperseded()) {
            return false;
        }
        return publisher.sendProgress(sessionId, stage, description);
    }

    @Override
    public boolean message(String chunk) {
        if (isTurnSuperseded()) {
            return false;
        }
        if (chunk != null) {
            messageAccumulator.append(chunk);
        }
        return publisher.sendMessage(sessionId, chunk);
    }

    @Override
    public boolean card(InteractiveCard card) {
        if (isTurnSuperseded()) {
            return false;
        }
        if (card != null) {
            if (!StringUtils.hasText(card.getTurnId()) && StringUtils.hasText(turnId)) {
                card.setTurnId(turnId);
            }
            if (cardRegistry != null) {
                cardRegistry.issue(sessionId, card);
            }
            if (StringUtils.hasText(card.getTitle())) {
                messageAccumulator.append("\n[已生成操作卡片: ").append(card.getTitle()).append("]");
            }
        }
        return publisher.sendInteractiveCard(sessionId, card);
    }

    @Override
    public boolean recommend(List<String> questions) {
        if (isTurnSuperseded()) {
            return false;
        }
        return publisher.sendRecommendQuestions(sessionId, questions);
    }

    @Override
    public boolean done() {
        if (isTurnSuperseded()) {
            return false;
        }
        try {
            if (conversationMemory != null && StringUtils.hasText(originalQuery)) {
                conversationMemory.append(sessionId, ConversationMessage.user(originalQuery));
                String fullReply = messageAccumulator.toString().trim();
                if (StringUtils.hasText(fullReply)) {
                    conversationMemory.append(sessionId, ConversationMessage.assistant(fullReply));
                }
            }
            return publisher.sendDone(sessionId);
        } finally {
            if (publisher != null) {
                publisher.unbindTurn(sessionId, turnId);
            }
        }
    }

    @Override
    public boolean error(String message) {
        if (isTurnSuperseded()) {
            return false;
        }
        try {
            return publisher.sendError(sessionId, message);
        } finally {
            if (publisher != null) {
                publisher.unbindTurn(sessionId, turnId);
            }
        }
    }
}
