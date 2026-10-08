package com.example.springai.execution.sse;

import com.example.springai.common.result.SsePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE Emitter 连接管理器
 * 负责维护长连接生命周期、心跳与线程安全推送
 */
@Component
public class SseEmitterManager {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterManager.class);

    /**
     * 连接超时时间: 5分钟
     */
    private static final Long DEFAULT_TIMEOUT = 5 * 60 * 1000L;

    private final Map<String, SseEmitter> emitterMap = new ConcurrentHashMap<>();

    /**
     * 建立并注册 SSE 连接
     */
    public SseEmitter createEmitter(String sessionId) {
        // 如果旧连接存在，先关闭
        removeEmitter(sessionId);

        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);

        emitter.onCompletion(() -> {
            log.info("[SSE] 会话完成关闭, sessionId: {}", sessionId);
            emitterMap.remove(sessionId);
        });

        emitter.onTimeout(() -> {
            log.warn("[SSE] 会话连接超时, sessionId: {}", sessionId);
            emitter.complete();
            emitterMap.remove(sessionId);
        });

        emitter.onError((ex) -> {
            log.error("[SSE] 会话发生异常断开, sessionId: {}, error: {}", sessionId, ex.getMessage());
            emitterMap.remove(sessionId);
        });

        emitterMap.put(sessionId, emitter);
        log.info("[SSE] 建立新连接成功, sessionId: {}", sessionId);
        return emitter;
    }

    /**
     * 向指定会话安全推送事件包
     */
    public boolean send(String sessionId, SsePacket<?> packet) {
        SseEmitter emitter = emitterMap.get(sessionId);
        if (emitter == null) {
            log.warn("[SSE] 未找到有效连接, 发送忽略, sessionId: {}, event: {}", sessionId, packet.getEvent());
            return false;
        }

        try {
            SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                    .name(packet.getEvent())
                    .data(packet.getData(), MediaType.APPLICATION_JSON)
                    .id(String.valueOf(packet.getTimestamp()));

            emitter.send(eventBuilder);
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.error("[SSE] 发送消息异常，清理连接, sessionId: {}, error: {}", sessionId, ex.getMessage());
            removeEmitter(sessionId);
            return false;
        }
    }

    /**
     * 完成并移除连接
     */
    public void complete(String sessionId) {
        SseEmitter emitter = emitterMap.remove(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 移除连接
     */
    public void removeEmitter(String sessionId) {
        SseEmitter emitter = emitterMap.remove(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isConnected(String sessionId) {
        return emitterMap.containsKey(sessionId);
    }
}
