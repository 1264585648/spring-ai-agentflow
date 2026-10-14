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
import com.example.springai.core.routing.vector.VectorCandidate;
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

/** 实际模型参数与容器装配回归；L2 使用有类型的记录替身，避免 Mockito 泛型推断依赖。 */
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
            if (method.equals("getOptions") || method.equals("getDefaultOptions")) return defaults;
            if (method.equals("stream")) {
                modelRequests.add((Prompt) invocation.getArguments()[0]);
                if (modelUnavailable.get()) return Flux.error(new IllegalStateException("LLM unavailable"));
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
        assertNotNull(updatedOptions.getTemperature());
        assertTrue(Math.abs(updatedOptions.getTemperature().doubleValue() - 0.45d) < 0.001d);
        definition.setAttachedTools("[\"opsActionTool\"]");
        registry.registerOrUpdate(definition);
        int before = modelRequests.size();
        assertThrows(IllegalArgumentException.class, () -> factory.createClient("LOG_DIAGNOSE_AGENT", logs));
        assertEquals(before, modelRequests.size(), "非法工具配置必须在调用模型前被拒绝");
    }

    @Test
    void l2UsesContainerDependencies() {
        RecordingEmbedding embedding = new RecordingEmbedding();
        RecordingVectorRegistry vectors = new RecordingVectorRegistry();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(L2VectorRegistry.class, () -> vectors);
            context.registerBean(VectorEmbeddingService.class, () -> embedding);
            context.register(L2VectorResolver.class);
            context.refresh();
            Optional<?> resolution = context.getBean(L2VectorResolver.class)
                    .resolve(AgentContext.builder().query("查询知识").build());
            assertTrue(resolution.isEmpty());
            assertEquals(List.of("查询知识"), embedding.requests);
            assertEquals(1, vectors.searches);
            assertArrayEquals(new float[]{1, 0}, vectors.query);
            assertTrue(Math.abs(vectors.threshold - 0.70d) < 0.001d);
            assertEquals("test-model", vectors.model);
        }
    }

    private static final class RecordingEmbedding implements VectorEmbeddingService {
        final List<String> requests = new ArrayList<>();
        @Override public float[] embed(String text) { requests.add(text); return new float[]{1, 0}; }
        @Override public int dimensions() { return 2; }
        @Override public String modelName() { return "test-model"; }
    }

    private static final class RecordingVectorRegistry extends L2VectorRegistry {
        int searches;
        float[] query;
        double threshold;
        String model;
        RecordingVectorRegistry() { super(null, Runnable::run); }
        // 容器生命周期不访问真实数据库；测试仍由容器创建并装配 L2VectorResolver。
        @Override public void init() { }
        @Override public int getActiveCount() { return 1; }
        @Override public Optional<VectorCandidate> searchTop1(float[] vector, double minimum, String expectedModel) {
            searches++;
            query = vector.clone();
            threshold = minimum;
            model = expectedModel;
            return Optional.empty();
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
        StepResult failed = agent.executeStep(AgentContext.builder().query("payment-service 504").sink(sink).build(), step);
        assertEquals(StepResult.Status.FAILED, failed.getStatus());
        assertEquals(0, issuedCards.get(), "模型失败不能签发可执行卡片");
    }
}
