package com.example.springai.infra.sse;

import com.example.springai.core.event.SessionDisconnectedEvent;
import com.example.springai.core.event.SsePacket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;
import java.util.concurrent.*;

/** 会话连接表。替换连接不会让旧回调删掉新连接；done/error 不关闭长连接。 */
@Component
@Slf4j
public class SseEmitterManager {
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher events;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat"); t.setDaemon(true); return t;
    });
    @Autowired
    public SseEmitterManager(ApplicationEventPublisher events) { this.events = events; }
    public SseEmitterManager() { this(null); }
    @PostConstruct public void startHeartbeat() { heartbeat.scheduleAtFixedRate(this::ping, 15, 15, TimeUnit.SECONDS); }
    @PreDestroy public void shutdownHeartbeat() {
        heartbeat.shutdownNow();
        emitters.keySet().forEach(this::complete);
    }
    public SseEmitter createEmitter(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) throw new IllegalArgumentException("无效 sessionId");
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> detach(sessionId, emitter));
        emitter.onTimeout(() -> detach(sessionId, emitter));
        emitter.onError(ex -> detach(sessionId, emitter));
        SseEmitter old = emitters.put(sessionId, emitter);
        if (old != null) old.complete();
        try {
            // 立即刷新响应头，不等待第一次业务事件/15 秒心跳；不新增第九种业务事件。
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception ex) { detach(sessionId, emitter); }
        return emitter;
    }
    public boolean send(String sessionId, SsePacket<?> packet) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) return false;
        synchronized (emitter) {
            if (emitters.get(sessionId) != emitter) return false;
            try {
                emitter.send(SseEmitter.event().name(packet.getEvent()).data(packet.getData(), MediaType.APPLICATION_JSON)
                        .id(packet.getEventId()));
                return true;
            } catch (Exception ex) {
                log.debug("[SSE] 发送失败，关闭会话连接 sessionId={}", sessionId, ex);
                detach(sessionId, emitter);
                return false;
            }
        }
    }
    public boolean isConnected(String sessionId) { return sessionId != null && emitters.containsKey(sessionId); }
    public void complete(String sessionId) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null) {
            detach(sessionId, emitter);
            try { emitter.complete(); } catch (RuntimeException ignored) { }
        }
    }
    public void removeEmitter(String sessionId) { complete(sessionId); }
    private void detach(String sessionId, SseEmitter expected) {
        if (emitters.remove(sessionId, expected) && events != null) events.publishEvent(new SessionDisconnectedEvent(sessionId));
    }
    private void ping() {
        emitters.forEach((sessionId, emitter) -> {
            synchronized (emitter) {
                if (emitters.get(sessionId) != emitter) return;
                try { emitter.send(SseEmitter.event().comment("ping")); }
                catch (Exception ex) { detach(sessionId, emitter); }
            }
        });
    }
}
