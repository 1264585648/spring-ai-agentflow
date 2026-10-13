package com.example.springai.capability.tool;

import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.port.DatabaseDiagnosePort;
import com.example.springai.capability.database.tool.DatabaseDiagnoseTool;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.port.LogQueryPort;
import com.example.springai.capability.log.tool.LogQueryTool;
import com.example.springai.capability.ops.card.OpsActionCardSubmitHandler;
import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.port.OpsActionPort;
import com.example.springai.capability.ops.tool.OpsActionTool;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.state.CardState;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.event.AgentEventSink;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.tool.L1ToolCatalog;
import com.example.springai.core.tool.L1ToolDispatcher;
import com.example.springai.infra.client.AgentChatClientFactory;
import com.example.springai.infra.sse.SseEventPublisher;
import com.example.springai.scenario.troubleshoot.TroubleshootCardFactory;
import com.example.springai.scenario.troubleshoot.TroubleshootSubAgent;
import com.example.springai.web.controller.CardInteractionController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import reactor.core.publisher.Flux;

/**
 * 使用真实 Spring 工具目录和 ChatClient，只替换外部模型及能力适配器。
 * 这是已知直接写工具旁路的回归测试，不是完整审批安全或完整 Boot 装配验收。
 */
@Timeout(20)
class DirectWriteToolBoundaryTest {

    private static final String WRITE_REF = "opsActionTool.executeEmergencyAction";
    private static final String ANALYSIS = "分析完成，请核验演示卡片。";

