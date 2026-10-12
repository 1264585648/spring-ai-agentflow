package com.example.springai.core.pipeline.impl;

import com.example.springai.core.agent.GeneralChatAgent;
import com.example.springai.core.agent.SubAgent;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.pipeline.AgentPipelineService;
import com.example.springai.core.routing.IntentResolver;
import com.example.springai.core.routing.L1RuleMatcher;
import com.example.springai.core.routing.MasterAgentRouter;
import com.example.springai.core.routing.Resolution;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.routing.dto.PlanType;
import com.example.springai.core.routing.impl.L1RuleResolver;
import com.example.springai.core.routing.impl.L2VectorResolver;
import com.example.springai.core.routing.impl.L3MasterAgentResolver;
import com.example.springai.core.tool.L1ToolDispatcher;
import com.example.springai.infra.sse.SseAgentEventSink;
import com.example.springai.infra.sse.SseEventPublisher;
import com.example.springai.web.dto.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 企业级通用智能协同执行流水线实现 (AgentPipelineServiceImpl)
 * 职责:
 * 1. 意图解析责任链: 遍历有序的 IntentResolver 链 (L1 ➔ L2 ➔ L3)，按需短路与降级;
 * 2. 统一决议调度: 针对 Resolution 的三种物理动作 (DirectReply / ToolCall / Route) 执行标准化调度;
 * 3. 纯净微内核调度: 动态匹配 Spring 容器中的 SubAgent 插件并派发，内核 100% 零业务依赖;
 * 4. 全链路可观测: 统一通过 AgentContext 与 AgentEventSink 回传全生命周期事件。
 */
@Service
@Slf4j
public class AgentPipelineServiceImpl implements AgentPipelineService {

    private static final int STREAM_CHUNK_SIZE = 8;
    private static final long L1_CHUNK_DELAY_MS = 12L;

    private final SseEventPublisher ssePublisher;
    private final List<IntentResolver> intentResolvers;
    private final L1ToolDispatcher l1ToolDispatcher;
    private final List<SubAgent> subAgents;
    private final GeneralChatAgent defaultGeneralAgent;
    private final com.example.springai.core.card.CardRegistry cardRegistry;
    private final Executor pipelineExecutor;

    /**
     * 主构造器：基于 Spring 标准责任链与 CardRegistry 注入
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentPipelineServiceImpl(
            SseEventPublisher ssePublisher,
            List<IntentResolver> intentResolvers,
            L1ToolDispatcher l1ToolDispatcher,
            List<SubAgent> subAgents,
            GeneralChatAgent defaultGeneralAgent,
            com.example.springai.core.card.CardRegistry cardRegistry,
            @Qualifier("agentPipelineExecutor") Executor pipelineExecutor) {
        this.ssePublisher = ssePublisher;
        this.intentResolvers = intentResolvers != null ? intentResolvers : Collections.emptyList();
        this.l1ToolDispatcher = l1ToolDispatcher;
        this.subAgents = subAgents != null ? subAgents : Collections.emptyList();
        this.defaultGeneralAgent = defaultGeneralAgent;
        this.cardRegistry = cardRegistry;
        this.pipelineExecutor = pipelineExecutor;
    }

    /**
     * 兼容性构造器：不传递 CardRegistry
     */
    public AgentPipelineServiceImpl(
            SseEventPublisher ssePublisher,
            List<IntentResolver> intentResolvers,
            L1ToolDispatcher l1ToolDispatcher,
            List<SubAgent> subAgents,
            GeneralChatAgent defaultGeneralAgent,
            @Qualifier("agentPipelineExecutor") Executor pipelineExecutor) {
        this(ssePublisher, intentResolvers, l1ToolDispatcher, subAgents, defaultGeneralAgent, null, pipelineExecutor);
    }

    /**
     * 兼容性构造器：允许外部或测试直接传递 L1RuleMatcher 与 MasterAgentRouter
     */
    public AgentPipelineServiceImpl(
            SseEventPublisher ssePublisher,
            L1RuleMatcher l1RuleMatcher,
            L1ToolDispatcher l1ToolDispatcher,
            MasterAgentRouter masterAgentRouter,
            List<SubAgent> subAgents,
            GeneralChatAgent defaultGeneralAgent,
            @Qualifier("agentPipelineExecutor") Executor pipelineExecutor) {
        this(
                ssePublisher,
                List.of(
                        new L1RuleResolver(l1RuleMatcher),
                        new L2VectorResolver(),
                        new L3MasterAgentResolver(masterAgentRouter)
                ),
                l1ToolDispatcher,
                subAgents,
                defaultGeneralAgent,
                null,
                pipelineExecutor
        );
    }

