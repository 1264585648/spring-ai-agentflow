package com.example.springai.scenario.troubleshoot;

import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.tool.DatabaseDiagnoseTool;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.tool.LogQueryTool;
import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.tool.OpsActionTool;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.event.AgentEventSink;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.infra.client.AgentChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TroubleshootSubAgentTest {

    private TroubleshootSubAgent troubleshootSubAgent;
    private AgentEventSink mockSink;

    @BeforeEach
    void setUp() {
        AgentChatClientFactory chatClientFactory = mock(AgentChatClientFactory.class);
        MockLogAdapter logAdapter = new MockLogAdapter();
        MockDbAdapter dbAdapter = new MockDbAdapter();
        MockOpsActionAdapter opsAdapter = new MockOpsActionAdapter();

        troubleshootSubAgent = new TroubleshootSubAgent(
                chatClientFactory,
                logAdapter,
                dbAdapter,
                new LogQueryTool(logAdapter),
                new DatabaseDiagnoseTool(dbAdapter),
                new OpsActionTool(opsAdapter),
                new TroubleshootCardFactory()
        );

        mockSink = mock(AgentEventSink.class);
        when(mockSink.progress(anyString(), anyString())).thenReturn(true);
        when(mockSink.message(anyString())).thenReturn(true);
        when(mockSink.card(any())).thenReturn(true);
        when(mockSink.recommend(anyList())).thenReturn(true);
    }

    @Test
    @DisplayName("测试 TroubleshootSubAgent 支持的智能体角色编码")
    void testSupports() {
        assertTrue(troubleshootSubAgent.supports("LOG_DIAGNOSE_AGENT"));
        assertTrue(troubleshootSubAgent.supports("DB_DIAGNOSE_AGENT"));
        assertTrue(troubleshootSubAgent.supports("SRE_COPILOT_AGENT"));
        assertTrue(troubleshootSubAgent.supports("TROUBLESHOOT_AGENT"));
        assertFalse(troubleshootSubAgent.supports("UNKNOWN_AGENT"));
        assertFalse(troubleshootSubAgent.supports(null));
    }

    @Test
    @DisplayName("测试 TroubleshootSubAgent 协同执行、兜底分析与卡片下发")
    void testExecuteFallbackAndCard() {
        AgentContext context = AgentContext.builder()
                .sessionId("sess_ts_001")
                .turnId("turn_001")
                .query("order-service 504 报警排查")
                .sink(mockSink)
                .build();

        DispatchStep step = DispatchStep.builder()
                .stepOrder(1)
                .targetAgent("LOG_DIAGNOSE_AGENT")
                .targetAgentName("日志分析专家")
                .taskDesc("提取异常日志并研判根因")
                .inputParams(Map.of("service", "order-service"))
                .build();

        troubleshootSubAgent.execute(context, step);

        verify(mockSink).progress(eq("AGENT_DISPATCH"), contains("日志分析专家"));
        verify(mockSink, atLeastOnce()).message(anyString());

        ArgumentCaptor<InteractiveCard> cardCaptor = ArgumentCaptor.forClass(InteractiveCard.class);
        verify(mockSink).card(cardCaptor.capture());
        InteractiveCard card = cardCaptor.getValue();
        assertNotNull(card);
        assertEquals("TROUBLESHOOT_ACTION", card.getCardType());
        assertTrue(card.getFields().stream().anyMatch(f -> "service".equals(f.getFieldKey()) && "order-service".equals(f.getValue())));

        ArgumentCaptor<List<String>> recommendCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockSink).recommend(recommendCaptor.capture());
        assertFalse(recommendCaptor.getValue().isEmpty());
    }

    @Test
    @DisplayName("测试 Spring AI 原生 .stream() 响应式真流式 Token 推送与协同卡片 (M2)")
    void testExecuteTrueStreamingSuccess() {
        org.springframework.ai.chat.client.ChatClient mockClient = mock(org.springframework.ai.chat.client.ChatClient.class);
        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec mockSpec = mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        org.springframework.ai.chat.client.ChatClient.StreamResponseSpec mockStreamSpec = mock(org.springframework.ai.chat.client.ChatClient.StreamResponseSpec.class);

        AgentChatClientFactory factory = mock(AgentChatClientFactory.class);
        when(factory.createClient(anyString(), any(), any(), any())).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockSpec);
        when(mockSpec.user(anyString())).thenReturn(mockSpec);
        when(mockSpec.stream()).thenReturn(mockStreamSpec);
        when(mockStreamSpec.content()).thenReturn(reactor.core.publisher.Flux.just("Token1 ", "Token2 ", "Token3"));

        TroubleshootSubAgent streamingAgent = new TroubleshootSubAgent(
                factory,
                new MockLogAdapter(),
                new MockDbAdapter(),
                new LogQueryTool(new MockLogAdapter()),
                new DatabaseDiagnoseTool(new MockDbAdapter()),
                new OpsActionTool(new MockOpsActionAdapter()),
                new TroubleshootCardFactory()
        );

        AgentContext context = AgentContext.builder()
                .sessionId("sess_ts_stream")
                .turnId("turn_002")
                .query("order-service 504 异常分析")
                .sink(mockSink)
                .build();

        DispatchStep step = DispatchStep.builder()
                .stepOrder(1)
                .targetAgent("LOG_DIAGNOSE_AGENT")
                .targetAgentName("日志分析专家")
                .taskDesc("流式诊断日志")
                .inputParams(Map.of("service", "order-service"))
                .build();

        streamingAgent.execute(context, step);

        verify(mockSink).message("Token1 ");
        verify(mockSink).message("Token2 ");
        verify(mockSink).message("Token3");
        verify(mockSink).card(any());
        verify(mockSink).recommend(anyList());
    }

    @Test
    @DisplayName("测试客户端断连快速熔断：SSE 发送失败时提前中止大模型流式输出与后续卡片 (M2)")
    void testClientDisconnectFastAbort() {
        org.springframework.ai.chat.client.ChatClient mockClient = mock(org.springframework.ai.chat.client.ChatClient.class);
        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec mockSpec = mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        org.springframework.ai.chat.client.ChatClient.StreamResponseSpec mockStreamSpec = mock(org.springframework.ai.chat.client.ChatClient.StreamResponseSpec.class);

        AgentChatClientFactory factory = mock(AgentChatClientFactory.class);
        when(factory.createClient(anyString(), any(), any(), any())).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockSpec);
        when(mockSpec.user(anyString())).thenReturn(mockSpec);
        when(mockSpec.stream()).thenReturn(mockStreamSpec);
        when(mockStreamSpec.content()).thenReturn(reactor.core.publisher.Flux.just("Token1", "Token2", "Token3"));

        AgentEventSink disconnectSink = mock(AgentEventSink.class);
        when(disconnectSink.progress(anyString(), anyString())).thenReturn(true);
        when(disconnectSink.message("Token1")).thenReturn(true);
        when(disconnectSink.message("Token2")).thenReturn(false);

        TroubleshootSubAgent streamingAgent = new TroubleshootSubAgent(
                factory,
                new MockLogAdapter(),
                new MockDbAdapter(),
                new LogQueryTool(new MockLogAdapter()),
                new DatabaseDiagnoseTool(new MockDbAdapter()),
                new OpsActionTool(new MockOpsActionAdapter()),
                new TroubleshootCardFactory()
        );

        AgentContext context = AgentContext.builder()
                .sessionId("sess_ts_dc")
                .turnId("turn_003")
                .query("order-service 504 异常分析")
                .sink(disconnectSink)
                .build();

        DispatchStep step = DispatchStep.builder()
                .stepOrder(1)
                .targetAgent("LOG_DIAGNOSE_AGENT")
                .targetAgentName("日志分析专家")
                .taskDesc("流式诊断日志")
                .inputParams(Map.of("service", "order-service"))
                .build();

        streamingAgent.execute(context, step);

        verify(disconnectSink, never()).message("Token3");
        verify(disconnectSink, never()).card(any());
        verify(disconnectSink, never()).recommend(anyList());
    }
}
