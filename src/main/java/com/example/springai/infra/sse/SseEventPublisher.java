package com.example.springai.infra.sse;

import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.event.SseEventType;
import com.example.springai.core.event.SsePacket;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 事件使用显式原始 turnId；时间戳不参与去重，回执不创建新的活动回合。 */
@Component
public class SseEventPublisher {
    private final SseEmitterManager manager;
    private final Map<String, String> activeTurns = new ConcurrentHashMap<>();
    // 进程级单调序列允许有间隔；跨进程/重启去重依赖包 UUID，不依赖序列从零开始。
    private final AtomicLong sequence = new AtomicLong();
    public SseEventPublisher(SseEmitterManager manager) { this.manager = manager; }
    public void bindTurn(String sessionId, String turnId) {
        if (hasText(sessionId) && hasText(turnId)) activeTurns.put(sessionId, turnId);
    }
    public void unbindTurn(String sessionId, String turnId) {
        if (hasText(sessionId) && hasText(turnId)) activeTurns.remove(sessionId, turnId);
    }
    public boolean isStaleTurn(String sessionId, String turnId) {
        return hasText(turnId) && !Objects.equals(turnId, activeTurns.get(sessionId));
    }
    public int activeTurnCount() { return activeTurns.size(); }
    public boolean sendThinking(String sessionId, String content) { return sendThinking(sessionId, activeTurns.get(sessionId), content); }
    public boolean sendThinking(String sessionId, String turnId, String content) {
        return send(sessionId, turnId, SseEventType.THINKING, Map.of("content", text(content)), true);
    }
    public boolean sendMessage(String sessionId, String content) { return sendMessage(sessionId, activeTurns.get(sessionId), content); }
    public boolean sendMessage(String sessionId, String turnId, String content) {
        return send(sessionId, turnId, SseEventType.MESSAGE, Map.of("content", text(content)), true);
    }
    public boolean sendProgress(String sessionId, String stage, String description) { return sendProgress(sessionId, activeTurns.get(sessionId), stage, description); }
    public boolean sendProgress(String sessionId, String turnId, String stage, String description) {
        return send(sessionId, turnId, SseEventType.PROGRESS, Map.of("stage", text(stage), "description", text(description)), true);
    }
    public boolean sendInteractiveCard(String sessionId, InteractiveCard card) { return sendInteractiveCard(sessionId, activeTurns.get(sessionId), card); }
    public boolean sendInteractiveCard(String sessionId, String turnId, InteractiveCard card) {
        if (card == null) return false;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionId", card.getActionId()); body.put("cardType", card.getCardType());
        body.put("title", card.getTitle()); body.put("description", card.getDescription()); body.put("fields", card.getFields());
        body.put("confirmButtonText", card.getConfirmButtonText()); body.put("cancelButtonText", card.getCancelButtonText());
        body.put("simulation", card.isSimulation());
        return send(sessionId, turnId, SseEventType.INTERACTIVE_CARD, body, true);
    }
    public boolean sendRecommendQuestions(String sessionId, List<String> questions) { return sendRecommendQuestions(sessionId, activeTurns.get(sessionId), questions); }
    public boolean sendRecommendQuestions(String sessionId, String turnId, List<String> questions) {
        return send(sessionId, turnId, SseEventType.RECOMMEND_QUESTIONS, Map.of("questions", questions == null ? List.of() : questions), true);
    }
    public boolean sendConversationTitle(String sessionId, String title) {
        return send(sessionId, activeTurns.get(sessionId), SseEventType.CONVERSATION_TITLE, Map.of("title", text(title)), true);
    }
    public boolean sendDone(String sessionId) { return sendDone(sessionId, activeTurns.get(sessionId)); }
    public boolean sendDone(String sessionId, String turnId) {
        return send(sessionId, turnId, SseEventType.DONE, Map.of("status", "completed"), true);
    }
    public boolean sendError(String sessionId, String message) { return sendError(sessionId, activeTurns.get(sessionId), message); }
    public boolean sendError(String sessionId, String turnId, String message) {
        return send(sessionId, turnId, SseEventType.ERROR, Map.of("message", text(message)), true);
    }
    public boolean sendCardReceipt(String sessionId, String turnId, String actionId, String ticketId, String content) {
        return send(sessionId, turnId, SseEventType.MESSAGE, Map.of("content", text(content), "actionId", text(actionId),
                "ticketId", text(ticketId), "cardReceipt", true), false);
    }
    private boolean send(String sessionId, String turnId, SseEventType type, Map<String, Object> base, boolean currentOnly) {
        if (currentOnly && isStaleTurn(sessionId, turnId)) return false;
        Map<String, Object> body = new LinkedHashMap<>(base);
        if (hasText(turnId)) body.put("turnId", turnId);
        body.put("sequence", sequence.incrementAndGet());
        // 在此之后即使新回合开始，该包仍携带旧 turnId，绝不能重新读取当前回合并改写归属。
        return manager.send(sessionId, SsePacket.of(type, body));
    }
    private static String text(String s) { return s == null ? "" : s; }
    private static boolean hasText(String s) { return s != null && !s.isBlank(); }
}
