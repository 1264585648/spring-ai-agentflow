package com.example.springai.core.routing.impl;

import com.example.springai.core.agent.AgentType;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.memory.ConversationMessage;
import com.example.springai.infra.client.AgentChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LlmQueryRewriterTest {

    private AgentChatClientFactory chatClientFactory;
    private LlmQueryRewriter queryRewriter;

    @BeforeEach
    void setUp() {
        chatClientFactory = mock(AgentChatClientFactory.class);
        queryRewriter = new LlmQueryRewriter(chatClientFactory);
    }

    @Test
    @DisplayName("测试历史记录为空或提问为空时直接返回原 Query")
    void testEmptyHistoryReturnsRawQuery() {
        AgentContext context = AgentContext.builder()
                .sessionId("sess-1")
                .query("查一下状态")
                .build();

        String result = queryRewriter.rewrite(context, Collections.emptyList());
        assertEquals("查一下状态", result);
        verifyNoInteractions(chatClientFactory);
    }

    @Test
    @DisplayName("测试 L1 极速指令前缀（/ 和 #）直接跳过改写，绝不增加 LLM 时延")
    void testL1CommandPrefixBypassesRewrite() {
        AgentContext context1 = AgentContext.builder()
                .sessionId("sess-1")
                .query("/help")
                .build();
        AgentContext context2 = AgentContext.builder()
                .sessionId("sess-1")
                .query("#ping")
                .build();

        List<ConversationMessage> history = List.of(
                ConversationMessage.user("order-service 挂了"),
                ConversationMessage.assistant("已查明原因")
        );

        assertEquals("/help", queryRewriter.rewrite(context1, history));
        assertEquals("#ping", queryRewriter.rewrite(context2, history));
        verifyNoInteractions(chatClientFactory);
    }

    @Test
    @DisplayName("测试大模型正常响应时的改写与前缀清洗")
    void testLlmSuccessfulRewrite() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClientFactory.createClient(AgentType.QUERY_REWRITER)).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("改写结果：“order-service 的慢查询日志”");

        AgentContext context = AgentContext.builder()
                .sessionId("sess-1")
                .query("它的慢查询日志")
                .build();

        List<ConversationMessage> history = List.of(
                ConversationMessage.user("order-service 504 报警了"),
                ConversationMessage.assistant("正在排查 order-service")
        );

        String result = queryRewriter.rewrite(context, history);
        assertEquals("order-service 的慢查询日志", result);
    }

    @Test
    @DisplayName("测试离线或大模型异常时平滑降级至自适应启发式实体补齐")
    void testOfflineHeuristicFallback() {
        // 模拟 LLM 抛出异常（例如离线单测或无 API-Key 环境）
        when(chatClientFactory.createClient(AgentType.QUERY_REWRITER))
                .thenThrow(new IllegalStateException("No API Key configured"));

        AgentContext context = AgentContext.builder()
                .sessionId("sess-offline")
                .query("把它的错误日志发我看看")
                .build();

        List<ConversationMessage> history = List.of(
                ConversationMessage.user("payment-service 接口响应超时"),
                ConversationMessage.assistant("收到，已定位到 payment-service 服务堆栈异常")
        );

        String result = queryRewriter.rewrite(context, history);
        // 离线启发式应将“它”自动替换为最近历史中出现的“payment-service”
        assertEquals("把payment-service的错误日志发我看看", result);
    }
}
