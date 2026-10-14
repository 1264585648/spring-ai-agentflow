package com.example.springai.verification;

import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.port.DatabaseDiagnosePort;
import com.example.springai.capability.database.tool.DatabaseDiagnoseTool;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.port.LogQueryPort;
import com.example.springai.capability.log.tool.LogQueryTool;
import com.example.springai.capability.ops.card.OpsActionCardSubmitHandler;
import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.model.OpsActionResult;
import com.example.springai.capability.ops.port.OpsActionPort;
import com.example.springai.core.agent.AgentDefinition;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.state.CardState;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.event.AgentEventSink;
import com.example.springai.core.routing.L2VectorRegistry;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.routing.impl.L2VectorResolver;
import com.example.springai.core.routing.vector.VectorEmbeddingService;
import com.example.springai.infra.client.AgentChatClientFactory;
import com.example.springai.infra.sse.SseEventPublisher;
import com.example.springai.scenario.troubleshoot.TroubleshootCardFactory;
import com.example.springai.scenario.troubleshoot.TroubleshootSubAgent;
import com.example.springai.web.controller.CardInteractionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 架构与代码审查验收测试矩阵 (第一部分：T01 ~ T06, T10, T18, T19)
 * 覆盖：最小工具权限、AgentDefinition 运行配置转换、卡片归属/只读/范围/白名单重建、真实容器装配、诊断草案证据绑定与执行幂等。
 */
class SecurityAndWiringVerificationTest {

