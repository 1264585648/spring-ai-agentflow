package com.example.springai.core.pipeline.impl;

import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.port.DatabaseDiagnosePort;
import com.example.springai.capability.database.tool.DatabaseDiagnoseTool;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.port.LogQueryPort;
import com.example.springai.capability.log.tool.LogQueryTool;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.agent.GeneralChatAgent;
import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.memory.ConversationMemory;
import com.example.springai.core.memory.impl.InMemoryConversationMemory;
import com.example.springai.core.routing.IntentMatchResult;
import com.example.springai.core.routing.IntentResolver;
import com.example.springai.core.routing.L1RuleMatcher;
import com.example.springai.core.routing.MasterAgentRouter;
import com.example.springai.core.routing.impl.L1RuleResolver;
import com.example.springai.core.routing.impl.L2VectorResolver;
import com.example.springai.core.routing.impl.L3MasterAgentResolver;
import com.example.springai.core.routing.impl.LlmQueryRewriter;
import com.example.springai.core.tool.L1ToolDispatcher;
import com.example.springai.infra.client.AgentChatClientFactory;
import com.example.springai.infra.sse.SseEventPublisher;
import com.example.springai.scenario.troubleshoot.TroubleshootCardFactory;
import com.example.springai.scenario.troubleshoot.TroubleshootSubAgent;
import com.example.springai.web.dto.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MultiTurnPipelineIntegrationTest {

    private SseEventPublisher ssePublisher;
    private L1RuleMatcher l1RuleMatcher;
    private L1ToolDispatcher l1ToolDispatcher;
    private AgentChatClientFactory chatClientFactory;
    private MasterAgentRouter masterAgentRouter;
    private TroubleshootSubAgent troubleshootSubAgent;
    private GeneralChatAgent generalChatAgent;
    private CardRegistry cardRegistry;
    private ConversationMemory conversationMemory;
    private LlmQueryRewriter queryRewriter;
    private Executor directExecutor;

    private AgentPipelineServiceImpl pipelineService;

    @BeforeEach
    void setUp() {
        ssePublisher = mock(SseEventPublisher.class);
        when(ssePublisher.sendThinking(anyString(), anyString())).thenReturn(true);
        when(ssePublisher.sendProgress(anyString(), anyString(), anyString())).thenReturn(true);
        when(ssePublisher.sendMessage(anyString(), anyString())).thenReturn(true);
        when(ssePublisher.sendInteractiveCard(anyString(), any())).thenReturn(true);
        when(ssePublisher.sendRecommendQuestions(anyString(), any())).thenReturn(true);
        when(ssePublisher.sendDone(anyString())).thenReturn(true);

        l1RuleMatcher = mock(L1RuleMatcher.class);
        l1ToolDispatcher = mock(L1ToolDispatcher.class);
        chatClientFactory = mock(AgentChatClientFactory.class);

        // 真实智能体注册中心与路由
        AgentPromptRegistry promptRegistry = new AgentPromptRegistry();
        masterAgentRouter = new MasterAgentRouter(promptRegistry);

        LogQueryPort logPort = new MockLogAdapter();
        DatabaseDiagnosePort dbPort = new MockDbAdapter();
        troubleshootSubAgent = new TroubleshootSubAgent(
                chatClientFactory,
                logPort,
                dbPort,
                new LogQueryTool(logPort),
                new DatabaseDiagnoseTool(dbPort),
                new TroubleshootCardFactory()
        );
        generalChatAgent = new GeneralChatAgent(chatClientFactory);

        cardRegistry = new InMemoryCardRegistry();
        conversationMemory = new InMemoryConversationMemory(10);
        queryRewriter = new LlmQueryRewriter(chatClientFactory);
        directExecutor = Runnable::run;

        List<IntentResolver> resolvers = List.of(
                new L1RuleResolver(l1RuleMatcher),
                new L2VectorResolver(),
                new L3MasterAgentResolver(masterAgentRouter)
        );

        pipelineService = new AgentPipelineServiceImpl(
                ssePublisher,
                resolvers,
                l1ToolDispatcher,
                List.of(troubleshootSubAgent),
                generalChatAgent,
                cardRegistry,
                conversationMemory,
                queryRewriter,
                directExecutor
        );
    }

    @Test
    @DisplayName("测试多轮对话完整链路：首轮建立记忆 ➔ 次轮指代补齐 ➔ 指令绕过改写")
    void testMultiTurnConversationLifecycle() {
        String sessionId = "sess-multiturn-888";

        // ==========================================
        // 第一轮：提问 order-service 异常，建立第一轮记忆
        // ==========================================
        ChatRequest req1 = new ChatRequest();
        req1.setSessionId(sessionId);
        req1.setQuery("order-service 最近经常出现 504 Gateway Timeout");

        pipelineService.process(req1);

        // 验证第一轮问答已被完整归档入 ConversationMemory (1 条 USER + 1 条 ASSISTANT)
        assertEquals(2, conversationMemory.size(sessionId));
        assertEquals("order-service 最近经常出现 504 Gateway Timeout",
                conversationMemory.getRecentMessages(sessionId, 2).get(0).getContent());

        // ==========================================
        // 第二轮：追问“把它的错误日志发我看看” (指代模糊)
        // 期望：通过 QueryRewriter 将“它”自动补齐为“order-service”，由 TroubleshootSubAgent 承接
        // ==========================================
        ChatRequest req2 = new ChatRequest();
        req2.setSessionId(sessionId);
        req2.setQuery("把它的错误日志发我看看");

        pipelineService.process(req2);

        // 验证思考面板通知了用户已完成指代补全
        ArgumentCaptor<String> thinkingCaptor = ArgumentCaptor.forClass(String.class);
        verify(ssePublisher, atLeastOnce()).sendThinking(eq(sessionId), thinkingCaptor.capture());
        boolean hasRewriteNotice = thinkingCaptor.getAllValues().stream()
                .anyMatch(msg -> msg.contains("检测到多轮上下文") && msg.contains("order-service"));
        assertTrue(hasRewriteNotice, "应当向用户发出问题已补全的 thinking 通知");

        // 验证第二轮问答追加归档，当前记忆池共 4 条消息
        assertEquals(4, conversationMemory.size(sessionId));

        // ==========================================
        // 第三轮：输入 L1 快捷指令 /help
        // 期望：极速绕行，跳过改写，直接命中 L1
        // ==========================================
        when(l1RuleMatcher.match("/help")).thenReturn(
                IntentMatchResult.hitL1("CMD_HELP", "帮助", "STATIC_TEXT", null, null, "系统帮助手册", 1L)
        );

        ChatRequest req3 = new ChatRequest();
        req3.setSessionId(sessionId);
        req3.setQuery("/help");

        pipelineService.process(req3);

        verify(l1RuleMatcher, times(1)).match("/help");
    }
}
