package com.example.springai.scenario.troubleshoot;

import com.example.springai.capability.database.model.ConnectionPoolMetrics;
import com.example.springai.capability.database.model.DbDiagnoseCriteria;
import com.example.springai.capability.database.model.DbDiagnoseResult;
import com.example.springai.capability.database.model.SlowQueryItem;
import com.example.springai.capability.database.port.DatabaseDiagnosePort;
import com.example.springai.capability.database.tool.DatabaseDiagnoseTool;
import com.example.springai.capability.log.model.LogQueryCriteria;
import com.example.springai.capability.log.model.LogQueryResult;
import com.example.springai.capability.log.port.LogQueryPort;
import com.example.springai.capability.log.tool.LogQueryTool;
import com.example.springai.core.agent.SubAgent;
import com.example.springai.core.card.model.ActionProposal;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.context.PromptContextAssembler;
import com.example.springai.core.context.StepResult;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.infra.client.AgentChatClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能运维排障场景协同 SubAgent 插件 (TroubleshootSubAgent)
 * 职责:
 * 1. 作为业务场景插件实现 SubAgent SPI，承接排障多专家协同；
 * 2. 向模型提供 log、database 查询工具；运维写动作不通过模型工具执行；
 * 3. 结合多轮上下文、L2 知识参考与前序步骤结果 (StepResult) 组装模型输入；
 * 4. 基于真实诊断证据生成结构化 ActionProposal，缺失目标服务、纯咨询或模型异常时绝不伪造固定生产目标卡片。
 */
@Component("troubleshootSubAgent")
@Slf4j
@RequiredArgsConstructor
public class TroubleshootSubAgent implements SubAgent {

    private static final int STREAM_CHUNK_SIZE = 8;
    private static final long DEMO_CHUNK_DELAY_MS = 20L;
    private static final Duration DEFAULT_MODEL_STREAM_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern SERVICE_PATTERN = Pattern.compile("([a-zA-Z0-9_-]+-(?:service|app|server|api|gateway))", Pattern.CASE_INSENSITIVE);

    private final AgentChatClientFactory chatClientFactory;
    private final LogQueryPort logQueryPort;
    private final DatabaseDiagnosePort databaseDiagnosePort;
    private final LogQueryTool logQueryTool;
    private final DatabaseDiagnoseTool databaseDiagnoseTool;
    private final TroubleshootCardFactory cardFactory;

    @Override
    public String name() {
        return "TROUBLESHOOT_AGENT";
    }

    @Override
    public boolean supports(String agentCode) {
        if (agentCode == null) {
            return false;
        }
        return "LOG_DIAGNOSE_AGENT".equalsIgnoreCase(agentCode)
                || "DB_DIAGNOSE_AGENT".equalsIgnoreCase(agentCode)
                || "SRE_COPILOT_AGENT".equalsIgnoreCase(agentCode)
                || "TROUBLESHOOT_AGENT".equalsIgnoreCase(agentCode);
    }

    @Override
    public void execute(AgentContext context, DispatchStep step) {
        executeStep(context, step);
    }

