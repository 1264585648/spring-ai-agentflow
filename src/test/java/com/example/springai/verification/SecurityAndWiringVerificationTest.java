package com.example.springai.verification;

import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.tool.DatabaseDiagnoseTool;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.tool.LogQueryTool;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.context.StepResult;
import com.example.springai.core.event.AgentEventSink;
import com.example.springai.core.routing.L2VectorRegistry;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.routing.impl.L2VectorResolver;
import com.example.springai.core.routing.vector.VectorEmbeddingService;
import com.example.springai.infra.client.AgentChatClientFactory;
import com.example.springai.scenario.troubleshoot.TroubleshootCardFactory;
import com.example.springai.scenario.troubleshoot.TroubleshootSubAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 从模型及端口实际收到的参数断言行为，而不是依赖混合匹配器的验证表达式。
 * 不删除安全断言：保留工具隔离、模型选项、真实容器注入和异常时不出卡检查。
 */
@Timeout(15)
class SecurityAndWiringVerificationTest {
    private final List<Prompt> modelRequests = new ArrayList<>();
    private final AtomicBoolean modelUnavailable = new AtomicBoolean();
    private AgentPromptRegistry registry;
    private AgentChatClientFactory factory;
    private LogQueryTool logs;
    private DatabaseDiagnoseTool database;

    @BeforeEach
    void setup() {
        var defaults = ToolCallingChatOptions.builder().build();
        ChatModel model = mock(ChatModel.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("getOptions") || method.equals("getDefaultOptions")) {
                return defaults;
            }
            if (method.equals("stream")) {
                modelRequests.add(invocation.getArgument(0, Prompt.class));
                if (modelUnavailable.get()) {
                    return Flux.error(new IllegalStateException("LLM unavailable"));
                }
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("诊断完成")))));
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        registry = new AgentPromptRegistry(null);
        registry.init();
        factory = new AgentChatClientFactory(ChatClient.builder(model), registry);
        logs = new LogQueryTool(new MockLogAdapter());
        database = new DatabaseDiagnoseTool(new MockDbAdapter());
    }

    private ToolCallingChatOptions executeAndObserve(String agentCode, String query) {
        int before = modelRequests.size();
        factory.createClient(agentCode, logs, database).prompt().user(query).stream().content().blockLast();
        assertEquals(before + 1, modelRequests.size(), "应恰好向实际模型发出一次请求");
        return assertInstanceOf(ToolCallingChatOptions.class, modelRequests.get(before).getOptions());
    }

    @Test
    void actualModelRequestsRespectToolIsolationAndUpdatedOptions() {
        var logOptions = executeAndObserve("LOG_DIAGNOSE_AGENT", "日志");
        assertEquals(List.of("queryServiceLogs"), logOptions.getToolCallbacks().stream()
                .map(tool -> tool.getToolDefinition().name()).toList());

        var databaseOptions = executeAndObserve("DB_DIAGNOSE_AGENT", "数据库");
        assertEquals(List.of("diagnoseDatabase"), databaseOptions.getToolCallbacks().stream()
                .map(tool -> tool.getToolDefinition().name()).toList());

        var definition = registry.getAgent("LOG_DIAGNOSE_AGENT").orElseThrow();
        definition.setModelName("qwen-max");
        definition.setTemperature(0.45);
        registry.registerOrUpdate(definition);
        var updatedOptions = executeAndObserve("LOG_DIAGNOSE_AGENT", "再次查询");
        assertEquals("qwen-max", updatedOptions.getModel());
        assertEquals(0.45, updatedOptions.getTemperature(), 0.001);

        definition.setAttachedTools("[\"opsActionTool\"]");
        registry.registerOrUpdate(definition);
        int before = modelRequests.size();
        assertThrows(IllegalArgumentException.class, () -> factory.createClient("LOG_DIAGNOSE_AGENT", logs));
        assertEquals(before, modelRequests.size(), "非法工具配置必须在调用模型前被拒绝");
    }

    @Test
    void l2UsesContainerDependencies() {
        List<String> embeddingRequests = new ArrayList<>();
        AtomicInteger searches = new AtomicInteger();
        VectorEmbeddingService embedding = mock(VectorEmbeddingService.class, invocation -> {
            if (invocation.getMethod().getName().equals("embed")) {
                embeddingRequests.add(invocation.getArgument(0, String.class));
                return new float[]{1, 0};
            }
            if (invocation.getMethod().getName().equals("modelName")) return "test-model";
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        L2VectorRegistry vectors = mock(L2VectorRegistry.class, invocation -> {
            if (invocation.getMethod().getName().equals("getActiveCount")) return 1;
            if (invocation.getMethod().getName().equals("searchTop1")) {
                assertEquals(3, invocation.getArguments().length, "检索必须携带模型空间标识");
                assertArrayEquals(new float[]{1, 0}, invocation.getArgument(0, float[].class));
                assertEquals(0.70, ((Number) invocation.getArgument(1)).doubleValue(), 0.001);
                assertEquals("test-model", invocation.getArgument(2, String.class));
                searches.incrementAndGet();
                return Optional.empty();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(L2VectorRegistry.class, () -> vectors);
            context.registerBean(VectorEmbeddingService.class, () -> embedding);
            context.register(L2VectorResolver.class);
            context.refresh();
            assertTrue(context.getBean(L2VectorResolver.class)
                    .resolve(AgentContext.builder().query("查询知识").build()).isEmpty());
            assertEquals(List.of("查询知识"), embeddingRequests);
            assertEquals(1, searches.get());
        }
    }

    @Test
    void missingTargetConsultationAndModelFailureNeverIssueExecutableCard() {
        AtomicInteger issuedCards = new AtomicInteger();
        AgentEventSink sink = mock(AgentEventSink.class, invocation -> {
            if (invocation.getMethod().getName().equals("card")) issuedCards.incrementAndGet();
            if (invocation.getMethod().getReturnType() == boolean.class) return true;
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        var agent = new TroubleshootSubAgent(factory, new MockLogAdapter(), new MockDbAdapter(),
                logs, database, new TroubleshootCardFactory());
        var step = DispatchStep.builder().stepOrder(1).targetAgent("SRE_COPILOT_AGENT")
                .inputParams(Map.of()).build();
        for (String query : List.of("请问 HikariCP 连接池的工作原理是什么？", "线上接口 504，请排查")) {
            agent.executeStep(AgentContext.builder().query(query).sink(sink).build(), step);
            assertEquals(0, issuedCards.get());
        }
        modelUnavailable.set(true);
        StepResult failed = agent.executeStep(
                AgentContext.builder().query("payment-service 504").sink(sink).build(), step);
        assertEquals(StepResult.Status.FAILED, failed.getStatus());
        assertEquals(0, issuedCards.get(), "模型失败不能签发可执行卡片");
    }
}
