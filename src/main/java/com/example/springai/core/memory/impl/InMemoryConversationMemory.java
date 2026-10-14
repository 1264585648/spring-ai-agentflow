package com.example.springai.core.memory.impl;

import com.example.springai.core.memory.ConversationMemory;
import com.example.springai.core.memory.ConversationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 基于内存滑动窗口的会话记忆默认实现 (InMemoryConversationMemory)
 * 职责:
 * 1. 基于 ConcurrentHashMap 与双端队列存储各会话的历史消息;
 * 2. 严格控制单会话最大容量 (默认 20 条消息 / 10 轮问答)，超出时 FIFO 自动淘汰最早条目;
 * 3. 限制全局最大活跃会话数、会话空闲过期时间 (Idle TTL) 与单条消息最大字符数，防止内存无界增长 (F13)。
 */
@Component
@Slf4j
public class InMemoryConversationMemory implements ConversationMemory {

    public static final int DEFAULT_MAX_CAPACITY = 20;
    public static final int DEFAULT_MAX_SESSIONS = 1000;
    public static final long DEFAULT_SESSION_IDLE_TTL_MS = 30 * 60 * 1000L;
    public static final int DEFAULT_MAX_MESSAGE_CHARS = 8192;

    private static final class SessionBucket {
        private final Deque<ConversationMessage> deque = new ConcurrentLinkedDeque<>();
        private volatile long lastAccessMillis;

        private SessionBucket(long nowMillis) {
            this.lastAccessMillis = nowMillis;
        }
    }

    private final int maxCapacityPerSession;
    private final int maxSessions;
    private final long sessionIdleTtlMillis;
    private final int maxMessageChars;
    private final Clock clock;
    private final ConcurrentHashMap<String, SessionBucket> storage = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryConversationMemory() {
        this(DEFAULT_MAX_CAPACITY, DEFAULT_MAX_SESSIONS, DEFAULT_SESSION_IDLE_TTL_MS, DEFAULT_MAX_MESSAGE_CHARS, Clock.systemUTC());
    }

    public InMemoryConversationMemory(int maxCapacityPerSession) {
        this(maxCapacityPerSession, DEFAULT_MAX_SESSIONS, DEFAULT_SESSION_IDLE_TTL_MS, DEFAULT_MAX_MESSAGE_CHARS, Clock.systemUTC());
    }

    public InMemoryConversationMemory(
            int maxCapacityPerSession,
            int maxSessions,
            long sessionIdleTtlMillis,
            int maxMessageChars,
            Clock clock) {
        this.maxCapacityPerSession = maxCapacityPerSession > 0 ? maxCapacityPerSession : DEFAULT_MAX_CAPACITY;
        this.maxSessions = maxSessions > 0 ? maxSessions : DEFAULT_MAX_SESSIONS;
        this.sessionIdleTtlMillis = sessionIdleTtlMillis > 0 ? sessionIdleTtlMillis : DEFAULT_SESSION_IDLE_TTL_MS;
        this.maxMessageChars = maxMessageChars > 0 ? maxMessageChars : DEFAULT_MAX_MESSAGE_CHARS;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public void append(String sessionId, ConversationMessage message) {
        if (sessionId == null || sessionId.isBlank() || message == null) {
            return;
        }

        long now = clock.millis();
        evictExpiredSessions(now);

        ConversationMessage boundedMsg = truncateIfNecessary(message);
        SessionBucket bucket = storage.computeIfAbsent(sessionId, k -> new SessionBucket(now));
        bucket.lastAccessMillis = now;

        synchronized (bucket.deque) {
            bucket.deque.addLast(boundedMsg);
            while (bucket.deque.size() > maxCapacityPerSession) {
                bucket.deque.pollFirst();
            }
        }

        enforceMaxSessions();

        log.debug("[Memory] 追加会话历史, sessionId={}, role={}, 当前窗口大小={}, 活跃会话数={}",
                sessionId, boundedMsg.getRole(), bucket.deque.size(), storage.size());
    }

    @Override
    public List<ConversationMessage> getRecentMessages(String sessionId, int lastN) {
        if (sessionId == null || sessionId.isBlank() || lastN <= 0) {
            return Collections.emptyList();
        }

        long now = clock.millis();
        evictExpiredSessions(now);

        SessionBucket bucket = storage.get(sessionId);
        if (bucket == null || bucket.deque.isEmpty()) {
            return Collections.emptyList();
        }
        bucket.lastAccessMillis = now;

        List<ConversationMessage> snapshot;
        synchronized (bucket.deque) {
            snapshot = new ArrayList<>(bucket.deque);
        }

        int fromIndex = Math.max(0, snapshot.size() - lastN);
        return Collections.unmodifiableList(snapshot.subList(fromIndex, snapshot.size()));
    }

    @Override
    public List<ConversationMessage> getAllMessages(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }

        long now = clock.millis();
        evictExpiredSessions(now);

        SessionBucket bucket = storage.get(sessionId);
        if (bucket == null || bucket.deque.isEmpty()) {
            return Collections.emptyList();
        }
        bucket.lastAccessMillis = now;

        synchronized (bucket.deque) {
            return Collections.unmodifiableList(new ArrayList<>(bucket.deque));
        }
    }

    @Override
    public void clear(String sessionId) {
        if (sessionId != null) {
            storage.remove(sessionId);
            log.info("[Memory] 已清空会话记忆: sessionId={}", sessionId);
        }
    }

    @Override
    public int size(String sessionId) {
        if (sessionId == null) {
            return 0;
        }
        evictExpiredSessions(clock.millis());
        SessionBucket bucket = storage.get(sessionId);
        return bucket != null ? bucket.deque.size() : 0;
    }

    /**
     * 获取当前存储的活跃会话总数 (供监控指标与容量测试核验)
     */
    public int sessionCount() {
        evictExpiredSessions(clock.millis());
        return storage.size();
    }

    /**
     * 清理超过空闲 TTL 的过期会话
     */
    public int evictExpiredSessions() {
        return evictExpiredSessions(clock.millis());
    }

    private int evictExpiredSessions(long nowMillis) {
        int evicted = 0;
        for (Map.Entry<String, SessionBucket> entry : storage.entrySet()) {
            SessionBucket bucket = entry.getValue();
            if (bucket != null && (nowMillis - bucket.lastAccessMillis) > sessionIdleTtlMillis) {
                if (storage.remove(entry.getKey(), bucket)) {
                    evicted++;
                }
            }
        }
        return evicted;
    }

    private void enforceMaxSessions() {
        while (storage.size() > maxSessions) {
            String oldestKey = null;
            long oldestAccess = Long.MAX_VALUE;
            for (Map.Entry<String, SessionBucket> entry : storage.entrySet()) {
                long access = entry.getValue().lastAccessMillis;
                if (access < oldestAccess) {
                    oldestAccess = access;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                break;
            }
            storage.remove(oldestKey);
        }
    }

    private ConversationMessage truncateIfNecessary(ConversationMessage message) {
        String content = message.getContent();
        if (content == null || content.length() <= maxMessageChars) {
            return message;
        }
        String truncated = content.substring(0, maxMessageChars) + "...[TRUNCATED]";
        return ConversationMessage.builder()
                .role(message.getRole())
                .content(truncated)
                .timestamp(message.getTimestamp())
                .build();
    }
}