    private AnnotationConfigApplicationContext applicationContext;
    private ChatModel chatModel;
    private OpsActionPort opsActionPort;
    private AgentEventSink sink;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        when(chatModel.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(ANALYSIS))))));

        // spy 的底层也是纯 Mock 适配器，不连接任何真实运维系统。
        opsActionPort = spy(new MockOpsActionAdapter());
        AgentPromptRegistry promptRegistry = mock(AgentPromptRegistry.class);
        when(promptRegistry.getSystemPrompt(anyString())).thenReturn("你是只提供分析和建议的排障助手。");

        sink = mock(AgentEventSink.class);
        when(sink.progress(anyString(), anyString())).thenReturn(true);
        when(sink.message(anyString())).thenReturn(true);
        when(sink.card(any())).thenReturn(true);
        when(sink.recommend(anyList())).thenReturn(true);

        applicationContext = new AnnotationConfigApplicationContext();
        applicationContext.registerBean(LogQueryPort.class, MockLogAdapter::new);
        applicationContext.registerBean(DatabaseDiagnosePort.class, MockDbAdapter::new);
        applicationContext.registerBean(OpsActionPort.class, () -> opsActionPort);
        applicationContext.registerBean(AgentChatClientFactory.class,
                () -> new AgentChatClientFactory(ChatClient.builder(chatModel), promptRegistry));
        // 保留真实写辅助 Bean，证明拒绝不是因为测试没有注册它。
        applicationContext.register(LogQueryTool.class, DatabaseDiagnoseTool.class, OpsActionTool.class,
                L1ToolCatalog.class, L1ToolDispatcher.class,
                TroubleshootCardFactory.class, TroubleshootSubAgent.class);
        applicationContext.refresh();
    }

    @AfterEach
    void tearDown() {
        if (applicationContext != null) {
            applicationContext.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOG_DIAGNOSE_AGENT", "DB_DIAGNOSE_AGENT", "SRE_COPILOT_AGENT", "TROUBLESHOOT_AGENT"})
    @DisplayName("所有排障角色实际发送给模型的工具定义均不包含写方法")
    void modelRequestContainsOnlyQueryTools(String agentCode) {
        applicationContext.getBean(TroubleshootSubAgent.class).execute(newContext(), newStep(agentCode));

        // 在模型边界捕获真实 Prompt，不 mock 工厂或只检查传入工厂的参数。
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());
        ToolCallingChatOptions options = assertInstanceOf(ToolCallingChatOptions.class,
                captor.getValue().getOptions());
        List<ToolCallback> callbacks = options.getToolCallbacks();
        assertNotNull(callbacks);
        assertEquals(List.of("diagnoseDatabase", "queryServiceLogs"), callbacks.stream()
                .map(callback -> callback.getToolDefinition().name()).sorted().toList());

        // 正向对照：保留下来的真实 ToolCallback 仍可调用 Mock 查询能力。
        assertTrue(callback(callbacks, "queryServiceLogs").call(
                "{\"service\":\"order-service\",\"keyword\":\"504\",\"logLevel\":\"ERROR\"}")
                .contains("HikariPool"));
        assertTrue(callback(callbacks, "diagnoseDatabase").call(
                "{\"service\":\"order-service\",\"slowThresholdMs\":1000}")
                .contains("trx_10423"));

        // 必须真正走到模型输出；不能让异常后的 Mock 兜底掩盖工具装配失败。
        verify(sink).message(ANALYSIS);
        verify(sink).card(any(InteractiveCard.class));
        verify(sink).recommend(anyList());
        verifyNoInteractions(opsActionPort);
    }

    @Test
    @DisplayName("Spring 扫描后即使仍有写辅助 Bean，L1 也拒绝旧的写目标引用")
    void l1RejectsDirectWriteWithoutInvokingPort() {
        assertTrue(applicationContext.containsBean("opsActionTool"));
        L1ToolCatalog catalog = applicationContext.getBean(L1ToolCatalog.class);
        L1ToolDispatcher dispatcher = applicationContext.getBean(L1ToolDispatcher.class);
        assertFalse(catalog.isRegistered(WRITE_REF));

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch(WRITE_REF, """
                        {"service":"order-service","actionType":"KILL_SLOW_SESSION",
                         "targetIdentifier":"trx_10423","operator":"model-supplied"}
                        """));
        assertTrue(rejected.getMessage().contains("不是已注册的 @Tool 方法"));
        verifyNoInteractions(opsActionPort);
    }

    @Test
    @DisplayName("L1 查询直通仍正常，不通过禁用整个目录来阻断写操作")
    void l1QueryToolsStillWork() {
        L1ToolCatalog catalog = applicationContext.getBean(L1ToolCatalog.class);
        L1ToolDispatcher dispatcher = applicationContext.getBean(L1ToolDispatcher.class);
        assertTrue(catalog.isRegistered("logQueryTool.queryServiceLogs"));
        assertTrue(catalog.isRegistered("databaseDiagnoseTool.diagnoseDatabase"));
        assertTrue(dispatcher.dispatch("logQueryTool.queryServiceLogs",
                "{\"service\":\"order-service\",\"keyword\":\"504\",\"logLevel\":\"ERROR\"}")
                .contains("HikariPool"));
        assertTrue(dispatcher.dispatch("databaseDiagnoseTool.diagnoseDatabase",
                "{\"service\":\"order-service\",\"slowThresholdMs\":1000}")
                .contains("trx_10423"));
        verifyNoInteractions(opsActionPort);
    }

    @Test
    @DisplayName("保留卡片签发到模拟提交的流程，确认前不执行、重复提交不重复执行")
    void mockCardSubmissionStillWorks() {
        applicationContext.getBean(TroubleshootSubAgent.class)
                .execute(newContext(), newStep("SRE_COPILOT_AGENT"));
        ArgumentCaptor<InteractiveCard> cardCaptor = ArgumentCaptor.forClass(InteractiveCard.class);
        verify(sink).card(cardCaptor.capture());
        InteractiveCard card = cardCaptor.getValue();
        verifyNoInteractions(opsActionPort);

        CardRegistry registry = new InMemoryCardRegistry();
        registry.issue("session-write-boundary", card);
        // 显式注入 Mock Port；本测试不替代后续的 Handler 自动装配修复。
        OpsActionCardSubmitHandler handler = new OpsActionCardSubmitHandler(opsActionPort);
        CardInteractionController controller = new CardInteractionController(
                List.of(handler), mock(SseEventPublisher.class), registry);
        Map<String, Object> formValues = new LinkedHashMap<>();
        card.getFields().forEach(field -> formValues.put(field.getFieldKey(), field.getValue()));
        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId(card.getActionId());
        request.setSessionId("session-write-boundary");
        request.setCardType(card.getCardType());
        request.setFormValues(formValues);

        var response = controller.submitCard(request);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(String.valueOf(response.getBody().get("ticketId")).startsWith("OPS-"));
        assertEquals(CardState.SUBMITTED, registry.getRecord(card.getActionId()).orElseThrow().getState());
        assertEquals(409, controller.submitCard(request).getStatusCode().value());
        verify(opsActionPort, times(1)).executeAction(any(OpsActionParam.class));
    }

    private AgentContext newContext() {
        return AgentContext.builder()
                .sessionId("session-write-boundary")
                .turnId("turn-write-boundary")
                .query("order-service 504，分析原因并提供处置建议")
                .sink(sink)
                .build();
    }

    private DispatchStep newStep(String agentCode) {
        return DispatchStep.builder()
                .stepOrder(1)
                .targetAgent(agentCode)
                .targetAgentName("排障专家")
                .taskDesc("提供查询分析与处置建议")
                .inputParams(Map.of("service", "order-service"))
                .build();
    }

    private ToolCallback callback(List<ToolCallback> callbacks, String name) {
        return callbacks.stream().filter(item -> name.equals(item.getToolDefinition().name()))
                .findFirst().orElseThrow();
    }
}
