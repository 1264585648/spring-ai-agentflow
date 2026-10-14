package com.example.springai.infra.sse;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.event.AgentEventSink;
import com.example.springai.core.memory.ConversationMemory;
import com.example.springai.core.memory.ConversationMessage;
import java.util.List;

/** 一次性终态出口。所有发送显式绑定原回合，累积内容有长度上限。 */
public class SseAgentEventSink implements AgentEventSink {
    private static final int MAX_REPLY_CHARS = 32768;
    private final String sessionId;
    private final String turnId;
    private final SseEventPublisher publisher;
    private final CardRegistry registry;
    private final ConversationMemory memory;
    private final String query;
    private final StringBuilder reply = new StringBuilder();
    private boolean terminal;
    public SseAgentEventSink(String sessionId, String turnId, SseEventPublisher publisher, CardRegistry registry,
                             ConversationMemory memory, String query) {
        this.sessionId = sessionId; this.turnId = turnId; this.publisher = publisher;
        this.registry = registry; this.memory = memory; this.query = query;
        if (publisher != null) publisher.bindTurn(sessionId, turnId);
    }
    public SseAgentEventSink(String s, SseEventPublisher p, CardRegistry r, ConversationMemory m, String q) { this(s, null, p, r, m, q); }
    public SseAgentEventSink(String s, SseEventPublisher p, CardRegistry r) { this(s, null, p, r, null, null); }
    public SseAgentEventSink(String s, SseEventPublisher p) { this(s, null, p, null, null, null); }
    private boolean available() { return !terminal && publisher != null && !publisher.isStaleTurn(sessionId, turnId); }
    @Override public synchronized boolean thinking(String content) {
        return available() && (turnId == null ? publisher.sendThinking(sessionId, content) : publisher.sendThinking(sessionId, turnId, content));
    }
    @Override public synchronized boolean progress(String stage, String description) {
        return available() && (turnId == null ? publisher.sendProgress(sessionId, stage, description) : publisher.sendProgress(sessionId, turnId, stage, description));
    }
    @Override public synchronized boolean message(String chunk) {
        if (!available()) return false;
        boolean sent = turnId == null ? publisher.sendMessage(sessionId, chunk) : publisher.sendMessage(sessionId, turnId, chunk);
        if (sent && chunk != null) append(chunk);
        return sent;
    }
    @Override public synchronized boolean card(InteractiveCard card) {
        if (!available() || card == null) return false;
        // 回合归属来自服务端上下文，不能采用调用方提供的另一 turnId。
        if (turnId != null) card.setTurnId(turnId);
        if (registry != null) registry.issue(sessionId, card);
        boolean sent = turnId == null ? publisher.sendInteractiveCard(sessionId, card) : publisher.sendInteractiveCard(sessionId, turnId, card);
        if (sent && card.getTitle() != null) append("\n[已生成操作卡片: " + card.getTitle() + "]");
        return sent;
    }
    @Override public synchronized boolean recommend(List<String> questions) {
        return available() && (turnId == null ? publisher.sendRecommendQuestions(sessionId, questions) : publisher.sendRecommendQuestions(sessionId, turnId, questions));
    }
    @Override public synchronized boolean done() {
        if (!available()) return false;
        terminal = true;
        try {
            if (memory != null && query != null && !query.isBlank()) {
                memory.append(sessionId, ConversationMessage.user(query));
                if (!reply.toString().isBlank()) memory.append(sessionId, ConversationMessage.assistant(reply.toString()));
            }
            return turnId == null ? publisher.sendDone(sessionId) : publisher.sendDone(sessionId, turnId);
        } finally { publisher.unbindTurn(sessionId, turnId); }
    }
    @Override public synchronized boolean error(String message) {
        if (!available()) return false;
        terminal = true;
        try { return turnId == null ? publisher.sendError(sessionId, message) : publisher.sendError(sessionId, turnId, message); }
        finally { publisher.unbindTurn(sessionId, turnId); }
    }
    private void append(String text) {
        int remaining = MAX_REPLY_CHARS - reply.length();
        if (remaining > 0) reply.append(text, 0, Math.min(remaining, text.length()));
    }
}
