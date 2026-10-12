package com.example.springai.core.memory.impl;

import com.example.springai.core.memory.ConversationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryConversationMemoryTest {

    private InMemoryConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryConversationMemory(5); // 容量设置为 5 条
    }

    @Test
    @DisplayName("测试消息追加、大小与正序提取")
    void testAppendAndGetRecentMessages() {
        String sessionId = "sess-01";
        memory.append(sessionId, ConversationMessage.user("你好"));
        memory.append(sessionId, ConversationMessage.assistant("你好！请问有什么可以帮您？"));
        memory.append(sessionId, ConversationMessage.user("查看系统状态"));

        assertEquals(3, memory.size(sessionId));

        List<ConversationMessage> recent = memory.getRecentMessages(sessionId, 2);
        assertEquals(2, recent.size());
        assertEquals("你好！请问有什么可以帮您？", recent.get(0).getContent());
        assertEquals("查看系统状态", recent.get(1).getContent());

        List<ConversationMessage> all = memory.getAllMessages(sessionId);
        assertEquals(3, all.size());
        assertEquals("你好", all.get(0).getContent());
    }

    @Test
    @DisplayName("测试便捷追加问答交互 appendExchange")
    void testAppendExchange() {
        String sessionId = "sess-exchange";
        memory.appendExchange(sessionId, "服务挂了", "正在排障");

        assertEquals(2, memory.size(sessionId));
        List<ConversationMessage> all = memory.getAllMessages(sessionId);
        assertEquals(ConversationMessage.Role.USER, all.get(0).getRole());
        assertEquals("服务挂了", all.get(0).getContent());
        assertEquals(ConversationMessage.Role.ASSISTANT, all.get(1).getRole());
        assertEquals("正在排障", all.get(1).getContent());
    }

    @Test
    @DisplayName("测试滑动窗口容量限制与 FIFO 最早历史淘汰")
    void testSlidingWindowCapacityEviction() {
        String sessionId = "sess-sliding";
        for (int i = 1; i <= 8; i++) {
            memory.append(sessionId, ConversationMessage.user("问题 " + i));
        }

        // 容量上限为 5，因此前 3 条被淘汰，保留 4 ~ 8
        assertEquals(5, memory.size(sessionId));
        List<ConversationMessage> all = memory.getAllMessages(sessionId);
        assertEquals(5, all.size());
        assertEquals("问题 4", all.get(0).getContent());
        assertEquals("问题 8", all.get(4).getContent());
    }

    @Test
    @DisplayName("测试清空会话记忆 clear")
    void testClear() {
        String sessionId = "sess-clear";
        memory.appendExchange(sessionId, "Q1", "A1");
        assertEquals(2, memory.size(sessionId));

        memory.clear(sessionId);
        assertEquals(0, memory.size(sessionId));
        assertTrue(memory.getAllMessages(sessionId).isEmpty());
    }

    @Test
    @DisplayName("测试并发追加的线程安全性")
    void testConcurrentAppends() throws InterruptedException {
        String sessionId = "sess-concurrent";
        InMemoryConversationMemory largeMemory = new InMemoryConversationMemory(200);

        int threads = 10;
        int operationsPerThread = 15;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        largeMemory.append(sessionId, ConversationMessage.user("T" + threadIdx + "-M" + i));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        executor.shutdown();

        // 10 * 15 = 150 条消息，均在 200 容量范围内
        assertEquals(150, largeMemory.size(sessionId));
    }
}
