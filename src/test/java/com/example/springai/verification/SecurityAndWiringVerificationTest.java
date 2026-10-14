package com.example.springai.verification;

import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.tool.DatabaseDiagnoseTool;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.tool.LogQueryTool;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.event.AgentEventSink;
import com.example.springai.core.routing.L2VectorRegistry;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.routing.impl.L2VectorResolver;
import com.example.springai.core.routing.vector.VectorEmbeddingService;
import com.example.springai.infra.client.AgentChatClientFactory;
import com.example.springai.scenario.troubleshoot.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 保留模型实参、容器装配与不安全出卡的验证；卡片安全验证迁移到 ActionGate/Controller/Registry 专项测试。 */
class SecurityAndWiringVerificationTest {
    private ChatModel model;
    private AgentPromptRegistry registry;
    private AgentChatClientFactory factory;
    private LogQueryTool logs;
    private DatabaseDiagnoseTool db;
    @BeforeEach void setup() {
        model=mock(ChatModel.class); var options=ToolCallingChatOptions.builder().build();
        when(model.getDefaultOptions()).thenReturn(options); when(model.getOptions()).thenReturn(options);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("诊断完成"))))));
        registry=new AgentPromptRegistry(null); registry.init();
        factory=new AgentChatClientFactory(ChatClient.builder(model),registry);
        logs=new LogQueryTool(new MockLogAdapter()); db=new DatabaseDiagnoseTool(new MockDbAdapter());
    }
    @Test void actualModelRequestsRespectToolIsolationAndUpdatedOptions() {
        var captor=ArgumentCaptor.forClass(Prompt.class);
        factory.createClient("LOG_DIAGNOSE_AGENT",logs,db).prompt().user("日志").stream().content().blockLast();
        verify(model).stream(captor.capture());
        var options=assertInstanceOf(ToolCallingChatOptions.class,captor.getValue().getOptions());
        assertEquals(List.of("queryServiceLogs"),options.getToolCallbacks().stream().map(t->t.getToolDefinition().name()).toList());
        clearInvocations(model);
        factory.createClient("DB_DIAGNOSE_AGENT",logs,db).prompt().user("数据库").stream().content().blockLast();
        verify(model).stream(captor.capture());
        options=assertInstanceOf(ToolCallingChatOptions.class,captor.getValue().getOptions());
        assertEquals(List.of("diagnoseDatabase"),options.getToolCallbacks().stream().map(t->t.getToolDefinition().name()).toList());
        var definition=registry.getAgent("LOG_DIAGNOSE_AGENT").orElseThrow();
        definition.setModelName("qwen-max"); definition.setTemperature(0.45); registry.registerOrUpdate(definition);
        clearInvocations(model);
        factory.createClient("LOG_DIAGNOSE_AGENT",logs,db).prompt().user("再次查询").stream().content().blockLast();
        verify(model).stream(captor.capture()); options=assertInstanceOf(ToolCallingChatOptions.class,captor.getValue().getOptions());
        assertEquals("qwen-max",options.getModel()); assertEquals(0.45,options.getTemperature(),0.001);
        definition.setAttachedTools("[\"opsActionTool\"]"); registry.registerOrUpdate(definition);
        assertThrows(IllegalArgumentException.class,()->factory.createClient("LOG_DIAGNOSE_AGENT",logs));
    }
    @Test void l2UsesContainerDependencies() {
        var vectors=mock(L2VectorRegistry.class); when(vectors.getActiveCount()).thenReturn(1);
        var embedding=mock(VectorEmbeddingService.class); when(embedding.embed(anyString())).thenReturn(new float[]{1,0});
        when(embedding.modelName()).thenReturn("test-model");
        when(vectors.searchTop1(any(float[].class),anyDouble(),anyString())).thenReturn(Optional.empty());
        try(var ctx=new AnnotationConfigApplicationContext()) {
            ctx.registerBean(L2VectorRegistry.class,()->vectors); ctx.registerBean(VectorEmbeddingService.class,()->embedding);
            ctx.register(L2VectorResolver.class); ctx.refresh();
            ctx.getBean(L2VectorResolver.class).resolve(AgentContext.builder().query("查询知识").build());
            verify(embedding).embed("查询知识");
        }
    }
    @Test void missingTargetConsultationAndModelFailureNeverIssueExecutableCard() {
        var sink=mock(AgentEventSink.class); when(sink.progress(anyString(),anyString())).thenReturn(true);
        when(sink.message(anyString())).thenReturn(true); when(sink.recommend(anyList())).thenReturn(true);
        var agent=new TroubleshootSubAgent(factory,new MockLogAdapter(),new MockDbAdapter(),logs,db,new TroubleshootCardFactory());
        var step=DispatchStep.builder().stepOrder(1).targetAgent("SRE_COPILOT_AGENT").inputParams(Map.of()).build();
        for(String query:List.of("请问 HikariCP 连接池的工作原理是什么？","线上接口 504，请排查")) {
            agent.executeStep(AgentContext.builder().query(query).sink(sink).build(),step);
        }
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("LLM unavailable")));
        agent.executeStep(AgentContext.builder().query("payment-service 504").sink(sink).build(),step);
        verify(sink,never()).card(any());
    }
}