    @Override
    public StepResult executeStep(AgentContext context, DispatchStep step) {
        int stepOrder = step != null ? step.getStepOrder() : 1;
        String targetAgentCode = (step != null && step.getTargetAgent() != null)
                ? step.getTargetAgent()
                : "LOG_DIAGNOSE_AGENT";
        String targetAgentName = (step != null && step.getTargetAgentName() != null)
                ? step.getTargetAgentName()
                : "智能运维排障专家";

        if (context == null || context.isCancelledOrExpired(System.currentTimeMillis())) {
            StepResult cancelledResult = StepResult.builder()
                    .stepOrder(stepOrder)
                    .agentCode(targetAgentCode)
                    .status(StepResult.Status.FAILED)
                    .errorCategory("CANCELLED")
                    .errorMessage("回合已取消或超时")
                    .summary("步骤未启动：回合已取消或超时")
                    .build();
            if (context != null) {
                context.addStepResult(cancelledResult);
            }
            return cancelledResult;
        }

        String rawQuery = context.getQuery() != null ? context.getQuery() : "";
        String effectiveQuery = StringUtils.hasText(context.getEffectiveQuery())
                ? context.getEffectiveQuery()
                : rawQuery;
        Map<String, Object> contextParams = (step != null && step.getInputParams() != null)
                ? step.getInputParams()
                : Collections.emptyMap();

        String service = resolveTargetService(contextParams, effectiveQuery, rawQuery);
        boolean pureConsultation = isPureConsultationQuery(effectiveQuery);

        log.info("[TroubleshootSubAgent] 启动排障场景协同, step={}, agent={}, service={}, pureConsultation={}, query={}",
                stepOrder, targetAgentCode, service, pureConsultation, effectiveQuery);

        // 1. 发送专家委派进度
        if (!context.getSink().progress("AGENT_DISPATCH", "已委派专家 [" + targetAgentName + "] 深入排障...")) {
            context.cancel();
            StepResult dcResult = StepResult.builder()
                    .stepOrder(stepOrder)
                    .agentCode(targetAgentCode)
                    .status(StepResult.Status.FAILED)
                    .errorCategory("CLIENT_DISCONNECTED")
                    .errorMessage("客户端连接已断开")
                    .build();
            context.addStepResult(dcResult);
            return dcResult;
        }

        // 2. 驱动大模型推理与工具调用（或受控离线演示模式）
        boolean clientActive = true;
        boolean offlineDemoMode = false;
        boolean modelErrorOccurred = false;
        String modelErrorMessage = null;

        ChatClient agentClient = null;
        try {
            agentClient = chatClientFactory.createClient(targetAgentCode,
                    logQueryTool, databaseDiagnoseTool);
        } catch (Exception ex) {
            modelErrorOccurred = true;
            modelErrorMessage = ex.getMessage();
            log.warn("[TroubleshootSubAgent] 创建 ChatClient 异常: {}", ex.getMessage());
        }

        if (agentClient == null && !modelErrorOccurred) {
            // 未配置在线 ChatClient（如离线演示或纯单元测试桩），进入显式标记的离线模拟诊断
            offlineDemoMode = true;
            if (!StringUtils.hasText(service)) {
                String askTargetMsg = "您好！为了保障生产环境安全，排障与应急止血必须明确指定**目标服务名称**（例如 `order-service`、`payment-service`）及异常现象。请补充目标服务名后重试。";
                clientActive = streamText(context, askTargetMsg, DEMO_CHUNK_DELAY_MS);
            } else {
                String responseText = buildFallbackExpertReply(effectiveQuery, service, context.getStepResults());
                clientActive = streamText(context, responseText, DEMO_CHUNK_DELAY_MS);
            }
        } else if (agentClient != null) {
            try {
                String prompt = PromptContextAssembler.assembleTroubleshootUserPrompt(
                        context, step, targetAgentName, contextParams);

                Flux<String> streamFlux = agentClient.prompt()
                        .user(prompt)
                        .stream()
                        .content();

                if (streamFlux == null) {
                    throw new IllegalStateException("大模型底座未返回有效流式响应");
                }

                Duration streamTimeout = resolveStreamTimeout(context);
                AtomicBoolean aborted = new AtomicBoolean(false);
                streamFlux
                        .timeout(streamTimeout)
                        .takeWhile(token -> !aborted.get() && !context.isCancelledOrExpired(System.currentTimeMillis()))
                        .doOnNext(token -> {
                            if (token != null && !token.isEmpty()) {
                                boolean ok = context.getSink().message(token);
                                if (!ok) {
                                    aborted.set(true);
                                    context.cancel();
                                    log.info("[TroubleshootSubAgent] 客户端断开连接，快速熔断流式生成");
                                }
                            }
                        })
                        .blockLast();

                if (aborted.get() || context.isCancelled()) {
                    clientActive = false;
                }
            } catch (Exception ex) {
                modelErrorOccurred = true;
                modelErrorMessage = ex.getMessage();
                log.warn("[TroubleshootSubAgent] 大模型在线推理异常 ({})，拒绝伪造已完成诊断的可执行卡片", ex.getMessage());
                String errNotice = "⚠️ 诊断模型调用异常（" + ex.getMessage() + "），未能完成在线根因核验。为防止误操作生产资源，本次不下发可执行处置卡片，请稍后重试或检查模型服务连接。";
                clientActive = streamText(context, errNotice, 0L);
            }
        } else {
            String errNotice = "⚠️ 诊断客户端初始化失败（" + modelErrorMessage + "），本次不下发可执行处置卡片。";
            clientActive = streamText(context, errNotice, 0L);
        }

        if (!clientActive || context.isCancelled()) {
            context.cancel();
            log.info("[TroubleshootSubAgent] 客户端已断开或取消，中止后续交互卡片与追问下发");
            StepResult abortedResult = StepResult.builder()
                    .stepOrder(stepOrder)
                    .agentCode(targetAgentCode)
                    .status(StepResult.Status.FAILED)
                    .errorCategory("CANCELLED")
                    .errorMessage("客户端断开连接或任务已取消")
                    .build();
            context.addStepResult(abortedResult);
            return abortedResult;
        }

        // 若模型调用异常，记录失败 StepResult 并严禁下发可执行处置卡片 (F05 / T10 / T16)
        if (modelErrorOccurred) {
            StepResult failedResult = StepResult.builder()
                    .stepOrder(stepOrder)
                    .agentCode(targetAgentCode)
                    .status(StepResult.Status.FAILED)
                    .errorCategory("MODEL_ERROR")
                    .errorMessage(modelErrorMessage)
                    .summary("模型推理异常，未完成诊断")
                    .build();
            context.addStepResult(failedResult);
            return failedResult;
        }

        // 若缺少明确的目标服务或属于纯技术咨询，只给建议/追问，不下发可执行处置卡片 (F05 / T10)
        if (!StringUtils.hasText(service) || pureConsultation) {
            StepResult consultResult = StepResult.builder()
                    .stepOrder(stepOrder)
                    .agentCode(targetAgentCode)
                    .status(StepResult.Status.SUCCESS)
                    .summary(pureConsultation ? "已完成纯技术咨询解答，无需生成写操作卡片" : "缺少明确目标服务名，已引导用户补充目标")
                    .build();
            context.addStepResult(consultResult);
            context.getSink().recommend(List.of(
                    "请指定需要排查的目标服务名称（如 order-service）",
                    "如何提取异常时间窗口内的全链路分布式 Trace？"
            ));
            return consultResult;
        }

        // 3. 采集针对目标服务 (service) 的结构化诊断证据并构建 ActionProposal
        StepResult stepResult = collectEvidenceAndBuildStepResult(
                stepOrder, targetAgentCode, service, offlineDemoMode, context.getStepResults());
        context.addStepResult(stepResult);

        // 复合任务中间步骤：将诊断证据与草案通过 StepResult 传递给后续步骤，仅在最后一步汇总下发单张卡片 (F10 / T17)
        int totalSteps = context.getTotalSteps() > 0 ? context.getTotalSteps() : 1;
        if (stepOrder < totalSteps) {
            log.info("[TroubleshootSubAgent] 复合计划步骤 {}/{} 完成，诊断证据已写入 StepResult 供下一步协同汇总",
                    stepOrder, totalSteps);
            return stepResult;
        }

        ActionProposal proposal = stepResult.getActionProposal();
        if (proposal != null && proposal.hasExecutableTarget()) {
            InteractiveCard card = cardFactory.createTroubleshootCard(proposal);
            if (!context.getSink().card(card)) {
                context.cancel();
                return stepResult;
            }
        }

        // 4. 推送智能延伸推荐问题
        context.getSink().recommend(List.of(
                "如何提取该异常时间窗口内的全链路分布式 Trace？",
                "查询该服务数据库连接池当前的活跃与空闲连接水位",
                "导出当前故障初步排障报告并通知告警值班群"
        ));
        return stepResult;
    }

