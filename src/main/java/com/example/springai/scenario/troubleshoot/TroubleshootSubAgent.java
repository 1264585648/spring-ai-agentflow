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
import com.example.springai.capability.ops.tool.OpsActionTool;
import com.example.springai.core.agent.SubAgent;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.infra.client.AgentChatClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 智能运维排障场景协同 SubAgent 插件 (TroubleshootSubAgent)
 * 职责:
 * 1. 作为业务场景插件实现 SubAgent SPI，承接排障多专家协同；
 * 2. 自由编排 log, database, ops 等一级正交原子能力工具；
 * 3. 负责故障专家研判、流式分析输出、应急止血卡片装配与延伸追问生成。
 */
@Component("troubleshootSubAgent")
@Slf4j
@RequiredArgsConstructor
public class TroubleshootSubAgent implements SubAgent {

    private static final int STREAM_CHUNK_SIZE = 8;
    private static final long DEMO_CHUNK_DELAY_MS = 20L;

    private final AgentChatClientFactory chatClientFactory;
    private final LogQueryPort logQueryPort;
    private final DatabaseDiagnosePort databaseDiagnosePort;
    private final LogQueryTool logQueryTool;
    private final DatabaseDiagnoseTool databaseDiagnoseTool;
    private final OpsActionTool opsActionTool;
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
        String query = context.getQuery();
        String targetAgentCode = (step != null && step.getTargetAgent() != null)
                ? step.getTargetAgent()
                : "LOG_DIAGNOSE_AGENT";
        String targetAgentName = (step != null && step.getTargetAgentName() != null)
                ? step.getTargetAgentName()
                : "智能运维排障专家";
        Map<String, Object> contextParams = (step != null && step.getInputParams() != null)
                ? step.getInputParams()
                : Collections.emptyMap();

        log.info("[TroubleshootSubAgent] 启动排障场景协同, agent: {}, query: {}", targetAgentCode, query);

        // 1. 发送专家委派进度
        context.getSink().progress("AGENT_DISPATCH", "已委派专家 [" + targetAgentName + "] 深入排障...");

        // 2. 驱动大模型推理与工具调用（Spring AI 原生 .stream() 响应式真流式），异常时平滑降级
        boolean clientActive = true;
        try {
            ChatClient agentClient = chatClientFactory.createClient(targetAgentCode,
                    logQueryTool, databaseDiagnoseTool, opsActionTool);
            if (agentClient == null) {
                throw new IllegalStateException("ChatClient 未能正确初始化");
            }

            String prompt = String.format("""
                    工程师故障描述/提问: %s
                    
                    调度指派信息:
                    - 目标专家: %s
                    - 任务说明: %s
                    - 上下文参数: %s
                    
                    请以资深运维/SRE架构师的角度提供专业解答：
                    1. 核心根因研判与排查思路；
                    2. 关键排查指标与验证方式；
                    3. 应急止血与长期优化措施。
                    """, query, targetAgentName, step != null ? step.getTaskDesc() : "", contextParams);

            Flux<String> streamFlux = agentClient.prompt()
                    .user(prompt)
                    .stream()
                    .content();

            if (streamFlux == null) {
                throw new IllegalStateException("大模型底座未返回有效流式响应");
            }

            AtomicBoolean aborted = new AtomicBoolean(false);
            streamFlux.takeWhile(token -> !aborted.get())
                    .doOnNext(token -> {
                        if (token != null && !token.isEmpty()) {
                            boolean ok = context.getSink().message(token);
                            if (!ok) {
                                aborted.set(true);
                                log.info("[TroubleshootSubAgent] 客户端断开连接，快速熔断流式生成");
                            }
                        }
                    })
                    .blockLast();

            if (aborted.get()) {
                clientActive = false;
            }
        } catch (Exception ex) {
            log.warn("[TroubleshootSubAgent] 大模型底座未配置有效 API-Key 或调用超时 ({})，启动排障标准端口协同直出", ex.getMessage());
            String responseText = buildFallbackExpertReply(query, contextParams);
            clientActive = streamText(context, responseText, DEMO_CHUNK_DELAY_MS);
        }

        if (!clientActive) {
            log.info("[TroubleshootSubAgent] 客户端已断开或取消，中止后续交互卡片与追问下发");
            return;
        }

        // 3. 挂载标准排障应急处置卡片 (Human-in-the-loop)
        String service = contextParams.get("service") != null
                ? String.valueOf(contextParams.get("service"))
                : "order-service";
        InteractiveCard card = cardFactory.createTroubleshootCard(service, "Kill阻塞慢查询并临时扩容连接池", "trx_10423");
        if (!context.getSink().card(card)) {
            return;
        }

        // 4. 推送智能延伸推荐问题
        context.getSink().recommend(List.of(
                "如何提取该异常时间窗口内的全链路分布式 Trace？",
                "查询该服务数据库连接池当前的活跃与空闲连接水位",
                "导出当前故障初步排障报告并通知告警值班群"
        ));
    }

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
}
