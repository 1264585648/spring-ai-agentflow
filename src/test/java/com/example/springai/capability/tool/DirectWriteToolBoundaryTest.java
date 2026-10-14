package com.example.springai.capability.tool;

import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.port.DatabaseDiagnosePort;
import com.example.springai.capability.database.tool.DatabaseDiagnoseTool;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.port.LogQueryPort;
import com.example.springai.capability.log.tool.LogQueryTool;
import com.example.springai.capability.ops.card.OpsActionCardSubmitHandler;
import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.port.OpsActionPort;
import com.example.springai.capability.ops.tool.OpsActionTool;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.card.action.*;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.event.AgentEventSink;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.tool.*;
import com.example.springai.infra.client.AgentChatClientFactory;
import com.example.springai.scenario.troubleshoot.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.*;

@Timeout(20)
class DirectWriteToolBoundaryTest {
    private AnnotationConfigApplicationContext beans;
    private ChatModel model;
    private OpsActionPort ops;
    private AgentEventSink sink;
    @BeforeEach void setup() {
        model=mock(ChatModel.class); var defaults=ToolCallingChatOptions.builder().build();
        when(model.getDefaultOptions()).thenReturn(defaults); when(model.getOptions()).thenReturn(defaults);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("分析完成"))))));
        ops=spy(new MockOpsActionAdapter()); var prompts=mock(AgentPromptRegistry.class);
        when(prompts.getSystemPrompt(anyString())).thenReturn("你是只读诊断助手");
        sink=mock(AgentEventSink.class); when(sink.progress(anyString(),anyString())).thenReturn(true);
        when(sink.message(anyString())).thenReturn(true); when(sink.card(any())).thenReturn(true); when(sink.recommend(anyList())).thenReturn(true);
        beans=new AnnotationConfigApplicationContext();
        beans.registerBean(LogQueryPort.class,() -> new MockLogAdapter()); beans.registerBean(DatabaseDiagnosePort.class,() -> new MockDbAdapter());
        beans.registerBean(OpsActionPort.class,() -> ops);
        beans.registerBean(AgentChatClientFactory.class,() -> new AgentChatClientFactory(ChatClient.builder(model),prompts));
        beans.register(LogQueryTool.class,DatabaseDiagnoseTool.class,OpsActionTool.class,L1ToolCatalog.class,L1ToolDispatcher.class,TroubleshootCardFactory.class,TroubleshootSubAgent.class);
        beans.refresh();
    }
    @AfterEach void cleanup() { beans.close(); }
    private void diagnose(String role) {
        beans.getBean(TroubleshootSubAgent.class).execute(AgentContext.builder().sessionId("s").turnId("t")
                .query("order-service 504，排查原因并提供处置建议").sink(sink).build(),
                DispatchStep.builder().stepOrder(1).targetAgent(role).targetAgentName("专家").inputParams(Map.of("service","order-service")).build());
    }
    @ParameterizedTest @ValueSource(strings={"LOG_DIAGNOSE_AGENT","DB_DIAGNOSE_AGENT","SRE_COPILOT_AGENT","TROUBLESHOOT_AGENT"})
    void realModelPromptExposesOnlyReadTools(String role) {
        diagnose(role); var prompt=ArgumentCaptor.forClass(Prompt.class); verify(model).stream(prompt.capture());
        var options=assertInstanceOf(ToolCallingChatOptions.class,prompt.getValue().getOptions());
        var callbacks=options.getToolCallbacks(); assertNotNull(callbacks);
        assertEquals(List.of("diagnoseDatabase","queryServiceLogs"),callbacks.stream().map(t->t.getToolDefinition().name()).sorted().toList());
        assertTrue(callbacks.stream().filter(t->t.getToolDefinition().name().equals("queryServiceLogs")).findFirst().orElseThrow()
                .call("{\"service\":\"order-service\",\"keyword\":\"504\",\"logLevel\":\"ERROR\"}").contains("HikariPool"));
        verify(sink).message("分析完成"); verify(ops,never()).executeAction(any());
    }
    @Test void writeBeanExistsButL1CannotCallIt() {
        assertTrue(beans.containsBean("opsActionTool"));
        var catalog=beans.getBean(L1ToolCatalog.class); var dispatcher=beans.getBean(L1ToolDispatcher.class);
        assertFalse(catalog.isRegistered("opsActionTool.executeEmergencyAction"));
        assertThrows(IllegalArgumentException.class,() -> dispatcher.dispatch("opsActionTool.executeEmergencyAction","{}"));
        assertTrue(catalog.isRegistered("logQueryTool.queryServiceLogs"));
        assertTrue(dispatcher.dispatch("logQueryTool.queryServiceLogs","{\"service\":\"order-service\",\"keyword\":\"504\",\"logLevel\":\"ERROR\"}").contains("HikariPool"));
        verify(ops,never()).executeAction(any());
    }
    @Test void cardMustBeExplicitlyApprovedAndCannotExecuteTwice() {
        diagnose("SRE_COPILOT_AGENT"); var cards=ArgumentCaptor.forClass(InteractiveCard.class); verify(sink).card(cards.capture());
        verify(ops,never()).executeAction(any());
        var store=new InMemoryCardRegistry(); var card=cards.getValue(); store.issue("s",card);
        var gate=new ActionGate(store,List.of(new OpsActionCardSubmitHandler(ops)),ActionAuthorizer.DENY_ALL,true,false);
        var request=new CardSubmitRequest(card.getActionId(),"s",card.getCardType(),Map.of());
        assertTrue(gate.submit(request,null).getResult().isSuccess());
        assertThrows(CardStateConflictException.class,() -> gate.submit(request,null)); verify(ops,times(1)).executeAction(any());
    }
}