    private StepResult collectEvidenceAndBuildStepResult(
            int stepOrder,
            String targetAgentCode,
            String service,
            boolean simulationMode,
            List<StepResult> previousResults) {
        Map<String, Object> structuredData = new LinkedHashMap<>();
        structuredData.put("service", service);
        List<String> evidenceList = new ArrayList<>();

        // 继承前序步骤证据
        if (previousResults != null) {
            for (StepResult prev : previousResults) {
                if (prev != null && prev.getEvidence() != null) {
                    evidenceList.addAll(prev.getEvidence());
                }
            }
        }

        LogQueryResult logResult = null;
        if (logQueryPort != null) {
            try {
                logResult = logQueryPort.queryLogs(LogQueryCriteria.builder()
                        .service(service)
                        .keyword("504")
                        .build());
            } catch (Exception ex) {
                log.warn("[TroubleshootSubAgent] 查询日志端口异常: {}", ex.getMessage());
            }
        }
        if (logResult != null && logResult.getTotalMatches() > 0) {
            structuredData.put("logTotalMatches", logResult.getTotalMatches());
            if (logResult.getTopExceptions() != null && !logResult.getTopExceptions().isEmpty()) {
                String topEx = logResult.getTopExceptions().get(0);
                structuredData.put("topException", topEx);
                evidenceList.add("日志高频异常: " + topEx + " (共 " + logResult.getTotalMatches() + " 条)");
            }
            if (StringUtils.hasText(logResult.getDeepestStackTrace())) {
                structuredData.put("deepestStackTrace", logResult.getDeepestStackTrace());
            }
        }

        DbDiagnoseResult dbResult = null;
        if (databaseDiagnosePort != null) {
            try {
                dbResult = databaseDiagnosePort.diagnoseDatabase(DbDiagnoseCriteria.builder()
                        .service(service)
                        .build());
            } catch (Exception ex) {
                log.warn("[TroubleshootSubAgent] 查询数据库诊断端口异常: {}", ex.getMessage());
            }
        }

        String targetIdentifier = null;
        if (dbResult != null) {
            if (dbResult.getPoolMetrics() != null) {
                ConnectionPoolMetrics pool = dbResult.getPoolMetrics();
                structuredData.put("activeConnections", pool.getActiveConnections());
                structuredData.put("maxPoolSize", pool.getMaxPoolSize());
                structuredData.put("poolUsageRatio", pool.getUsageRatio());
                evidenceList.add(String.format("连接池水位: %d/%d (%.0f%%)",
                        pool.getActiveConnections(), pool.getMaxPoolSize(), pool.getUsageRatio() * 100));
            }
            if (dbResult.getActiveSlowQueries() != null && !dbResult.getActiveSlowQueries().isEmpty()) {
                SlowQueryItem sq = dbResult.getActiveSlowQueries().get(0);
                targetIdentifier = sq.getSessionProcessId();
                structuredData.put("slowQuerySessionId", targetIdentifier);
                structuredData.put("slowQueryElapsedMs", sq.getExecutionTimeMs());
                evidenceList.add("阻塞慢查询会话: " + targetIdentifier + " (" + sq.getExecutionTimeMs() + "ms)");
            }
        }

        ActionProposal proposal = null;
        if (StringUtils.hasText(service) && StringUtils.hasText(targetIdentifier)) {
            String summaryText = evidenceList.isEmpty()
                    ? "针对 " + service + " 紧急止血，隔离慢查询 " + targetIdentifier
                    : "诊断依据 [" + service + "]: " + String.join("；", evidenceList);
            proposal = ActionProposal.builder()
                    .service(service)
                    .actionType("Kill阻塞慢查询并临时扩容连接池")
                    .targetIdentifier(targetIdentifier)
                    .riskLevel("HIGH")
                    .evidenceSummary(summaryText)
                    .suggestedMaxPoolSize("80")
                    .simulationMode(simulationMode)
                    .extraParameters(structuredData)
                    .build();
        }

        String stepSummary = String.format("已完成服务 [%s] 的 %s 诊断", service, targetAgentCode);
        return StepResult.builder()
                .stepOrder(stepOrder)
                .agentCode(targetAgentCode)
                .status(StepResult.Status.SUCCESS)
                .summary(stepSummary)
                .structuredData(structuredData)
                .evidence(evidenceList)
                .actionProposal(proposal)
                .build();
    }

