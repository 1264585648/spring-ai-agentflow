package com.example.springai.core.memory.impl;

import com.example.springai.core.memory.ConversationMemory;
import com.example.springai.core.memory.ConversationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 基于内存滑动窗口的会话记忆默认实现 (InMemoryConversationMemory)
 * 职责:
 * 1. 基于 ConcurrentHashMap 与双端队列存储各会话的历史消息;
 * 2. 严格控制单会话最大容量 (默认 20 条消息 / 10 轮问答)，超出时 FIFO 自动淘汰最早条目;
 * 3. 读写操作保证线程安全，开箱即用，零外部中间件强依赖。
 */
@Component
@Slf4j
public class InMemoryConversationMemory implements ConversationMemory {

    public static final int DEFAULT_MAX_CAPACITY = 20;

    private final int maxCapacityPerSession;
    private final ConcurrentHashMap<String, Deque<ConversationMessage>> storage = new ConcurrentHashMap<>();

    public InMemoryConversationMemory() {
        this(DEFAULT_MAX_CAPACITY);
    }

    public InMemoryConversationMemory(int maxCapacityPerSession) {
        this.maxCapacityPerSession = maxCapacityPerSession > 0 ? maxCapacityPerSession : DEFAULT_MAX_CAPACITY;
    }

    @Override
    public void append(String sessionId, ConversationMessage message) {
        if (sessionId == null || sessionId.isBlank() || message == null) {
            return;
        }

        Deque<ConversationMessage> deque = storage.computeIfAbsent(sessionId, k -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            deque.addLast(message);
            // 滑动窗口：超过最大容量淘汰最早历史
            while (deque.size() > maxCapacityPerSession) {
                deque.pollFirst();
            }
        }

        log.debug("[Memory] 追加会话历史, sessionId={}, role={}, 当前窗口大小={}",
                sessionId, message.getRole(), deque.size());
    }

    @Override
    public List<ConversationMessage> getRecentMessages(String sessionId, int lastN) {
        if (sessionId == null || sessionId.isBlank() || lastN <= 0) {
            return Collections.emptyList();
        }

        Deque<ConversationMessage> deque = storage.get(sessionId);
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }

        List<ConversationMessage> snapshot;
        synchronized (deque) {
            snapshot = new ArrayList<>(deque);
        }

        int fromIndex = Math.max(0, snapshot.size() - lastN);
        return Collections.unmodifiableList(snapshot.subList(fromIndex, snapshot.size()));
    }

    @Override
    public List<ConversationMessage> getAllMessages(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }

        Deque<ConversationMessage> deque = storage.get(sessionId);
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }

        synchronized (deque) {
            return Collections.unmodifiableList(new ArrayList<>(deque));
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
        Deque<ConversationMessage> deque = storage.get(sessionId);
        return deque != null ? deque.size() : 0;
    }
}