    @Override
    public void process(ChatRequest request) {
        String sessionId = request.getSessionId();
        String query = request.getQuery();
        String turnId = UUID.randomUUID().toString();
        log.info("[Pipeline] 启动处理会话: {}, 回合: {}, 问题: {}", sessionId, turnId, query);

        AgentContext context = AgentContext.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .query(query)
                .effectiveQuery(query)
                .sink(new SseAgentEventSink(sessionId, ssePublisher, cardRegistry))
                .build();

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 遍历意图解析责任链，逐层串行降级 (L1 -> L2 -> L3)
                Resolution resolution = null;
                for (IntentResolver resolver : intentResolvers) {
                    Optional<Resolution> opt = resolver.resolve(context);
                    if (opt.isPresent()) {
                        resolution = opt.get();
                        log.info("[Pipeline] 意图解析命中层级: {}, 决议类型: {}", resolver.level(), resolution.getClass().getSimpleName());
                        break;
                    }
                }

                if (resolution == null) {
                    log.warn("[Pipeline] 意图解析责任链未产生决议，由默认通用智能体兜底");
                    defaultGeneralAgent.execute(context, null);
                    context.getSink().done();
                    return;
                }

                // 2. 根据统一决议契约执行标准化物理动作
                if (resolution instanceof Resolution.DirectReply direct) {
                    executeDirectReply(context, sessionId, direct);
                } else if (resolution instanceof Resolution.ToolCall tool) {
                    executeToolCall(context, sessionId, tool);
                } else if (resolution instanceof Resolution.Route route) {
                    executeRoute(context, sessionId, route);
                } else {
                    context.getSink().error("不支持的决议类型: " + resolution.getClass().getName());
                    context.getSink().done();
                }

            } catch (Exception ex) {
                log.error("[Pipeline] 流水线执行异常, sessionId: {}", sessionId, ex);
                context.getSink().error("流水线执行异常: " + ex.getMessage());
            }
        }, pipelineExecutor);
    }

    /**
     * 执行纯文本直出决议 (L1 静态指令、L2 权威 FAQ 直出)
     */
    private void executeDirectReply(AgentContext context, String sessionId, Resolution.DirectReply direct) {
        log.info("[Pipeline] 执行纯文本直出决议, level={}, ruleCode={}", direct.level(), direct.ruleCode());
        String stage = "L1".equalsIgnoreCase(direct.level()) ? "L1_HIT" : "DIRECT_REPLY";
        String desc = "L1".equalsIgnoreCase(direct.level()) ? "已命中 L1 快捷指令，毫秒级直出" : "命中标准答案，极速直出";

        if (!context.getSink().progress(stage, desc)) {
            return;
        }
        if (!streamText(context, direct.text(), L1_CHUNK_DELAY_MS)) {
            return;
        }
        context.getSink().done();
    }

    /**
     * 执行命令工具直通决议 (L1 精准命令直接反射执行 Spring @Tool)
     */
    private void executeToolCall(AgentContext context, String sessionId, Resolution.ToolCall tool) {
        log.info("[Pipeline] 执行工具直通决议, level={}, ruleCode={}, toolRef={}", tool.level(), tool.ruleCode(), tool.toolRef());
        String stage = "L1".equalsIgnoreCase(tool.level()) ? "L1_TOOL" : "TOOL_CALL";
        if (!context.getSink().progress(stage, "已命中命令直通，正在调度工具: " + tool.toolRef())) {
            return;
        }

        String toolResult = l1ToolDispatcher.dispatch(tool.toolRef(), tool.jsonArgs());
        if (!streamText(context, toolResult, L1_CHUNK_DELAY_MS)) {
            return;
        }
        context.getSink().done();
    }

    /**
     * 执行智能体路由编排决议 (交由 SubAgent 业务专家协同或通用兜底)
     */
    private void executeRoute(AgentContext context, String sessionId, Resolution.Route route) {
        log.info("[Pipeline] 执行智能体路由决议, level={}, planType={}, reason={}", route.level(), route.planType(), route.reason());
        String thinkingMsg = "L1".equalsIgnoreCase(route.level())
                ? "正在规划路由执行: " + route.reason()
                : "未命中 L1 极速指令，MasterAgent 正在分析诉求并规划调度链路: " + route.reason();

        if (!context.getSink().thinking(thinkingMsg)) {
            return;
        }

        if (route.planType() == PlanType.COMPOSITE) {
            if (!context.getSink().progress("MULTI_AGENT_COLLAB", "启动多智能体复合协同：依次调用专业领域专家深入研判...")) {
                return;
            }
        }

        if (route.planType() == PlanType.FALLBACK) {
            if (route.reason() != null) {
                context.getSink().message(route.reason());
            } else {
                defaultGeneralAgent.execute(context, null);
            }
            context.getSink().done();
            log.info("[Pipeline] 流水线降级结束, sessionId: {}", sessionId);
            return;
        }

        List<DispatchStep> steps = route.steps();
        if (steps != null && !steps.isEmpty()) {
            for (DispatchStep step : steps) {
                String targetAgentCode = step.getTargetAgent();
                SubAgent matchedSubAgent = subAgents.stream()
                        .filter(agent -> agent.supports(targetAgentCode))
                        .findFirst()
                        .orElse(defaultGeneralAgent);

                log.debug("[Pipeline] 派发 SubAgent 插件 (step {}): {} (targetCode: {})",
                        step.getStepOrder(), matchedSubAgent.getClass().getSimpleName(), targetAgentCode);

                matchedSubAgent.execute(context, step);
            }
        } else {
            defaultGeneralAgent.execute(context, null);
        }

        context.getSink().done();
        log.info("[Pipeline] 流水线执行完成, sessionId: {}", sessionId);
    }

    /**
     * 按小块推送文本。发送失败表示连接已断开，调用方应停止本回合。
     */
    private boolean streamText(AgentContext context, String text, long delayMs) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int offset = 0; offset < text.length(); ) {
            int end = Math.min(text.length(), offset + STREAM_CHUNK_SIZE);
            if (!context.getSink().message(text.substring(offset, end))) {
                return false;
            }
            offset = end;
            if (offset < text.length() && delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }
}
