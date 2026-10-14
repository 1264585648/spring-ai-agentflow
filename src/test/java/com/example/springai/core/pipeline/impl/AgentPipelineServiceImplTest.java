package com.example.springai.core.pipeline.impl;

import com.example.springai.core.agent.*;
import com.example.springai.core.context.*;
import com.example.springai.core.event.SsePacket;
import com.example.springai.core.routing.*;
import com.example.springai.core.routing.dto.*;
import com.example.springai.core.tool.L1ToolDispatcher;
import com.example.springai.infra.sse.*;
import com.example.springai.web.dto.ChatRequest;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 检查实际事件包，而不是仅给过时的 Publisher 重载打桩。 */
class AgentPipelineServiceImplTest {
    private final List<SsePacket<?>> events = new ArrayList<>();
    private SseEventPublisher publisher;
    private AgentPipelineServiceImpl pipeline;
    @BeforeEach void setup() {
        var transport=mock(SseEmitterManager.class);
        when(transport.send(anyString(),any())).thenAnswer(call -> { events.add(call.getArgument(1)); return true; });
        publisher=new SseEventPublisher(transport);
    }
    @AfterEach void cleanup() { if(pipeline!=null) pipeline.close(); }
    private ChatRequest request() {
        var request=new ChatRequest(); request.setSessionId("s"); request.setTurnId("turn-1"); request.setQuery("query"); return request;
    }
    private IntentResolver resolver(Resolution result) {
        var resolver=mock(IntentResolver.class); when(resolver.resolve(any())).thenReturn(Optional.of(result)); return resolver;
    }
    private long count(String type) { return events.stream().filter(p -> p.getEvent().equals(type)).count(); }
    @Test void l1ReplyBypassesLaterResolversAndEmitsExactlyOneTerminalEvent() {
        var later=mock(IntentResolver.class); var general=mock(GeneralChatAgent.class);
        pipeline=new AgentPipelineServiceImpl(publisher,List.of(resolver(Resolution.directReply("L1","HELP","这是完整的快捷回复")),later),
                mock(L1ToolDispatcher.class),List.of(),general,Runnable::run);
        pipeline.process(request());
        verifyNoInteractions(later,general);
        assertEquals(1,count("done")); assertEquals(0,count("error"));
        String reply=events.stream().filter(p->p.getEvent().equals("message")).map(p -> (String)((Map<?,?>)p.getData()).get("content")).reduce("",String::concat);
        assertEquals("这是完整的快捷回复",reply);
        events.forEach(p -> assertEquals("turn-1",((Map<?,?>)p.getData()).get("turnId")));
        assertEquals(0,publisher.activeTurnCount()); assertEquals(0,pipeline.getActiveTurnCount());
    }
    @Test void compositePassesEvidenceAndStopsAfterFailedDependency() {
        var calls=new AtomicInteger();
        SubAgent expert=new SubAgent() {
            public String name() { return "EXPERT"; }
            public void execute(AgentContext context,DispatchStep step) {
                calls.incrementAndGet();
                if(step.getStepOrder()==2) assertEquals("first-evidence",context.getStepResults().get(0).getSummary());
                context.addStepResult(StepResult.builder().stepOrder(step.getStepOrder()).agentCode(name())
                        .status(step.getStepOrder()==2?StepResult.Status.FAILED:StepResult.Status.SUCCESS).summary("first-evidence").build());
            }
        };
        List<DispatchStep> steps=new ArrayList<>();
        for(int i=1;i<=3;i++) steps.add(DispatchStep.builder().stepOrder(i).targetAgent("EXPERT").build());
        pipeline=new AgentPipelineServiceImpl(publisher,List.of(resolver(Resolution.route("L3",PlanType.COMPOSITE,steps,"diagnose"))),
                mock(L1ToolDispatcher.class),List.of(expert),mock(GeneralChatAgent.class),Runnable::run);
        pipeline.process(request());
        assertEquals(2,calls.get()); assertEquals(1,count("error")); assertEquals(0,count("done"));
        assertEquals(0,publisher.activeTurnCount());
    }
    @Test void rejectionIsVisibleToHttpCallerAndDoesNotEmitSuccess() {
        pipeline=new AgentPipelineServiceImpl(publisher,List.of(),mock(L1ToolDispatcher.class),List.of(),mock(GeneralChatAgent.class),
                command -> { throw new java.util.concurrent.RejectedExecutionException(); });
        assertThrows(java.util.concurrent.RejectedExecutionException.class,()->pipeline.process(request()));
        assertEquals(0,count("done")); assertEquals(0,pipeline.getActiveTurnCount());
        assertTrue(pipeline.getRejectedTurnCount()>0);
    }
}
