package com.example.springai.core.pipeline.impl;

import com.example.springai.core.agent.GeneralChatAgent;
import com.example.springai.core.agent.SubAgent;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.memory.ConversationMemory;
import com.example.springai.core.memory.ConversationMessage;
import com.example.springai.core.pipeline.AgentPipelineService;
import com.example.springai.core.routing.IntentResolver;
import com.example.springai.core.routing.L1RuleMatcher;
import com.example.springai.core.routing.MasterAgentRouter;
import com.example.springai.core.routing.QueryRewriter;
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
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 企业级通用智能协同执行流水线实现 (AgentPipelineServiceImpl)
 * 职责:
 * 1. 会话记忆与意图改写: 提取多轮历史，执行 QueryRewriter 指代消除与实体补齐，生成有效提问 (effectiveQuery);
 * 2. 意图解析责任链: 遍历有序的 IntentResolver 链 (L1 ➔ L2 ➔ L3)，按需短路与降级;
 * 3. 统一决议调度: 针对 Resolution 的三种物理动作 (DirectReply / ToolCall / Route) 执行标准化调度;
 * 4. 纯净微内核调度: 动态匹配 Spring 容器中的 SubAgent 插件并派发，内核 100% 零业务依赖;
 * 5. 全链路可观测: 统一通过 AgentContext 与 AgentEventSink 回传全生命周期事件并归档问答记忆。
 */
@Service
@Slf4j
public class AgentPipelineServiceImpl implements AgentPipelineService {

    private static final int STREAM_CHUNK_SIZE = 8;
    private static final long L1_CHUNK_DELAY_MS = 12L;
    private static final int MAX_HISTORY_ROUNDS = 6;
    public static final long DEFAULT_TURN_TIMEOUT_MS = 60_000L;

    private final SseEventPublisher ssePublisher;
    private final List<IntentResolver> intentResolvers;
    private final L1ToolDispatcher l1ToolDispatcher;
    private final List<SubAgent> subAgents;
    private final GeneralChatAgent defaultGeneralAgent;
    private final com.example.springai.core.card.CardRegistry cardRegistry;
    private final ConversationMemory conversationMemory;
    private final QueryRewriter queryRewriter;
    private final Executor pipelineExecutor;

    private volatile long turnTimeoutMillis = DEFAULT_TURN_TIMEOUT_MS;
    private final java.util.concurrent.ConcurrentHashMap<String, AgentContext> activeTurnsBySession =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong rejectedTurnCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong timeoutTurnCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cancelledTurnCount = new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * 主构造器：基于 Spring 标准责任链、CardRegistry、ConversationMemory 与 QueryRewriter 注入
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentPipelineServiceImpl(
            SseEventPublisher ssePublisher,
            List<IntentResolver> intentResolvers,
            L1ToolDispatcher l1ToolDispatcher,
            List<SubAgent> subAgents,
            GeneralChatAgent defaultGeneralAgent,
            com.example.springai.core.card.CardRegistry cardRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ConversationMemory conversationMemory,
            @org.springframework.beans.factory.annotation.Autowired(required = false) QueryRewriter queryRewriter,
            @Qualifier("agentPipelineExecutor") Executor pipelineExecutor) {
        this.ssePublisher = ssePublisher;
        this.intentResolvers = intentResolvers != null ? intentResolvers : Collections.emptyList();
        this.l1ToolDispatcher = l1ToolDispatcher;
        this.subAgents = subAgents != null ? subAgents : Collections.emptyList();
        this.defaultGeneralAgent = defaultGeneralAgent;
        this.cardRegistry = cardRegistry;
        this.conversationMemory = conversationMemory;
        this.queryRewriter = queryRewriter;
        this.pipelineExecutor = pipelineExecutor;
    }

    /**
     * 兼容性构造器：不传递 Memory 和 Rewriter
     */
    public AgentPipelineServiceImpl(
            SseEventPublisher ssePublisher,
            List<IntentResolver> intentResolvers,
            L1ToolDispatcher l1ToolDispatcher,
            List<SubAgent> subAgents,
            GeneralChatAgent defaultGeneralAgent,
            com.example.springai.core.card.CardRegistry cardRegistry,
            @Qualifier("agentPipelineExecutor") Executor pipelineExecutor) {
        this(ssePublisher, intentResolvers, l1ToolDispatcher, subAgents, defaultGeneralAgent, cardRegistry, null, null, pipelineExecutor);
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
        this(ssePublisher, intentResolvers, l1ToolDispatcher, subAgents, defaultGeneralAgent, null, null, null, pipelineExecutor);
    }

