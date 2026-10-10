package com.example.springai.core.pipeline.impl;

import com.example.springai.core.agent.AgentType;
import com.example.springai.core.agent.GeneralChatAgent;
import com.example.springai.core.agent.SubAgent;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.pipeline.AgentPipelineService;
import com.example.springai.core.routing.IntentMatchResult;
import com.example.springai.core.routing.L1RuleMatcher;
import com.example.springai.core.routing.MasterAgentRouter;
import com.example.springai.core.routing.dto.DispatchPlan;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.routing.dto.PlanType;
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
 * 1. L1 极速直出: 匹配规则引擎，静态答复或反射调度工具，耗时 < 30ms；
 * 2. 调度决策中枢: 未命中 L1 时，由 MasterAgentRouter 进行意图研判与多智能体任务分解 (DispatchPlan)；
 * 3. 纯净微内核调度: 动态匹配 Spring 容器中的 SubAgent 插件并派发，内核 100% 零业务依赖；
 * 4. 全链路可观测: 统一通过 AgentContext 与 AgentEventSink 回传全生命周期事件。
 */
@Service
@Slf4j
public class AgentPipelineServiceImpl implements AgentPipelineService {

    private static final int STREAM_CHUNK_SIZE = 8;
    private static final long L1_CHUNK_DELAY_MS = 12L;

    private final SseEventPublisher ssePublisher;
    private final L1RuleMatcher l1RuleMatcher;
    private final L1ToolDispatcher l1ToolDispatcher;
    private final MasterAgentRouter masterAgentRouter;
    private final List<SubAgent> subAgents;
    private final GeneralChatAgent defaultGeneralAgent;
    private final Executor pipelineExecutor;

    public AgentPipelineServiceImpl(
            SseEventPublisher ssePublisher,
            L1RuleMatcher l1RuleMatcher,
            L1ToolDispatcher l1ToolDispatcher,
            MasterAgentRouter masterAgentRouter,
            List<SubAgent> subAgents,
            GeneralChatAgent defaultGeneralAgent,
            @Qualifier("agentPipelineExecutor") Executor pipelineExecutor) {
        this.ssePublisher = ssePublisher;
        this.l1RuleMatcher = l1RuleMatcher;
        this.l1ToolDispatcher = l1ToolDispatcher;
        this.masterAgentRouter = masterAgentRouter;
        this.subAgents = subAgents != null ? subAgents : Collections.emptyList();
        this.defaultGeneralAgent = defaultGeneralAgent;
        this.pipelineExecutor = pipelineExecutor;
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
                .sink(new SseAgentEventSink(sessionId, ssePublisher))
                .build();

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 优先尝试 L1 规则毫秒级直出或命令直通
                IntentMatchResult l1Result = l1RuleMatcher.match(query);
                if (l1Result.isMatched()) {
                    if ("STATIC_TEXT".equalsIgnoreCase(l1Result.getTargetType()) && l1Result.getDirectReply() != null) {
                        log.info("[Pipeline] 命中 L1 静态指令直出，会话: {}, 规则: {}", sessionId, l1Result.getRuleCode());
                        if (!context.getSink().progress("L1_HIT", "已命中 L1 快捷指令，毫秒级直出")) {
                            return;
                        }
                        if (!streamText(context, l1Result.getDirectReply(), L1_CHUNK_DELAY_MS)) {
                            return;
                        }
                        context.getSink().done();
                        return;
                    } else if ("TOOL".equalsIgnoreCase(l1Result.getTargetType())) {
                        log.info("[Pipeline] 命中 L1 工具直通，会话: {}, 目标: {}, 参数: {}",
                                sessionId, l1Result.getTargetRef(), l1Result.getExtractedParams());
                        if (!context.getSink().progress("L1_TOOL", "已命中命令直通，正在调度工具: " + l1Result.getTargetRef())) {
                            return;
                        }

                        String toolResult = l1ToolDispatcher.dispatch(l1Result.getTargetRef(), l1Result.getExtractedParams());
                        if (!streamText(context, toolResult, L1_CHUNK_DELAY_MS)) {
                            return;
                        }
                        context.getSink().done();
                        return;
                    } else {
                        context.getSink().error("不支持的 L1 目标类型: " + l1Result.getTargetType());
                        context.getSink().done();
                        return;
                    }
                }

                // 2. 未命中 L1，由 MasterAgent 进行调度决策规划 (DispatchPlan)
                DispatchPlan plan = masterAgentRouter.route(query);
                log.info("[Pipeline] MasterAgent 路由规划结论: type={}, reason={}", plan.getPlanType(), plan.getReason());

                String thinkingMsg = "未命中 L1 极速指令，MasterAgent 正在分析诉求并规划调度链路: " + plan.getReason();
                if (!context.getSink().thinking(thinkingMsg)) {
                    return;
                }

                if (plan.getPlanType() == PlanType.COMPOSITE) {
                    if (!context.getSink().progress("MULTI_AGENT_COLLAB", "启动多智能体复合协同：依次调用专业领域专家深入研判...")) {
                        return;
                    }
                }

                // 3. 提取执行步骤并派发至匹配的 SubAgent 插件
                DispatchStep primaryStep = (plan.getSteps() != null && !plan.getSteps().isEmpty())
                        ? plan.getSteps().get(0)
                        : null;
                String targetAgentCode = (primaryStep != null && primaryStep.getTargetAgent() != null)
                        ? primaryStep.getTargetAgent()
                        : AgentType.GENERAL_AGENT.getCode();

                SubAgent matchedSubAgent = subAgents.stream()
                        .filter(agent -> agent.supports(targetAgentCode))
                        .findFirst()
                        .orElse(defaultGeneralAgent);

                log.debug("[Pipeline] 匹配到 SubAgent 插件: {} (targetCode: {})",
                        matchedSubAgent.getClass().getSimpleName(), targetAgentCode);

                // 4. 执行场景插件业务逻辑
                matchedSubAgent.execute(context, primaryStep);

                // 5. 结束当前对话回合
                context.getSink().done();
                log.info("[Pipeline] 流水线执行完成, sessionId: {}", sessionId);

            } catch (Exception ex) {
                log.error("[Pipeline] 流水线执行异常, sessionId: {}", sessionId, ex);
                context.getSink().error("流水线执行异常: " + ex.getMessage());
            }
        }, pipelineExecutor);
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
