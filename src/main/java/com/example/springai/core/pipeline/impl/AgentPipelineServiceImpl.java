package com.example.springai.core.pipeline.impl;

import com.example.springai.core.agent.GeneralChatAgent;
import com.example.springai.core.agent.SubAgent;
import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.context.StepResult;
import com.example.springai.core.event.CancellableAgentEventSink;
import com.example.springai.core.event.SessionDisconnectedEvent;
import com.example.springai.core.memory.ConversationMemory;
import com.example.springai.core.memory.ConversationMessage;
import com.example.springai.core.pipeline.AgentPipelineService;
import com.example.springai.core.routing.*;
import com.example.springai.core.routing.dto.*;
import com.example.springai.core.routing.impl.L1RuleResolver;
import com.example.springai.core.routing.impl.L3MasterAgentResolver;
import com.example.springai.core.tool.L1ToolDispatcher;
import com.example.springai.infra.concurrent.TurnTaskSupervisor;
import com.example.springai.infra.sse.SseAgentEventSink;
import com.example.springai.infra.sse.SseEventPublisher;
import com.example.springai.web.dto.ChatRequest;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.Executor;

/** 串行步骤流水线。现有接入依赖仍待后续 SPI 抽离；不宣称是完整 DAG/runtime。 */
@Service
@Slf4j
public class AgentPipelineServiceImpl implements AgentPipelineService,AutoCloseable {
    public static final long DEFAULT_TURN_TIMEOUT_MS=60000;
    private final SseEventPublisher publisher;
    private final List<IntentResolver> resolvers;
    private final L1ToolDispatcher tools;
    private final List<SubAgent> agents;
    private final GeneralChatAgent general;
    private final CardRegistry cards;
    private final ConversationMemory memory;
    private final QueryRewriter rewriter;
    private final TurnTaskSupervisor supervisor;
    private volatile long turnTimeoutMillis=DEFAULT_TURN_TIMEOUT_MS;
    @Autowired
    public AgentPipelineServiceImpl(SseEventPublisher publisher,List<IntentResolver> resolvers,L1ToolDispatcher tools,
            List<SubAgent> agents,GeneralChatAgent general,CardRegistry cards,
            @Autowired(required=false) ConversationMemory memory,@Autowired(required=false) QueryRewriter rewriter,
            @Qualifier("agentPipelineExecutor") Executor executor) {
        this.publisher=publisher; this.resolvers=resolvers==null?List.of():List.copyOf(resolvers);
        this.tools=tools; this.agents=agents==null?List.of():List.copyOf(agents); this.general=general;
        this.cards=cards; this.memory=memory; this.rewriter=rewriter; this.supervisor=new TurnTaskSupervisor(executor);
    }
    public AgentPipelineServiceImpl(SseEventPublisher p,List<IntentResolver> r,L1ToolDispatcher t,List<SubAgent> a,
            GeneralChatAgent g,CardRegistry c,Executor e) { this(p,r,t,a,g,c,null,null,e); }
    public AgentPipelineServiceImpl(SseEventPublisher p,List<IntentResolver> r,L1ToolDispatcher t,List<SubAgent> a,
            GeneralChatAgent g,Executor e) { this(p,r,t,a,g,null,null,null,e); }
    public AgentPipelineServiceImpl(SseEventPublisher p,L1RuleMatcher matcher,L1ToolDispatcher t,MasterAgentRouter router,
            List<SubAgent> a,GeneralChatAgent g,Executor e) {
        this(p,List.of(new L1RuleResolver(matcher),new L3MasterAgentResolver(router)),t,a,g,null,null,null,e);
    }
    public void setTurnTimeoutMillis(long value) { turnTimeoutMillis=value>0?value:DEFAULT_TURN_TIMEOUT_MS; }
    public int getActiveTurnCount() { return supervisor.activeCount(); }
    public long getRejectedTurnCount() { return supervisor.rejectedCount(); }
    public long getTimeoutTurnCount() { return supervisor.timeoutCount(); }
    public long getCancelledTurnCount() { return supervisor.cancelledCount(); }
    @Override public boolean cancelActiveTurn(String sessionId) { return sessionId!=null && supervisor.cancel(sessionId); }
    @Override public boolean cancelActiveTurn(String sessionId,String turnId) {
        return sessionId!=null && supervisor.cancel(sessionId,turnId);
    }
    @EventListener public void onDisconnected(SessionDisconnectedEvent event) { cancelActiveTurn(event.getSessionId()); }
    @PreDestroy @Override public void close() { supervisor.close(); }
    @Override public void process(ChatRequest request) {
        if(request==null || blank(request.getSessionId()) || blank(request.getQuery())) throw new IllegalArgumentException("sessionId 和 query 不能为空");
        if(request.getSessionId().length()>128 || request.getQuery().length()>32768) throw new IllegalArgumentException("输入过长");
        String turnId=blank(request.getTurnId())?UUID.randomUUID().toString():request.getTurnId().trim();
        if(turnId.length()>128) throw new IllegalArgumentException("turnId 过长");
        request.setTurnId(turnId);
        AgentContext context=AgentContext.builder().sessionId(request.getSessionId()).turnId(turnId).query(request.getQuery())
                .effectiveQuery(request.getQuery()).deadlineMillis(System.currentTimeMillis()+turnTimeoutMillis).build();
        var rawSink=new SseAgentEventSink(context.getSessionId(),turnId,publisher,cards,memory,request.getQuery());
        context.setSink(new CancellableAgentEventSink(context,rawSink));
        // Rejection is returned to HTTP without stealing the previous turn's SSE binding.
        supervisor.submit(context,() -> run(context),rawSink::activate,rawSink::release);
    }
    private void run(AgentContext context) {
        try {
            ensureActive(context);
            if(memory!=null) {
                List<ConversationMessage> history=memory.getRecentRounds(context.getSessionId(),6);
                if(history==null || history.isEmpty()) history=memory.getRecentMessages(context.getSessionId(),12);
                context.setHistory(history==null?List.of():List.copyOf(history));
            }
            ensureActive(context);
            if(rewriter!=null && !context.getHistory().isEmpty()) {
                String value=rewriter.rewrite(context,context.getHistory()); ensureActive(context);
                if(!blank(value)) context.setEffectiveQuery(value);
            }
            Resolution resolution=null;
            for(IntentResolver resolver:resolvers) {
                ensureActive(context); Optional<Resolution> result=resolver.resolve(context); ensureActive(context);
                if(result.isPresent()) { resolution=result.get(); break; }
            }
            if(resolution instanceof Resolution.DirectReply direct) {
                context.getSink().progress("L1".equals(direct.level())?"L1_HIT":"DIRECT_REPLY","已命中快捷回复");
                text(context,direct.text());
            } else if(resolution instanceof Resolution.ToolCall tool) {
                context.getSink().progress("L1".equals(tool.level())?"L1_TOOL":"TOOL_CALL","正在调用工具: "+tool.toolRef());
                ensureActive(context); String value=tools.dispatch(tool.toolRef(),tool.jsonArgs()); ensureActive(context); text(context,value);
            } else if(resolution instanceof Resolution.Route route) {
                context.getSink().thinking("MasterAgent 正在分析诉求并规划调度链路: "+route.reason());
                executeRoute(context,route);
            } else { executeStep(context,general,null); }
            ensureActive(context);
            if(context.hasFailedStep()) context.getSink().error("STEP_FAILED: 依赖步骤执行失败，本回合未完成");
            else context.getSink().done();
        } catch(TurnAborted ex) {
            context.cancel(); context.getSink().error(ex.getMessage());
        } catch(Exception ex) {
            log.warn("[Pipeline] 回合执行失败 turnId={}",context.getTurnId(),ex);
            context.getSink().error(context.isCancelled()?"TURN_CANCELLED: 本回合已停止":"PIPELINE_FAILED: 处理失败，请检查服务状态");
        }
    }
    private void executeRoute(AgentContext context,Resolution.Route route) {
        if(route.planType()==PlanType.FALLBACK) {
            if(!blank(route.reason())) text(context,route.reason()); else executeStep(context,general,null);
            return;
        }
        List<DispatchStep> steps=route.steps();
        if(steps==null || steps.isEmpty()) { executeStep(context,general,null); return; }
        if(steps.size()>32) throw new IllegalArgumentException("调度步骤超过上限");
        context.setTotalSteps(steps.size());
        if(route.planType()==PlanType.COMPOSITE) context.getSink().progress("MULTI_AGENT_COLLAB","依次调用专家进行诊断");
        for(DispatchStep step:steps) {
            ensureActive(context);
            SubAgent selected=agents.stream().filter(a->a.supports(step.getTargetAgent())).findFirst().orElse(general);
            if(!executeStep(context,selected,step)) break;
        }
    }
    private boolean executeStep(AgentContext context,SubAgent agent,DispatchStep step) {
        ensureActive(context);
        StepResult result=agent.executeStep(context,step); ensureActive(context);
        if(result!=null && result.getStatus()==StepResult.Status.FAILED) {
            if(!context.getStepResults().contains(result)) context.addStepResult(result);
            return false;
        }
        return !context.hasFailedStep();
    }
    private void text(AgentContext context,String value) {
        if(value==null) return;
        for(int offset=0;offset<value.length();offset+=8) {
            ensureActive(context);
            if(!context.getSink().message(value.substring(offset,Math.min(value.length(),offset+8)))) {
                context.cancel(); throw new TurnAborted("CLIENT_DISCONNECTED");
            }
        }
    }
    private static void ensureActive(AgentContext context) {
        if(context.isCancelled() || Thread.currentThread().isInterrupted()) throw new TurnAborted("TURN_CANCELLED");
        if(context.isExpired(System.currentTimeMillis())) throw new TurnAborted("TURN_TIMEOUT");
    }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static final class TurnAborted extends RuntimeException { TurnAborted(String message) { super(message); } }
}