    /**
     * 兼容性构造器：允许外部或测试直接传递 L1RuleMatcher 与 MasterAgentRouter（不隐式私建空依赖 L2VectorResolver，F03）
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
                        new L3MasterAgentResolver(masterAgentRouter)
                ),
                l1ToolDispatcher,
                subAgents,
                defaultGeneralAgent,
                null,
                null,
                null,
                pipelineExecutor
        );
    }

    public void setTurnTimeoutMillis(long turnTimeoutMillis) {
        this.turnTimeoutMillis = turnTimeoutMillis > 0 ? turnTimeoutMillis : DEFAULT_TURN_TIMEOUT_MS;
    }

    public int getActiveTurnCount() {
        return activeTurnsBySession.size();
    }

    public long getRejectedTurnCount() {
        return rejectedTurnCount.get();
    }

    public long getTimeoutTurnCount() {
        return timeoutTurnCount.get();
    }

    public long getCancelledTurnCount() {
        return cancelledTurnCount.get();
    }

    /**
     * 显式取消指定会话当前运行中的活动回合
     */
    public boolean cancelActiveTurn(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return false;
        }
        AgentContext active = activeTurnsBySession.get(sessionId);
        if (active != null) {
            active.cancel();
            cancelledTurnCount.incrementAndGet();
            return true;
        }
        return false;
    }

    @Override
    public void process(ChatRequest request) {
        String sessionId = request.getSessionId();
        String query = request.getQuery();
        String turnId = StringUtils.hasText(request.getTurnId())
                ? request.getTurnId().trim()
                : UUID.randomUUID().toString();
        request.setTurnId(turnId);
        log.info("[Pipeline] 启动处理会话: {}, 回合: {}, 问题: {}", sessionId, turnId, query);

        // 1. 提取当前会话最近历史问答记录（统一按轮数 MAX_HISTORY_ROUNDS = 6 轮 = 12 条消息截取，F04 / T09）
        List<ConversationMessage> history = Collections.emptyList();
        if (conversationMemory != null) {
            List<ConversationMessage> roundMessages = conversationMemory.getRecentRounds(sessionId, MAX_HISTORY_ROUNDS);
            if (roundMessages != null && !roundMessages.isEmpty()) {
                history = roundMessages;
            } else {
                List<ConversationMessage> fallbackMessages = conversationMemory.getRecentMessages(sessionId, MAX_HISTORY_ROUNDS * 2);
                if (fallbackMessages != null) {
                    history = fallbackMessages;
                }
            }
        }

        long deadlineMillis = System.currentTimeMillis() + turnTimeoutMillis;
        AgentContext context = AgentContext.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .query(query)
                .effectiveQuery(query)
                .history(history)
                .sink(new SseAgentEventSink(sessionId, turnId, ssePublisher, cardRegistry, conversationMemory, query))
                .deadlineMillis(deadlineMillis)
                .build();

        // 若同一会话仍有未结束的旧回合，先向旧回合传播取消信号，防止旧回合继续向流中穿插输出 (F07 / F14)
        AgentContext previousTurn = activeTurnsBySession.put(sessionId, context);
        if (previousTurn != null && !previousTurn.isCancelled()) {
            previousTurn.cancel();
            cancelledTurnCount.incrementAndGet();
            log.info("[Pipeline] 会话 {} 发起新回合 {}，已取消未完成的旧回合 {}",
                    sessionId, turnId, previousTurn.getTurnId());
        }

        try {
            CompletableFuture.runAsync(() -> {
                try {
                    if (checkTurnAborted(context, sessionId)) {
                        return;
                    }

                    // 2. 前置意图改写：若存在多轮历史，执行指代消除与实体补全
                    List<ConversationMessage> currentHistory = context.getHistory();
                    if (queryRewriter != null && currentHistory != null && !currentHistory.isEmpty()) {
                        String rewritten = queryRewriter.rewrite(context, currentHistory);
                        if (StringUtils.hasText(rewritten) && !rewritten.trim().equalsIgnoreCase(query.trim())) {
                            log.info("[Pipeline] 意图指代消除完成: '{}' ➔ '{}'", query, rewritten);
                            context.setEffectiveQuery(rewritten);
                            if (!context.getSink().thinking("检测到多轮上下文，问题已自动补全为: " + rewritten)) {
                                context.cancel();
                                cancelledTurnCount.incrementAndGet();
                                return;
                            }
                        }
                    }

                    if (checkTurnAborted(context, sessionId)) {
                        return;
                    }

                    // 3. 遍历意图解析责任链，逐层串行降级 (L1 -> L2 -> L3)
                    Resolution resolution = null;
                    for (IntentResolver resolver : intentResolvers) {
                        if (checkTurnAborted(context, sessionId)) {
                            return;
                        }
                        Optional<Resolution> opt = resolver.resolve(context);
                        if (opt.isPresent()) {
                            resolution = opt.get();
                            log.info("[Pipeline] 意图解析命中层级: {}, 决议类型: {}", resolver.level(), resolution.getClass().getSimpleName());
                            break;
                        }
                    }

                    if (resolution == null) {
                        log.warn("[Pipeline] 意图解析责任链未产生决议，由默认通用智能体兜底");
                        defaultGeneralAgent.executeStep(context, null);
                        if (!context.isCancelled()) {
                            context.getSink().done();
                        }
                        return;
                    }

                    // 4. 根据统一决议契约执行标准化物理动作
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
                    log.error("[Pipeline] 流水线执行异常, sessionId: {}, turnId: {}", sessionId, turnId, ex);
                    context.getSink().error("流水线执行异常: " + ex.getMessage());
                    context.getSink().done();
                } finally {
                    activeTurnsBySession.remove(sessionId, context);
                }
            }, pipelineExecutor);
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            rejectedTurnCount.incrementAndGet();
            activeTurnsBySession.remove(sessionId, context);
            log.warn("[Pipeline] 线程池与队列已饱和，拒绝回合执行: sessionId={}, turnId={}", sessionId, turnId);
            context.getSink().error("系统繁忙，当前排队请求过多，请稍后重试 (QUEUE_SATURATED)");
            context.getSink().done();
        }
    }

    private boolean checkTurnAborted(AgentContext context, String sessionId) {
        if (context.isCancelled()) {
            log.info("[Pipeline] 回合已取消，停止后续步骤: sessionId={}, turnId={}", sessionId, context.getTurnId());
            return true;
        }
        if (context.isExpired(System.currentTimeMillis())) {
            context.cancel();
            timeoutTurnCount.incrementAndGet();
            log.warn("[Pipeline] 回合执行超过期限，强制中止: sessionId={}, turnId={}", sessionId, context.getTurnId());
            context.getSink().error("本回合处理超时，已中止后续执行 (TURN_TIMEOUT)");
            context.getSink().done();
            return true;
        }
        return false;
    }

    /**
     * 执行纯文本直出决议 (L1 静态指令、L2 权威 FAQ 直出)
     */
    private void executeDirectReply(AgentContext context, String sessionId, Resolution.DirectReply direct) {
        log.info("[Pipeline] 执行纯文本直出决议, level={}, ruleCode={}", direct.level(), direct.ruleCode());
        String stage = "L1".equalsIgnoreCase(direct.level()) ? "L1_HIT" : "DIRECT_REPLY";
        String desc = "L1".equalsIgnoreCase(direct.level()) ? "已命中 L1 快捷指令，毫秒级直出" : "命中标准答案，极速直出";

        if (!context.getSink().progress(stage, desc)) {
            context.cancel();
            cancelledTurnCount.incrementAndGet();
            return;
        }
        if (!streamText(context, direct.text(), L1_CHUNK_DELAY_MS)) {
            context.cancel();
            cancelledTurnCount.incrementAndGet();
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
            context.cancel();
            cancelledTurnCount.incrementAndGet();
            return;
        }

        String toolResult = l1ToolDispatcher.dispatch(tool.toolRef(), tool.jsonArgs());
        if (!streamText(context, toolResult, L1_CHUNK_DELAY_MS)) {
            context.cancel();
            cancelledTurnCount.incrementAndGet();
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
            context.cancel();
            cancelledTurnCount.incrementAndGet();
            return;
        }

        if (route.planType() == PlanType.COMPOSITE) {
            if (!context.getSink().progress("MULTI_AGENT_COLLAB", "启动多智能体复合协同：依次调用专业领域专家深入研判...")) {
                context.cancel();
                cancelledTurnCount.incrementAndGet();
                return;
            }
        }

        if (route.planType() == PlanType.FALLBACK) {
            if (route.reason() != null) {
                context.getSink().message(route.reason());
            } else {
                defaultGeneralAgent.executeStep(context, null);
            }
            if (!context.isCancelled()) {
                context.getSink().done();
            }
            log.info("[Pipeline] 流水线降级结束, sessionId: {}", sessionId);
            return;
        }

        List<DispatchStep> steps = route.steps();
        if (steps != null && !steps.isEmpty()) {
            context.setTotalSteps(steps.size());
            for (DispatchStep step : steps) {
                if (checkTurnAborted(context, sessionId)) {
                    return;
                }

                String targetAgentCode = step.getTargetAgent();
                SubAgent matchedSubAgent = subAgents.stream()
                        .filter(agent -> agent.supports(targetAgentCode))
                        .findFirst()
                        .orElse(defaultGeneralAgent);

                log.debug("[Pipeline] 派发 SubAgent 插件 (step {}/{}): {} (targetCode: {})",
                        step.getStepOrder(), steps.size(), matchedSubAgent.getClass().getSimpleName(), targetAgentCode);

                com.example.springai.core.context.StepResult stepResult = matchedSubAgent.executeStep(context, step);
                if (context.isCancelled()) {
                    cancelledTurnCount.incrementAndGet();
                    log.info("[Pipeline] 步骤 {} 执行期间回合已取消，停止后续步骤: sessionId={}",
                            step.getStepOrder(), sessionId);
                    return;
                }
                if ((stepResult != null && stepResult.getStatus() == com.example.springai.core.context.StepResult.Status.FAILED)
                        || context.hasFailedStep()) {
                    log.warn("[Pipeline] 复合协同步骤 {} 执行失败 (error={})，按安全策略中止后续依赖步骤: sessionId={}",
                            step.getStepOrder(),
                            stepResult != null ? stepResult.getErrorMessage() : "STEP_FAILED",
                            sessionId);
                    break;
                }
            }
        } else {
            context.setTotalSteps(1);
            defaultGeneralAgent.executeStep(context, null);
        }

        if (!context.isCancelled()) {
            context.getSink().done();
        }
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
            if (context.isCancelledOrExpired(System.currentTimeMillis())) {
                return false;
            }
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