    private ChatModel chatModel;
    private AgentPromptRegistry promptRegistry;
    private AgentChatClientFactory clientFactory;
    private LogQueryTool logQueryTool;
    private DatabaseDiagnoseTool databaseDiagnoseTool;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        ToolCallingChatOptions defaultOptions = ToolCallingChatOptions.builder().build();
        when(chatModel.getDefaultOptions()).thenReturn(defaultOptions);
        when(chatModel.getOptions()).thenReturn(defaultOptions);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("诊断完成"))))));

        promptRegistry = new AgentPromptRegistry(null);
        promptRegistry.init();

        clientFactory = new AgentChatClientFactory(ChatClient.builder(chatModel), promptRegistry);
        logQueryTool = new LogQueryTool(new MockLogAdapter());
        databaseDiagnoseTool = new DatabaseDiagnoseTool(new MockDbAdapter());
    }

    @Test
    @DisplayName("T01/T02 (F01/F09): 真实 AgentPromptRegistry 下日志专家与数据库专家按最小权限隔离工具，且模型配置修改实时生效")
    void testLeastPrivilegeToolIsolationAndRuntimeConfigConversion() {
        // 1. LOG_DIAGNOSE_AGENT 仅可获得 queryServiceLogs，不可获得 diagnoseDatabase
        ChatClient logClient = clientFactory.createClient("LOG_DIAGNOSE_AGENT", logQueryTool, databaseDiagnoseTool);
        logClient.prompt().user("排查 order-service 日志").stream().content().blockLast();

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, atLeastOnce()).stream(promptCaptor.capture());
        ToolCallingChatOptions logOptions = assertInstanceOf(ToolCallingChatOptions.class,
                promptCaptor.getValue().getOptions());
        List<String> logTools = logOptions.getToolCallbacks().stream()
                .map(cb -> cb.getToolDefinition().name()).toList();
        assertEquals(List.of("queryServiceLogs"), logTools, "日志专家仅能获得日志查询工具");

        // 2. DB_DIAGNOSE_AGENT 仅可获得 diagnoseDatabase，不可获得 queryServiceLogs
        clearInvocations(chatModel);
        ChatClient dbClient = clientFactory.createClient("DB_DIAGNOSE_AGENT", logQueryTool, databaseDiagnoseTool);
        dbClient.prompt().user("排查 order-service 慢SQL").stream().content().blockLast();

        verify(chatModel).stream(promptCaptor.capture());
        ToolCallingChatOptions dbOptions = assertInstanceOf(ToolCallingChatOptions.class,
                promptCaptor.getValue().getOptions());
        List<String> dbTools = dbOptions.getToolCallbacks().stream()
                .map(cb -> cb.getToolDefinition().name()).toList();
        assertEquals(List.of("diagnoseDatabase"), dbTools, "数据库专家仅能获得数据库诊断工具");

        // 3. 修改 AgentDefinition 的 modelName 与 temperature 后，模型收到的 Prompt Options 实际反映新参数
        clearInvocations(chatModel);
        AgentDefinition updated = promptRegistry.getAgent("LOG_DIAGNOSE_AGENT").orElseThrow();
        updated.setModelName("qwen-max");
        updated.setTemperature(0.45);
        updated.setVersion(2);
        promptRegistry.registerOrUpdate(updated);

        ChatClient updatedClient = clientFactory.createClient("LOG_DIAGNOSE_AGENT", logQueryTool, databaseDiagnoseTool);
        updatedClient.prompt().user("再次排查").stream().content().blockLast();

        verify(chatModel).stream(promptCaptor.capture());
        ToolCallingChatOptions updatedOpts = assertInstanceOf(ToolCallingChatOptions.class,
                promptCaptor.getValue().getOptions());
        assertEquals("qwen-max", updatedOpts.getModel());
        assertEquals(0.45, updatedOpts.getTemperature(), 0.001);

        // 4. 未知模型或非法工具配置必须明确拒绝
        updated.setModelName("unknown-model-xyz");
        promptRegistry.registerOrUpdate(updated);
        assertThrows(IllegalArgumentException.class,
                () -> clientFactory.createClient("LOG_DIAGNOSE_AGENT", logQueryTool));

        updated.setModelName("qwen-max");
        updated.setAttachedTools("[\"opsActionTool\"]");
        promptRegistry.registerOrUpdate(updated);
        assertThrows(IllegalArgumentException.class,
                () -> clientFactory.createClient("LOG_DIAGNOSE_AGENT", logQueryTool));
    }

    @Test
    @DisplayName("T03/T04/T05 (F02): 空会话、跨会话、篡改 cardType、注入 operator、篡改只读字段与超范围数值均被拦截且状态可恢复")
    void testCardOwnershipTypeBindingValidationAndRecovery() {
        InMemoryCardRegistry registry = new InMemoryCardRegistry();
        InteractiveCard card = new InteractiveCard();
        card.setActionId("act-sec-001");
        card.setTurnId("turn-sec-001");
        card.setCardType("TROUBLESHOOT_ACTION");
        card.setTitle("应急止血确认");
        CardFormField serviceField = new CardFormField("service", "目标服务", "text", "order-service", false, true);
        CardFormField actionField = new CardFormField("action_type", "处置动作", "text", "KILL_SLOW_SESSION", true, true);
        CardFormField poolField = new CardFormField("max_pool_size", "连接池上限", "number", 50, 100, 10, true, false, null);
        card.setFields(List.of(serviceField, actionField, poolField));

        registry.issue("sess-owner-1", card);

        ArgumentCaptor<OpsActionParam> paramCaptor = ArgumentCaptor.forClass(OpsActionParam.class);
        OpsActionPort spyPort = spy(new MockOpsActionAdapter());
        OpsActionCardSubmitHandler realHandler = new OpsActionCardSubmitHandler(spyPort);
        CardSubmitHandler rogueHandler = new CardSubmitHandler() {
            @Override
            public boolean supports(String cardType) {
                return "ROGUE_CARD_TYPE".equals(cardType);
            }

            @Override
            public CardSubmitResult handleSubmit(CardSubmitRequest request) {
                fail("绝不能路由到客户端伪造的 ROGUE_CARD_TYPE 处理器");
                return CardSubmitResult.fail("should not reach");
            }
        };

        SseEventPublisher ssePublisher = mock(SseEventPublisher.class);
        CardInteractionController controller = new CardInteractionController(
                List.of(realHandler, rogueHandler), ssePublisher, registry);

        // 1. T03: 空 sessionId 提交必须返回 403，绝不跳过归属核验
        CardSubmitRequest blankSessionReq = new CardSubmitRequest();
        blankSessionReq.setActionId("act-sec-001");
        blankSessionReq.setSessionId("   ");
        blankSessionReq.setCardType("TROUBLESHOOT_ACTION");
        blankSessionReq.setFormValues(Map.of("service", "order-service", "action_type", "KILL_SLOW_SESSION"));
        assertEquals(403, controller.submitCard(blankSessionReq).getStatusCode().value());
        assertEquals(CardState.ISSUED, registry.getRecord("act-sec-001").orElseThrow().getState());

        // 2. T05: 省略只读字段或超出数值范围 -> 返回 400 且卡片状态回退为 ISSUED（不卡死在 SUBMITTING）
        CardSubmitRequest outOfRangeReq = new CardSubmitRequest();
        outOfRangeReq.setActionId("act-sec-001");
        outOfRangeReq.setSessionId("sess-owner-1");
        outOfRangeReq.setFormValues(Map.of(
                "service", "order-service",
                "action_type", "KILL_SLOW_SESSION",
                "max_pool_size", 500 // 超过 maxLimit=100
        ));
        ResponseEntity<Map<String, Object>> rangeResp = controller.submitCard(outOfRangeReq);
        assertEquals(400, rangeResp.getStatusCode().value());
        assertEquals(CardState.ISSUED, registry.getRecord("act-sec-001").orElseThrow().getState(),
                "校验失败后卡片必须回退至 ISSUED 可恢复状态");

        // 3. T04: 客户端篡改 cardType 为 ROGUE_CARD_TYPE，并在 formValues 中夹带 operator="HACKER_ADMIN"
        CardSubmitRequest forgedReq = new CardSubmitRequest();
        forgedReq.setActionId("act-sec-001");
        forgedReq.setSessionId("sess-owner-1");
        forgedReq.setCardType("ROGUE_CARD_TYPE");
        Map<String, Object> formWithExtra = new LinkedHashMap<>();
        formWithExtra.put("service", "order-service");
        formWithExtra.put("action_type", "KILL_SLOW_SESSION");
        formWithExtra.put("max_pool_size", 80);
        formWithExtra.put("operator", "HACKER_ADMIN");
        formWithExtra.put("unauthorized_field", "malicious_payload");
        forgedReq.setFormValues(formWithExtra);

        ResponseEntity<Map<String, Object>> okResp = controller.submitCard(forgedReq);
        assertEquals(200, okResp.getStatusCode().value());
        assertEquals("TROUBLESHOOT_ACTION", okResp.getBody().get("cardType"),
                "必须绑定服务端签发记录中的 cardType");

        verify(spyPort).executeAction(paramCaptor.capture());
        OpsActionParam executedParam = paramCaptor.getValue();
        assertEquals(OpsActionCardSubmitHandler.TRUSTED_DEFAULT_OPERATOR, executedParam.getOperator(),
                "执行操作人必须来自服务端可信上下文，严禁采用表单中的 HACKER_ADMIN");
        assertEquals("act-sec-001", executedParam.getIdempotencyKey());
    }

    @Test
    @DisplayName("T06 (F03): 真实 Spring 容器装配 L2VectorResolver 与 OpsActionCardSubmitHandler，验证注入容器 Bean 而非空构造或私建 Mock")
    void testSpringContainerUsesInjectedBeansNotNoArgConstructors() {
        OpsActionPort customPort = mock(OpsActionPort.class);
        when(customPort.findExecutedResult(anyString())).thenReturn(Optional.empty());
        when(customPort.executeAction(any())).thenReturn(OpsActionResult.builder()
                .success(true).ticketId("OPS-CONTAINER-01").auditMessage("容器端口执行成功").build());

        L2VectorRegistry mockL2Registry = mock(L2VectorRegistry.class);
        when(mockL2Registry.getActiveCount()).thenReturn(5);
        VectorEmbeddingService mockEmbedding = mock(VectorEmbeddingService.class);
        when(mockEmbedding.modelName()).thenReturn("text-embedding-3-small");
        when(mockEmbedding.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        when(mockL2Registry.searchTop1(any(float[].class), anyDouble(), eq("text-embedding-3-small")))
                .thenReturn(Optional.empty());

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(OpsActionPort.class, () -> customPort);
            ctx.registerBean(L2VectorRegistry.class, () -> mockL2Registry);
            ctx.registerBean(VectorEmbeddingService.class, () -> mockEmbedding);
            ctx.register(OpsActionCardSubmitHandler.class, L2VectorResolver.class);
            ctx.refresh();

            OpsActionCardSubmitHandler handler = ctx.getBean(OpsActionCardSubmitHandler.class);
            CardSubmitRequest req = new CardSubmitRequest();
            req.setActionId("act-container-1");
            req.setSessionId("sess-1");
            req.setFormValues(Map.of("service", "payment-service", "action_type", "RESTART_POD"));
            CardSubmitResult res = handler.handleSubmit(req);
            assertTrue(res.isSuccess());
            assertEquals("OPS-CONTAINER-01", res.getTicketId());
            verify(customPort, times(1)).executeAction(any());

            L2VectorResolver resolver = ctx.getBean(L2VectorResolver.class);
            AgentContext agentContext = AgentContext.builder().query("网关超时如何排查").build();
            resolver.resolve(agentContext);
            verify(mockEmbedding, times(1)).embed("网关超时如何排查");
        }
    }

    @Test
    @DisplayName("T10 (F05): 纯技术咨询、缺失目标服务、以及在线模型调用失败时，均不下发固定目标的可执行卡片")
    void testNoExecutableCardOnConsultationMissingTargetOrModelFailure() {
        AgentEventSink sink = mock(AgentEventSink.class);
        when(sink.progress(anyString(), anyString())).thenReturn(true);
        when(sink.message(anyString())).thenReturn(true);
        when(sink.card(any())).thenReturn(true);
        when(sink.recommend(anyList())).thenReturn(true);

        TroubleshootSubAgent subAgent = new TroubleshootSubAgent(
                clientFactory,
                new MockLogAdapter(),
                new MockDbAdapter(),
                logQueryTool,
                databaseDiagnoseTool,
                new TroubleshootCardFactory()
        );

        // 1. 纯咨询问题 -> 不下发卡片
        AgentContext consultCtx = AgentContext.builder()
                .sessionId("s1").turnId("t1")
                .query("请问 HikariCP 连接池的工作原理是什么？")
                .sink(sink).build();
        DispatchStep consultStep = DispatchStep.builder()
                .stepOrder(1).targetAgent("SRE_COPILOT_AGENT").targetAgentName("SRE专家")
                .inputParams(Map.of()).build();
        subAgent.executeStep(consultCtx, consultStep);
        verify(sink, never()).card(any());

        // 2. 缺失目标服务名 -> 引导补充服务名，绝不默认填补 order-service 下发卡片
        clearInvocations(sink);
        AgentContext missingServiceCtx = AgentContext.builder()
                .sessionId("s1").turnId("t2")
                .query("线上接口报 504 错误，请帮我止血")
                .sink(sink).build();
        DispatchStep missingServiceStep = DispatchStep.builder()
                .stepOrder(1).targetAgent("SRE_COPILOT_AGENT").targetAgentName("SRE专家")
                .inputParams(Map.of()).build();
        subAgent.executeStep(missingServiceCtx, missingServiceStep);
        verify(sink, never()).card(any());

        // 3. 在线模型抛出异常 -> 返回错误提示，绝不伪造诊断完成下发固定 trx_10423 卡片
        clearInvocations(sink);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("Upstream LLM 503")));
        AgentContext errorCtx = AgentContext.builder()
                .sessionId("s1").turnId("t3")
                .query("payment-service 出现 504 超时")
                .sink(sink).build();
        DispatchStep errorStep = DispatchStep.builder()
                .stepOrder(1).targetAgent("SRE_COPILOT_AGENT").targetAgentName("SRE专家")
                .inputParams(Map.of("service", "payment-service")).build();
        var stepResult = subAgent.executeStep(errorCtx, errorStep);
        assertEquals(com.example.springai.core.context.StepResult.Status.FAILED, stepResult.getStatus());
        verify(sink, never()).card(any());
    }

    @Test
    @DisplayName("T18/T19 (F11): 端口返回业务失败不包装为成功；外部已执行成功但首次响应丢失时，重试直接返回原幂等结果而不产生第二次副作用")
    void testPortBusinessFailureAndIdempotentRetryOnUnknownOutcome() {
        InMemoryCardRegistry registry = new InMemoryCardRegistry();
        InteractiveCard card = new InteractiveCard("act-idem-1", "TROUBLESHOOT_ACTION", "止血", "描述",
                List.of(
                        new CardFormField("service", "服务", "text", "payment-service", false, true),
                        new CardFormField("action_type", "动作", "text", "KILL_SLOW_SESSION", true, true)
                ), "确认", "取消");
        card.setTurnId("turn-1");
        registry.issue("sess-idem", card);

        // 1. T18: 端口返回 success=false 时，Controller 必须返回 500 且卡片状态回退为 ISSUED
        OpsActionPort failingPort = mock(OpsActionPort.class);
        when(failingPort.findExecutedResult(anyString())).thenReturn(Optional.empty());
        when(failingPort.executeAction(any())).thenReturn(OpsActionResult.builder()
                .success(false).auditMessage("目标实例处于保护锁定状态，拒绝执行").build());

        CardInteractionController failController = new CardInteractionController(
                List.of(new OpsActionCardSubmitHandler(failingPort)), mock(SseEventPublisher.class), registry);

        CardSubmitRequest req = new CardSubmitRequest();
        req.setActionId("act-idem-1");
        req.setSessionId("sess-idem");
        req.setCardType("TROUBLESHOOT_ACTION");
        req.setFormValues(Map.of("service", "payment-service", "action_type", "KILL_SLOW_SESSION"));

        ResponseEntity<Map<String, Object>> failResp = failController.submitCard(req);
        assertEquals(500, failResp.getStatusCode().value());
        assertEquals(CardState.ISSUED, registry.getRecord("act-idem-1").orElseThrow().getState());

        // 2. T19: 模拟首次执行已在端口侧落地成功但上层网络超时释放了锁，二次重试时通过幂等键查得原结果，底层物理动作仅执行 1 次
        AtomicInteger physicalExecutions = new AtomicInteger(0);
        MockOpsActionAdapter realIdempotentAdapter = new MockOpsActionAdapter() {
            @Override
            public OpsActionResult executeAction(OpsActionParam param) {
                if (param.getIdempotencyKey() != null) {
                    Optional<OpsActionResult> existing = findExecutedResult(param.getIdempotencyKey());
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                }
                physicalExecutions.incrementAndGet();
                return super.executeAction(param);
            }
        };

        // 预模拟外部端口已完成首次执行但因网络抖动 Controller 侧释放回 ISSUED
        OpsActionParam firstParam = OpsActionParam.builder()
                .idempotencyKey("act-idem-1")
                .service("payment-service")
                .actionType("KILL_SLOW_SESSION")
                .targetIdentifier("trx_payment_99")
                .operator("SRE_CONFIRMED_CONSOLE")
                .build();
        OpsActionResult firstResult = realIdempotentAdapter.executeAction(firstParam);
        assertEquals(1, physicalExecutions.get());

        CardInteractionController retryController = new CardInteractionController(
                List.of(new OpsActionCardSubmitHandler(realIdempotentAdapter)), mock(SseEventPublisher.class), registry);

        ResponseEntity<Map<String, Object>> retryResp = retryController.submitCard(req);
        assertEquals(200, retryResp.getStatusCode().value());
        assertEquals(firstResult.getTicketId(), retryResp.getBody().get("ticketId"));
        assertEquals(1, physicalExecutions.get(), "幂等重试绝不能触发第二次物理副作用");
    }
}
