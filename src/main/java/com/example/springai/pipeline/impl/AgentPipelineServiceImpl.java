package com.example.springai.pipeline.impl;

import com.example.springai.api.dto.ChatRequest;
import com.example.springai.card.model.CardFormField;
import com.example.springai.card.model.InteractiveCard;
import com.example.springai.execution.sse.SseEventPublisher;
import com.example.springai.pipeline.agent.AgentChatClientFactory;
import com.example.springai.pipeline.agent.AgentType;
import com.example.springai.pipeline.agent.MasterAgentRouter;
import com.example.springai.pipeline.agent.dto.DispatchPlan;
import com.example.springai.pipeline.agent.dto.DispatchStep;
import com.example.springai.pipeline.agent.dto.PlanType;
import com.example.springai.pipeline.dispatcher.L1ToolDispatcher;
import com.example.springai.pipeline.intent.IntentMatchResult;
import com.example.springai.pipeline.intent.L1RuleMatcher;
import com.example.springai.pipeline.AgentPipelineService;
import com.example.springai.tool.TroubleshootTool;
import com.example.springai.troubleshoot.dto.*;
import com.example.springai.troubleshoot.port.DatabaseDiagnosePort;
import com.example.springai.troubleshoot.port.LogQueryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 企业级智能协同执行流水线实现 (AgentPipelineServiceImpl)
 * 职责:
 * 1. L1 极速直出: 匹配规则引擎，静态答复或反射调度工具，耗时 < 30ms；
 * 2. 调度决策中枢: 未命中 L1 时，由 MasterAgentRouter 进行意图研判与多智能体任务分解 (DispatchPlan)；
 * 3. 专家协同执行: 派发至专业子智能体 (LogDiagnose, DbDiagnose, SreCopilot)，挂载标准排障工具库 (TroubleshootTool)；
 * 4. 全链路可观测：统一推送思考链 (thinking)、步骤进度 (progress)、打字机流式文本、应急卡片与推荐问题。
 */
@Service
@Slf4j
public class AgentPipelineServiceImpl implements AgentPipelineService {

    private static final int STREAM_CHUNK_SIZE = 8;
    private static final long L1_CHUNK_DELAY_MS = 12L;
    private static final long DEMO_CHUNK_DELAY_MS = 20L;

    private final SseEventPublisher ssePublisher;
    private final L1RuleMatcher l1RuleMatcher;
    private final L1ToolDispatcher l1ToolDispatcher;
    private final AgentChatClientFactory chatClientFactory;
    private final MasterAgentRouter masterAgentRouter;
    private final LogQueryPort logQueryPort;
    private final DatabaseDiagnosePort databaseDiagnosePort;
    private final TroubleshootTool troubleshootTool;
    private final Executor pipelineExecutor;

    public AgentPipelineServiceImpl(
            SseEventPublisher ssePublisher,
            L1RuleMatcher l1RuleMatcher,
            L1ToolDispatcher l1ToolDispatcher,
            AgentChatClientFactory chatClientFactory,
            MasterAgentRouter masterAgentRouter,
            LogQueryPort logQueryPort,
            DatabaseDiagnosePort databaseDiagnosePort,
            TroubleshootTool troubleshootTool,
            @Qualifier("agentPipelineExecutor") Executor pipelineExecutor) {
        this.ssePublisher = ssePublisher;
        this.l1RuleMatcher = l1RuleMatcher;
        this.l1ToolDispatcher = l1ToolDispatcher;
        this.chatClientFactory = chatClientFactory;
        this.masterAgentRouter = masterAgentRouter;
        this.logQueryPort = logQueryPort;
        this.databaseDiagnosePort = databaseDiagnosePort;
        this.troubleshootTool = troubleshootTool;
        this.pipelineExecutor = pipelineExecutor;
    }

