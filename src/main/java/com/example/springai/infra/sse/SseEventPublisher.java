package com.example.springai.infra.sse;

import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.event.SseEventType;
import com.example.springai.core.event.SsePacket;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 统一事件发布器
 * 为各个 SubAgent 和 Pipeline 提供开箱即用的事件推送 API，支持端到端 turnId 与事件序号贯穿
 */
@Component
public class SseEventPublisher {

    private final SseEmitterManager emitterManager;
    private final Map<String, String> activeSessionTurns = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> turnSequences = new ConcurrentHashMap<>();

    public SseEventPublisher(SseEmitterManager emitterManager) {
        this.emitterManager = emitterManager;
    }

    /**
     * 绑定会话当前活动回合标识 (TurnId)
     */
    public void bindTurn(String sessionId, String turnId) {
        if (StringUtils.hasText(sessionId) && StringUtils.hasText(turnId)) {
            activeSessionTurns.put(sessionId, turnId);
            turnSequences.putIfAbsent(turnId, new AtomicLong(0));
        }
    }

    /**
     * 解除会话当前活动回合标识绑定
     */
    public void unbindTurn(String sessionId, String turnId) {
        if (StringUtils.hasText(sessionId)) {
            if (StringUtils.hasText(turnId)) {
                activeSessionTurns.remove(sessionId, turnId);
                turnSequences.remove(turnId);
            } else {
                String removed = activeSessionTurns.remove(sessionId);
                if (removed != null) {
                    turnSequences.remove(removed);
                }
            }
        }
    }

    /**
     * 判断指定会话的回合是否已过期（已被更新的回合抢占或已解除绑定）
     */
    public boolean isStaleTurn(String sessionId, String turnId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(turnId)) {
            return false;
        }
        String active = activeSessionTurns.get(sessionId);
        return active == null || !turnId.equals(active);
    }

    /**
     * 发送思考内容块
     */
    public boolean sendThinking(String sessionId, String thinkingChunk) {
        return emitterManager.send(sessionId, SsePacket.of(
                SseEventType.THINKING,
                enrichPayload(sessionId, null, Map.of("content", thinkingChunk != null ? thinkingChunk : ""))));
    }

    /**
     * 发送正式回复文本流块 (打字机效果)
     */
    public boolean sendMessage(String sessionId, String messageChunk) {
        return emitterManager.send(sessionId, SsePacket.of(
                SseEventType.MESSAGE,
                enrichPayload(sessionId, null, Map.of("content", messageChunk != null ? messageChunk : ""))));
    }

    /**
     * 发送卡片确认回执消息，精确绑定原卡片所属 turnId 与 actionId，防止串入新回合
     */
    public boolean sendCardReceipt(
            String sessionId,
            String turnId,
            String actionId,
            String ticketId,
            String messageChunk) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("content", messageChunk != null ? messageChunk : "");
        if (StringUtils.hasText(actionId)) {
            base.put("actionId", actionId);
        }
        if (StringUtils.hasText(ticketId)) {
            base.put("ticketId", ticketId);
        }
        base.put("cardReceipt", true);
        return emitterManager.send(sessionId, SsePacket.of(
                SseEventType.MESSAGE,
                enrichPayload(sessionId, turnId, base)));
    }

    /**
     * 发送进度通知 (例如: "正在查询案件逾期账单...")
     */
    public boolean sendProgress(String sessionId, String stage, String description) {
        return emitterManager.send(sessionId, SsePacket.of(
                SseEventType.PROGRESS,
                enrichPayload(sessionId, null, Map.of(
                        "stage", stage != null ? stage : "",
                        "description", description != null ? description : ""
                ))));
    }

    /**
     * 发送通用人机协同方案交互确认卡片 (Human-in-the-loop 交互)
     */
    public boolean sendInteractiveCard(String sessionId, InteractiveCard card) {
        if (card != null && !StringUtils.hasText(card.getTurnId()) && StringUtils.hasText(sessionId)) {
            String activeTurnId = activeSessionTurns.get(sessionId);
            if (StringUtils.hasText(activeTurnId)) {
                card.setTurnId(activeTurnId);
            }
        }
        return emitterManager.send(sessionId, SsePacket.of(SseEventType.INTERACTIVE_CARD, card));
    }

    /**
     * 发送推荐追问列表
     */
    public boolean sendRecommendQuestions(String sessionId, List<String> questions) {
        return emitterManager.send(sessionId, SsePacket.of(
                SseEventType.RECOMMEND_QUESTIONS,
                enrichPayload(sessionId, null, Map.of("questions", questions != null ? questions : List.of()))));
    }

    /**
     * 发送会话标题
     */
    public boolean sendConversationTitle(String sessionId, String title) {
        return emitterManager.send(sessionId, SsePacket.of(
                SseEventType.CONVERSATION_TITLE,
                enrichPayload(sessionId, null, Map.of("title", title != null ? title : ""))));
    }

    /**
     * 当前回合结束。连接留给同一 sessionId 的后续提问。
     */
    public boolean sendDone(String sessionId) {
        return emitterManager.send(sessionId, SsePacket.of(
                SseEventType.DONE,
                enrichPayload(sessionId, null, Map.of("status", "completed"))));
    }

    /**
     * 发送错误信息。不关闭长连接，客户端可以在同一会话里继续提问。
     */
    public boolean sendError(String sessionId, String errorMsg) {
        return emitterManager.send(sessionId, SsePacket.of(
                SseEventType.ERROR,
                enrichPayload(sessionId, null, Map.of("message", errorMsg != null ? errorMsg : ""))));
    }

    private Map<String, Object> enrichPayload(String sessionId, String explicitTurnId, Map<String, Object> base) {
        String turnId = StringUtils.hasText(explicitTurnId)
                ? explicitTurnId
                : (StringUtils.hasText(sessionId) ? activeSessionTurns.get(sessionId) : null);
        if (!StringUtils.hasText(turnId)) {
            return base;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(base);
        enriched.put("turnId", turnId);
        long seq = turnSequences.computeIfAbsent(turnId, k -> new AtomicLong(0)).incrementAndGet();
        enriched.put("sequence", seq);
        return enriched;
    }
}
