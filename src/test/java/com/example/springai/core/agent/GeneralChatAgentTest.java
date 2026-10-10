package com.example.springai.core.agent;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.event.AgentEventSink;
import com.example.springai.infra.client.AgentChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeneralChatAgentTest {

    private AgentChatClientFactory chatClientFactory;
    private GeneralChatAgent generalChatAgent;
    private AgentEventSink mockSink;

    @BeforeEach
    void setUp() {
        chatClientFactory = mock(AgentChatClientFactory.class);
        generalChatAgent = new GeneralChatAgent(chatClientFactory);
        mockSink = mock(AgentEventSink.class);
        when(mockSink.progress(anyString(), anyString())).thenReturn(true);
        when(mockSink.message(anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("测试 GeneralChatAgent 支持的角色编码与名称")
    void testSupportsAndName() {
        assertEquals("GENERAL_AGENT", generalChatAgent.name());
        assertTrue(generalChatAgent.supports("GENERAL_AGENT"));
        assertTrue(generalChatAgent.supports("DEFAULT_AGENT"));
        assertTrue(generalChatAgent.supports(null));
        assertFalse(generalChatAgent.supports("LOG_DIAGNOSE_AGENT"));
    }

    @Test
    @DisplayName("测试 GeneralChatAgent 响应式真流式 Token 推送 (M2)")
    void testExecuteStreamingSuccess() {
        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec mockStreamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClientFactory.createClient("GENERAL_AGENT")).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockSpec);
        when(mockSpec.user("你好")).thenReturn(mockSpec);
        when(mockSpec.stream()).thenReturn(mockStreamSpec);
        when(mockStreamSpec.content()).thenReturn(Flux.just("您好，", "很高兴", "为您服务！"));

        AgentContext context = AgentContext.builder()
                .sessionId("sess_gen_1")
                .turnId("turn_gen_1")
                .query("你好")
                .sink(mockSink)
                .build();

        generalChatAgent.execute(context, null);

        verify(mockSink).progress(eq("AGENT_DISPATCH"), contains("通用协同专家"));
        verify(mockSink).message("您好，");
        verify(mockSink).message("很高兴");
        verify(mockSink).message("为您服务！");
    }

    @Test
    @DisplayName("测试 GeneralChatAgent 无 Key 或大模型异常时平滑降级 (M2)")
    void testExecuteFallbackWhenException() {
        when(chatClientFactory.createClient("GENERAL_AGENT"))
                .thenThrow(new RuntimeException("OpenAI API-Key 未配置"));

        AgentContext context = AgentContext.builder()
                .sessionId("sess_gen_2")
                .turnId("turn_gen_2")
                .query("请问系统的核心架构是什么？")
                .sink(mockSink)
                .build();

        generalChatAgent.execute(context, null);

        verify(mockSink).progress(eq("AGENT_DISPATCH"), contains("通用协同专家"));
        verify(mockSink, atLeastOnce()).message(anyString());
    }

    @Test
    @DisplayName("测试 GeneralChatAgent 客户端断连熔断 (M2)")
    void testClientDisconnectFastAbort() {
        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec mockStreamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClientFactory.createClient("GENERAL_AGENT")).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockSpec);
        when(mockSpec.user(anyString())).thenReturn(mockSpec);
        when(mockSpec.stream()).thenReturn(mockStreamSpec);
        when(mockStreamSpec.content()).thenReturn(Flux.just("TokenA", "TokenB", "TokenC"));

        AgentEventSink disconnectSink = mock(AgentEventSink.class);
        when(disconnectSink.progress(anyString(), anyString())).thenReturn(true);
        when(disconnectSink.message("TokenA")).thenReturn(true);
        when(disconnectSink.message("TokenB")).thenReturn(false);

        AgentContext context = AgentContext.builder()
                .sessionId("sess_gen_3")
                .turnId("turn_gen_3")
                .query("测试断连")
                .sink(disconnectSink)
                .build();

        generalChatAgent.execute(context, null);

        verify(disconnectSink).message("TokenA");
        verify(disconnectSink).message("TokenB");
        verify(disconnectSink, never()).message("TokenC");
    }
}