    private String resolveTargetService(Map<String, Object> contextParams, String effectiveQuery, String rawQuery) {
        if (contextParams != null && contextParams.get("service") != null) {
            String s = String.valueOf(contextParams.get("service")).trim();
            if (StringUtils.hasText(s)) {
                return s;
            }
        }
        for (String candidate : List.of(
                effectiveQuery != null ? effectiveQuery : "",
                rawQuery != null ? rawQuery : "")) {
            Matcher matcher = SERVICE_PATTERN.matcher(candidate);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private boolean isPureConsultationQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return true;
        }
        String lower = query.trim().toLowerCase(Locale.ROOT);
        boolean hasConsultKeyword = lower.startsWith("什么是") || lower.startsWith("如何理解")
                || lower.startsWith("介绍一下") || lower.startsWith("解释一下")
                || lower.contains("概念是什么") || lower.contains("原理是什么")
                || lower.contains("纯咨询");
        boolean hasIncidentSignal = lower.contains("504") || lower.contains("500")
                || lower.contains("502") || lower.contains("503")
                || lower.contains("报警") || lower.contains("告警")
                || lower.contains("打满") || lower.contains("超时")
                || lower.contains("止血") || lower.contains("排查")
                || lower.contains("异常") || lower.contains("错误日志")
                || lower.contains("慢查询") || lower.contains("死锁");
        return hasConsultKeyword && !hasIncidentSignal;
    }