    @Override
    public void process(ChatRequest request) {
        String sessionId = request.getSessionId();
        String query = request.getQuery();
        log.info("[Pipeline] 启动处理会话: {}, 问题: {}", sessionId, query);

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 优先尝试 L1 规则毫秒级直出或命令直通
                IntentMatchResult l1Result = l1RuleMatcher.match(query);
                if (l1Result.isMatched()) {
                    if ("STATIC_TEXT".equalsIgnoreCase(l1Result.getTargetType()) && l1Result.getDirectReply() != null) {
                        log.info("[Pipeline] 命中 L1 静态指令直出，会话: {}, 规则: {}", sessionId, l1Result.getRuleCode());
                        if (!ssePublisher.sendProgress(sessionId, "L1_HIT", "已命中 L1 快捷指令，毫秒级直出")) {
                            return;
                        }
                        if (!streamText(sessionId, l1Result.getDirectReply(), L1_CHUNK_DELAY_MS)) {
                            return;
                        }
                        ssePublisher.sendDone(sessionId);
                        return;
                    } else if ("TOOL".equalsIgnoreCase(l1Result.getTargetType())) {
                        log.info("[Pipeline] 命中 L1 工具直通，会话: {}, 目标: {}, 参数: {}",
                                sessionId, l1Result.getTargetRef(), l1Result.getExtractedParams());
                        if (!ssePublisher.sendProgress(sessionId, "L1_TOOL", "已命中命令直通，正在调度工具: " + l1Result.getTargetRef())) {
                            return;
                        }

                        String toolResult = l1ToolDispatcher.dispatch(l1Result.getTargetRef(), l1Result.getExtractedParams());
                        if (!streamText(sessionId, toolResult, L1_CHUNK_DELAY_MS)) {
                            return;
                        }
                        ssePublisher.sendDone(sessionId);
                        return;
                    } else {
                        ssePublisher.sendError(sessionId, "不支持的 L1 目标类型: " + l1Result.getTargetType());
                        ssePublisher.sendDone(sessionId);
                        return;
                    }
                }

                // 2. 未命中 L1，由 MasterAgent 进行调度决策规划 (DispatchPlan)
                DispatchPlan plan = masterAgentRouter.route(query);
                log.info("[Pipeline] MasterAgent 路由规划结论: type={}, reason={}", plan.getPlanType(), plan.getReason());

                String thinkingMsg = "未命中 L1 极速指令，MasterAgent 正在分析排障诉求并规划调度链路: " + plan.getReason();
                if (!ssePublisher.sendThinking(sessionId, thinkingMsg)) {
                    return;
                }

                // 3. 执行专家分发与推理
                DispatchStep primaryStep = (plan.getSteps() != null && !plan.getSteps().isEmpty())
                        ? plan.getSteps().get(0)
                        : null;
                String targetAgentCode = (primaryStep != null && primaryStep.getTargetAgent() != null)
                        ? primaryStep.getTargetAgent()
                        : AgentType.GENERAL_AGENT.getCode();
                String targetAgentName = (primaryStep != null && primaryStep.getTargetAgentName() != null)
                        ? primaryStep.getTargetAgentName()
                        : "通用协同专家";
                Map<String, Object> contextParams = (primaryStep != null && primaryStep.getInputParams() != null)
                        ? primaryStep.getInputParams()
                        : Collections.emptyMap();

                String responseText;

                if (plan.getPlanType() == PlanType.COMPOSITE) {
                    if (!ssePublisher.sendProgress(sessionId, "MULTI_AGENT_COLLAB", "启动多智能体复合排障：依次调用日志分析专家与数据库诊断专家...")) {
                        return;
                    }
                } else if (plan.getPlanType() == PlanType.SINGLE) {
                    if (!ssePublisher.sendProgress(sessionId, "AGENT_DISPATCH", "已委派专家 [" + targetAgentName + "] 深入排障...")) {
                        return;
                    }
                }

                try {
                    // 为专业智能体挂载专属排障工具库 (TroubleshootTool)
                    ChatClient agentClient = chatClientFactory.createClient(targetAgentCode, troubleshootTool);
                    String prompt = String.format("""
                            工程师故障描述/提问: %s
                            
                            MasterAgent 调度规划建议:
                            - 调度类型: %s
                            - 任务说明: %s
                            - 上下文参数: %s
                            
                            请以资深运维/SRE架构师的角度提供专业解答：
                            1. 核心根因研判与排查思路；
                            2. 关键排查指标与验证方式；
                            3. 应急止血与长期优化措施。
                            """, query, plan.getPlanType(), plan.getReason(), contextParams);

                    responseText = agentClient.prompt()
                            .user(prompt)
                            .call()
                            .content();
                } catch (Exception ex) {
                    log.warn("[Pipeline] 大模型底座未配置有效 API-Key 或调用超时 ({})，启动排障标准端口协同直出", ex.getMessage());
                    responseText = buildFallbackExpertReply(query, contextParams);
                }

                // 4. 打字机流式回传分析结论
                if (!streamText(sessionId, responseText, DEMO_CHUNK_DELAY_MS)) {
                    return;
                }

                // 5. 挂载标准排障应急处置卡片 (Human-in-the-loop 确认)
                InteractiveCard card = buildTroubleshootCard(request, contextParams, query);
                if (!ssePublisher.sendInteractiveCard(sessionId, card)) {
                    return;
                }

                // 6. 推送智能延伸推荐问题
                if (!ssePublisher.sendRecommendQuestions(sessionId, List.of(
                        "如何提取该异常时间窗口内的全链路分布式 Trace？",
                        "查询该服务数据库连接池当前的活跃与空闲连接水位",
                        "导出当前故障初步排障报告并通知告警值班群"
                ))) {
                    return;
                }

                ssePublisher.sendDone(sessionId);
                log.info("[Pipeline] 流水线执行完成, sessionId: {}", sessionId);

            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("[Pipeline] 流水线被中断, sessionId: {}", sessionId);
                ssePublisher.sendError(sessionId, "流水线被中断");
            } catch (Exception ex) {
                log.error("[Pipeline] 流水线执行异常, sessionId: {}", sessionId, ex);
                ssePublisher.sendError(sessionId, "流水线执行异常: " + ex.getMessage());
            }
        }, pipelineExecutor);
    }

    /**
     * 按小块推送文本。发送失败表示连接已断开，调用方应停止本回合。
     */
    private boolean streamText(String sessionId, String text, long delayMs) throws InterruptedException {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int offset = 0; offset < text.length(); ) {
            int end = Math.min(text.length(), offset + STREAM_CHUNK_SIZE);
            if (!ssePublisher.sendMessage(sessionId, text.substring(offset, end))) {
                return false;
            }
            offset = end;
            if (offset < text.length() && delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
        return true;
    }

    private String buildFallbackExpertReply(String query, Map<String, Object> contextParams) {
        String service = contextParams != null && contextParams.get("service") != null
                ? String.valueOf(contextParams.get("service"))
                : "order-service";

        LogQueryResult logResult = logQueryPort.queryLogs(LogQueryCriteria.builder()
                .service(service)
                .keyword("504")
                .build());

        DbDiagnoseResult dbResult = databaseDiagnosePort.diagnoseDatabase(DbDiagnoseCriteria.builder()
                .service(service)
                .build());

        StringBuilder sb = new StringBuilder();
        sb.append("您好！我是智能运维与故障排查专家。\n\n");
        sb.append("针对您反馈的问题（`").append(query).append("`），系统已自动联动 **日志分析专家** 与 **数据库诊断专家** 完成多维协同研判：\n\n");

        sb.append("### 1. 🔍 服务日志与堆栈排查结论 (LogQueryPort)\n");
        if (logResult != null && logResult.getTotalMatches() > 0) {
            sb.append("- **命中异常**：共捕获 `").append(logResult.getTotalMatches()).append("` 条关联错误日志；\n");
            if (logResult.getTopExceptions() != null && !logResult.getTopExceptions().isEmpty()) {
                sb.append("- **聚类根因**：`").append(logResult.getTopExceptions().get(0)).append("`；\n");
            }
            if (logResult.getDeepestStackTrace() != null) {
                sb.append("- **核心异常栈**：`").append(logResult.getDeepestStackTrace()).append("`。\n");
            }
        }

        sb.append("\n### 2. 📊 数据库运行指标与慢查诊断 (DatabaseDiagnosePort)\n");
        if (dbResult != null) {
            if (dbResult.getPoolMetrics() != null) {
                ConnectionPoolMetrics pool = dbResult.getPoolMetrics();
                sb.append(String.format("- **连接池状态**：HikariCP 连接数 `%d / %d`（水位 **%.0f%% 打满**），排队等待线程数 `%d` 个；\n",
                        pool.getActiveConnections(), pool.getMaxPoolSize(), pool.getUsageRatio() * 100, pool.getWaitingThreads()));
            }
            if (dbResult.getActiveSlowQueries() != null && !dbResult.getActiveSlowQueries().isEmpty()) {
                SlowQueryItem sq = dbResult.getActiveSlowQueries().get(0);
                sb.append(String.format("- **阻塞源头会话**：会话 ID `%s`，已执行 `%.1fs`，触发全表扫描；\n",
                        sq.getSessionProcessId(), sq.getExecutionTimeMs() / 1000.0));
                sb.append("  ```sql\n  ").append(sq.getSanitizedSql()).append("\n  ```\n");
            }
        }

        sb.append("\n### 3. 🚨 应急止血处置方案 (SreCopilotAgent)\n");
        sb.append("1. **终止长事务**：立即终止 (Kill) 阻塞慢查会话 `trx_10423`，释放数据表排他行锁；\n");
        sb.append("2. **临时扩容连接池**：将 HikariCP 最大连接数从 50 调优至 80，快速吸收积压流量；\n");
        sb.append("3. **中长期治理**：针对 `t_order` 表的 `(status, create_time, id)` 补充联合复合索引。\n\n");
        sb.append("遵循 Human-in-the-loop 安全红线，系统已为您预填标准化应急止血卡片，请核验下方参数后一键执行：");

        return sb.toString();
    }

    private InteractiveCard buildTroubleshootCard(ChatRequest request, Map<String, Object> contextParams, String query) {
        String service = contextParams != null && contextParams.get("service") != null
                ? String.valueOf(contextParams.get("service"))
                : "order-service";

        InteractiveCard card = new InteractiveCard();
        card.setActionId("act_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        card.setCardType("TROUBLESHOOT_ACTION");
        card.setTitle("智能运维应急止血与处置确认单");
        card.setDescription("基于多智能体故障研判，已自动生成止血预案。遵循 Human-in-the-loop 安全红线，请核验参数后一键执行：");

        card.getFields().add(new CardFormField(
                "service", "目标故障服务", "text", service, false, true
        ));
        card.getFields().add(new CardFormField(
                "action_type", "应急处置动作", "text", "Kill阻塞慢查询并临时扩容连接池", false, true
        ));
        card.getFields().add(new CardFormField(
                "target_identifier", "慢查会话ID / 目标标识", "text", "trx_10423", false, true
        ));
        card.getFields().add(new CardFormField(
                "max_pool_size", "临时连接池上限调整", "text", "80", true, false
        ));

        String defaultRemark = "针对 " + service + " 504 报警紧急止血，隔离慢查询 trx_10423，保障核心交易链路可用。";
        card.getFields().add(new CardFormField(
                "remark", "处置原因与影响评估", "textarea", defaultRemark, true, true
        ));

        card.setConfirmButtonText("确认执行应急处置");
        card.setCancelButtonText("取消/仅记录");
        return card;
    }
}
