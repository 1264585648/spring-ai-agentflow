package com.example.springai.execution.sse;

import com.example.springai.card.model.InteractiveCard;
import com.example.springai.common.constant.SseEventType;
import com.example.springai.common.result.SsePacket;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SSE 统一事件发布器
 * 为各个 SubAgent 和 Pipeline 提供开箱即用的事件推送 API
 */
@Component
public class SseEventPublisher {

    private final SseEmitterManager emitterManager;

    public SseEventPublisher(SseEmitterManager emitterManager) {
        this.emitterManager = emitterManager;
    }

    /**
     * 发送思考内容块
     */
    public void sendThinking(String sessionId, String thinkingChunk) {
        emitterManager.send(sessionId, SsePacket.of(SseEventType.THINKING, Map.of("content", thinkingChunk)));
    }

    /**
     * 发送正式回复文本流块 (打字机效果)
     */
    public void sendMessage(String sessionId, String messageChunk) {
        emitterManager.send(sessionId, SsePacket.of(SseEventType.MESSAGE, Map.of("content", messageChunk)));
    }

    /**
     * 发送进度通知 (例如: "正在查询案件逾期账单...")
     */
    public void sendProgress(String sessionId, String stage, String description) {
        emitterManager.send(sessionId, SsePacket.of(SseEventType.PROGRESS, Map.of(
                "stage", stage,
                "description", description
        )));
    }

    /**
     * 发送通用人机协同方案交互确认卡片 (Human-in-the-loop 交互)
     */
    public void sendInteractiveCard(String sessionId, com.example.springai.card.model.InteractiveCard card) {
        emitterManager.send(sessionId, SsePacket.of(SseEventType.INTERACTIVE_CARD, card));
    }

    /**
     * 发送推荐追问列表
     */
    public void sendRecommendQuestions(String sessionId, List<String> questions) {
        emitterManager.send(sessionId, SsePacket.of(SseEventType.RECOMMEND_QUESTIONS, Map.of("questions", questions)));
    }

    /**
     * 发送会话标题
     */
    public void sendConversationTitle(String sessionId, String title) {
        emitterManager.send(sessionId, SsePacket.of(SseEventType.CONVERSATION_TITLE, Map.of("title", title)));
    }

    /**
     * 正常完成流输出并关闭
     */
    public void sendDone(String sessionId) {
        emitterManager.send(sessionId, SsePacket.of(SseEventType.DONE, Map.of("status", "completed")));
        emitterManager.complete(sessionId);
    }

    /**
     * 发送错误信息
     */
    public void sendError(String sessionId, String errorMsg) {
        emitterManager.send(sessionId, SsePacket.of(SseEventType.ERROR, Map.of("message", errorMsg)));
        emitterManager.complete(sessionId);
    }
}