    private Duration resolveStreamTimeout(AgentContext context) {
        if (context != null && context.getAttribute("MODEL_STREAM_TIMEOUT_MS") instanceof Number num) {
            long ms = num.longValue();
            if (ms > 0) {
                return Duration.ofMillis(ms);
            }
        }
        return DEFAULT_MODEL_STREAM_TIMEOUT;
    }

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

    private String buildFallbackExpertReply(String query, String service, List<StepResult> previousSteps) {
        LogQueryResult logResult = logQueryPort.queryLogs(LogQueryCriteria.builder()
                .service(service)
                .keyword("504")
                .build());

        DbDiagnoseResult dbResult = databaseDiagnosePort.diagnoseDatabase(DbDiagnoseCriteria.builder()
                .service(service)
                .build());

        StringBuilder sb = new StringBuilder();
        sb.append("您好！我是智能运维与故障排查专家（当前运行于 **[离线演示模拟模式]**）。\n\n");
        sb.append("针对您反馈的 `").append(service).append("` 问题（`").append(query).append("`），系统已自动联动 **日志分析专家** 与 **数据库诊断专家** 完成多维协同研判：\n\n");

        if (previousSteps != null && !previousSteps.isEmpty()) {
            sb.append("### 0. 🔗 前序协同步骤已传递诊断证据\n");
            for (StepResult sr : previousSteps) {
                sb.append("- **Step ").append(sr.getStepOrder()).append(" (").append(sr.getAgentCode()).append(")**：")
                        .append(sr.getSummary()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("### 1. 🔍 服务日志与堆栈排查结论 (LogQueryPort)\n");
        if (logResult != null && logResult.getTotalMatches() > 0) {
            sb.append("- **目标服务**：`").append(service).append("`\n");
            sb.append("- **命中异常**：共捕获 `").append(logResult.getTotalMatches()).append("` 条关联错误日志；\n");
            if (logResult.getTopExceptions() != null && !logResult.getTopExceptions().isEmpty()) {
                sb.append("- **聚类根因**：`").append(logResult.getTopExceptions().get(0)).append("`；\n");
            }
            if (logResult.getDeepestStackTrace() != null) {
                sb.append("- **核心异常栈**：`").append(logResult.getDeepestStackTrace()).append("`。\n");
            }
        }

        sb.append("\n### 2. 📊 数据库运行指标与慢查诊断 (DatabaseDiagnosePort)\n");
        String slowSessionId = "trx_10423";
        if (dbResult != null) {
            if (dbResult.getPoolMetrics() != null) {
                ConnectionPoolMetrics pool = dbResult.getPoolMetrics();
                sb.append(String.format("- **连接池状态**：HikariCP 连接数 `%d / %d`（水位 **%.0f%% 打满**），排队等待线程数 `%d` 个；\n",
                        pool.getActiveConnections(), pool.getMaxPoolSize(), pool.getUsageRatio() * 100, pool.getWaitingThreads()));
            }
            if (dbResult.getActiveSlowQueries() != null && !dbResult.getActiveSlowQueries().isEmpty()) {
                SlowQueryItem sq = dbResult.getActiveSlowQueries().get(0);
                slowSessionId = sq.getSessionProcessId();
                sb.append(String.format("- **阻塞源头会话**：会话 ID `%s`，已执行 `%.1fs`，触发全表扫描；\n",
                        sq.getSessionProcessId(), sq.getExecutionTimeMs() / 1000.0));
                sb.append("  ```sql\n  ").append(sq.getSanitizedSql()).append("\n  ```\n");
            }
        }

        sb.append("\n### 3. 🚨 应急止血处置方案 (SreCopilotAgent)\n");
        sb.append("1. **终止长事务**：立即终止 (Kill) `").append(service).append("` 阻塞慢查会话 `").append(slowSessionId).append("`，释放数据表排他行锁；\n");
        sb.append("2. **临时扩容连接池**：将 HikariCP 最大连接数从 50 调优至 80，快速吸收积压流量；\n");
        sb.append("3. **中长期治理**：针对 `t_order` 表的 `(status, create_time, id)` 补充联合复合索引。\n\n");
        sb.append("遵循 Human-in-the-loop 安全红线，系统已为您预填标准化应急止血卡片，请核验下方参数后一键执行：");

        return sb.toString();
    }
}
