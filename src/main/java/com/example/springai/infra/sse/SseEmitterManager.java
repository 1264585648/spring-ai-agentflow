package com.example.springai.infra.sse;

import lombok.extern.slf4j.Slf4j;
import com.example.springai.core.event.SsePacket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE Emitter 连接管理器。
 * 连接按会话保持，done/error 只结束当前回合，不关闭长连接。
 */
@Component
@Slf4j
public class SseEmitterManager {


    /**
     * 0 表示容器不为这条异步请求设置超时，空闲保活交给心跳。
     */
    private static final Long NO_TIMEOUT = 0L;

    private static final long HEARTBEAT_INTERVAL_SECONDS = 15L;

    private final Map<String, SseEmitter> emitterMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdownHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }

    /**
     * 建立并注册 SSE 连接。先放入新连接，再关闭旧连接，避免旧回调删掉新连接。
     */
    public SseEmitter createEmitter(String sessionId) {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);

        emitter.onCompletion(() -> {
            log.info("[SSE] 会话完成关闭, sessionId: {}", sessionId);
            emitterMap.remove(sessionId, emitter);
        });

        emitter.onTimeout(() -> {
            log.warn("[SSE] 会话连接超时, sessionId: {}", sessionId);
            emitterMap.remove(sessionId, emitter);
        });

        emitter.onError((ex) -> {
            log.error("[SSE] 会话发生异常断开, sessionId: {}, error: {}", sessionId, ex.getMessage());
            emitterMap.remove(sessionId, emitter);
        });

        SseEmitter previous = emitterMap.put(sessionId, emitter);
        if (previous != null) {
            try {
                previous.complete();
            } catch (Exception ignored) {
            }
        }
        log.info("[SSE] 建立新连接成功, sessionId: {}", sessionId);
        return emitter;
    }

    /**
     * 向指定会话安全推送事件包。同一连接上的发送互斥。
     */
    public boolean send(String sessionId, SsePacket<?> packet) {
        SseEmitter emitter = emitterMap.get(sessionId);
        if (emitter == null) {
            log.warn("[SSE] 未找到有效连接, 发送忽略, sessionId: {}, event: {}", sessionId, packet.getEvent());
            return false;
        }

        synchronized (emitter) {
            if (emitterMap.get(sessionId) != emitter) {
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
                emitterMap.remove(sessionId, emitter);
                return false;
            }
        }
    }

    /**
     * 完成并移除连接。仅在客户端离开或服务端主动拆连接时使用。
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
        complete(sessionId);
    }

    public boolean isConnected(String sessionId) {
        return emitterMap.containsKey(sessionId);
    }

    private void sendHeartbeat() {
        for (Map.Entry<String, SseEmitter> entry : emitterMap.entrySet()) {
            SseEmitter emitter = entry.getValue();
            synchronized (emitter) {
                if (emitterMap.get(entry.getKey()) != emitter) {
                    continue;
                }
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception ex) {
                    log.debug("[SSE] 心跳失败，清理连接, sessionId: {}", entry.getKey());
                    emitterMap.remove(entry.getKey(), emitter);
                }
            }
        }
    }
}
