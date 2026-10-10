package com.example.springai.pipeline.impl;

import com.example.springai.api.dto.ChatRequest;
import com.example.springai.card.model.InteractiveCard;
import com.example.springai.execution.sse.SseEventPublisher;
import com.example.springai.pipeline.agent.AgentChatClientFactory;
import com.example.springai.pipeline.agent.MasterAgentRouter;
import com.example.springai.pipeline.agent.dto.DispatchPlan;
import com.example.springai.pipeline.dispatcher.L1ToolDispatcher;
import com.example.springai.pipeline.intent.IntentMatchResult;
import com.example.springai.pipeline.intent.L1RuleMatcher;
import com.example.springai.tool.TroubleshootTool;
import com.example.springai.troubleshoot.mock.MockActionAdapter;
import com.example.springai.troubleshoot.mock.MockDbAdapter;
import com.example.springai.troubleshoot.mock.MockLogAdapter;
import com.example.springai.troubleshoot.port.DatabaseDiagnosePort;
import com.example.springai.troubleshoot.port.LogQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentPipelineServiceImplTest {

    private SseEventPublisher ssePublisher;
    private L1RuleMatcher l1RuleMatcher;
    private L1ToolDispatcher l1ToolDispatcher;
    private AgentChatClientFactory chatClientFactory;
    private MasterAgentRouter masterAgentRouter;
    private LogQueryPort logQueryPort;
    private DatabaseDiagnosePort databaseDiagnosePort;
    private TroubleshootTool troubleshootTool;
    private Executor directExecutor;

    private AgentPipelineServiceImpl pipelineService;

    @BeforeEach
    void setUp() {
        ssePublisher = mock(SseEventPublisher.class);
        when(ssePublisher.sendThinking(anyString(), anyString())).thenReturn(true);
        when(ssePublisher.sendProgress(anyString(), anyString(), anyString())).thenReturn(true);
        when(ssePublisher.sendMessage(anyString(), anyString())).thenReturn(true);
        when(ssePublisher.sendInteractiveCard(anyString(), any())).thenReturn(true);
        when(ssePublisher.sendRecommendQuestions(anyString(), anyList())).thenReturn(true);
        when(ssePublisher.sendDone(anyString())).thenReturn(true);

        l1RuleMatcher = mock(L1RuleMatcher.class);
        l1ToolDispatcher = mock(L1ToolDispatcher.class);
        chatClientFactory = mock(AgentChatClientFactory.class);
        masterAgentRouter = mock(MasterAgentRouter.class);

        logQueryPort = new MockLogAdapter();
        databaseDiagnosePort = new MockDbAdapter();
        troubleshootTool = new TroubleshootTool(logQueryPort, databaseDiagnosePort, new MockActionAdapter());

        // 使用同步直接执行器以确保单测可预测完成
        directExecutor = Runnable::run;

        pipelineService = new AgentPipelineServiceImpl(
                ssePublisher,
                l1RuleMatcher,
                l1ToolDispatcher,
                chatClientFactory,
                masterAgentRouter,
                logQueryPort,
                databaseDiagnosePort,
                troubleshootTool,
                directExecutor
        );
    }

    @Test
    @DisplayName("测试命中 L1 静态指令直出快捷响应")
    void testL1StaticTextHit() {
        when(l1RuleMatcher.match("#ping")).thenReturn(
                IntentMatchResult.hitL1("CMD_SYS_PING", "探活", "STATIC_TEXT", null, null, "🏓 PONG!", 1L)
        );

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess_101");
        request.setQuery("#ping");
        pipelineService.process(request);

        verify(ssePublisher).sendProgress(eq("sess_101"), eq("L1_HIT"), anyString());
        verify(ssePublisher, atLeastOnce()).sendMessage(eq("sess_101"), anyString());
        verify(ssePublisher).sendDone("sess_101");
        verify(chatClientFactory, never()).createClient(anyString(), any());
    }

    @Test
    @DisplayName("测试命中 L1 命令直通工具调度")
    void testL1ToolDispatchHit() {
        when(l1RuleMatcher.match("/query user_id=1001")).thenReturn(
                IntentMatchResult.hitL1("CMD_QUERY_ACCOUNT", "查账", "TOOL", "userAccountTool.queryBalance", "{\"userId\": \"1001\"}", null, 2L)
        );
        when(l1ToolDispatcher.dispatch(eq("userAccountTool.queryBalance"), anyString()))
                .thenReturn("💰 账户余额: 1000.00");

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess_102");
        request.setQuery("/query user_id=1001");
        pipelineService.process(request);

        verify(ssePublisher).sendProgress(eq("sess_102"), eq("L1_TOOL"), contains("userAccountTool.queryBalance"));
        verify(l1ToolDispatcher).dispatch(eq("userAccountTool.queryBalance"), anyString());
        verify(ssePublisher, atLeastOnce()).sendMessage(eq("sess_102"), anyString());
        verify(ssePublisher).sendDone("sess_102");
    }

    @Test
    @DisplayName("测试未命中 L1 时进入排障协同流水线并下发 TROUBLESHOOT_ACTION 卡片")
    void testNonL1EntersTroubleshootingPipelineAndEmitsCard() {
        when(l1RuleMatcher.match(anyString())).thenReturn(IntentMatchResult.miss());

        DispatchPlan mockPlan = DispatchPlan.single(
                "LOG_DIAGNOSE_AGENT",
                "日志异常分析智能体",
                "检索服务异常日志并分析报错根因",
                Map.of("service", "order-service")
        );
        when(masterAgentRouter.route(anyString())).thenReturn(mockPlan);

        String query = "order-service 出现大量 504 错误，请协助排查";
        ChatRequest request = new ChatRequest();
        request.setSessionId("sess_103");
        request.setQuery(query);

        pipelineService.process(request);

        // 1. 验证下发思考链
        verify(ssePublisher).sendThinking(eq("sess_103"), contains("MasterAgent 正在分析排障诉求"));

        // 2. 验证智能体委派进度
        verify(ssePublisher).sendProgress(eq("sess_103"), eq("AGENT_DISPATCH"), contains("日志异常分析智能体"));

        // 3. 验证文本流式输出 (包含降级时从 MockLogAdapter / MockDbAdapter 提取的排障结论)
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(ssePublisher, atLeastOnce()).sendMessage(eq("sess_103"), messageCaptor.capture());
        String fullResponse = String.join("", messageCaptor.getAllValues());
        System.out.println("【DEBUG fullResponse】: " + fullResponse);
        assertTrue(fullResponse.contains("排障") || fullResponse.contains("order-service"));

        // 4. 验证交互卡片挂载
        ArgumentCaptor<InteractiveCard> cardCaptor = ArgumentCaptor.forClass(InteractiveCard.class);
        verify(ssePublisher).sendInteractiveCard(eq("sess_103"), cardCaptor.capture());
        InteractiveCard card = cardCaptor.getValue();
        assertNotNull(card);
        assertEquals("TROUBLESHOOT_ACTION", card.getCardType());
        assertTrue(card.getTitle().contains("应急止血"));
        assertTrue(card.getFields().stream().anyMatch(f -> "service".equals(f.getFieldKey()) && "order-service".equals(f.getValue())));
        assertTrue(card.getFields().stream().anyMatch(f -> "action_type".equals(f.getFieldKey())));
        assertTrue(card.getFields().stream().anyMatch(f -> "target_identifier".equals(f.getFieldKey())));

        // 5. 验证推荐问题
        ArgumentCaptor<List<String>> questionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ssePublisher).sendRecommendQuestions(eq("sess_103"), questionsCaptor.capture());
        List<String> questions = questionsCaptor.getValue();
        assertNotNull(questions);
        assertTrue(questions.stream().anyMatch(q -> q.contains("Trace")));

        // 6. 验证结束包
        verify(ssePublisher).sendDone("sess_103");
    }
}
