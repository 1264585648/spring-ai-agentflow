package com.example.springai.tool;

import com.example.springai.troubleshoot.dto.*;
import com.example.springai.troubleshoot.port.DatabaseDiagnosePort;
import com.example.springai.troubleshoot.port.LogQueryPort;
import com.example.springai.troubleshoot.port.TroubleshootActionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 智能运维排障工具库 (TroubleshootTool)
 * 核心设计:
 * 1. 标注 Spring AI 原生的 @Tool 注解；
 * 2. 既供大模型自主进行 Function Calling 推理调用，也可被 L1 规则引擎直接反射调度；
 * 3. 严格遵循六边形架构，所有数据操作由标准 Port SPI (LogQueryPort, DatabaseDiagnosePort, TroubleshootActionPort) 驱动；
 * 4. 内核与底层实现完全解耦，支持 Mock 与生产 ACL 无缝切换。
 */
@Component("troubleshootTool")
@Slf4j
@RequiredArgsConstructor
public class TroubleshootTool {

    private final LogQueryPort logQueryPort;
    private final DatabaseDiagnosePort databaseDiagnosePort;
    private final TroubleshootActionPort troubleshootActionPort;

    @Tool(description = "根据微服务标识与过滤条件检索异常日志，提取报错堆栈与高频错误根因")
    public String queryServiceLogs(String service, String keyword, String logLevel) {
        log.info("[TroubleshootTool] 收到日志检索请求: service={}, keyword={}, logLevel={}", service, keyword, logLevel);
        LogQueryCriteria criteria = LogQueryCriteria.builder()
                .service(service)
                .keyword(keyword)
                .logLevel(logLevel != null && !logLevel.isBlank() ? logLevel : "ERROR")
                .limit(50)
                .build();

        LogQueryResult result = logQueryPort.queryLogs(criteria);
        if (result == null) {
            return "未检索到相关日志记录。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📋 【服务日志分析报告】服务: %s | 命中异常数: %d 条\n", result.getService(), result.getTotalMatches()));

        if (result.getTopExceptions() != null && !result.getTopExceptions().isEmpty()) {
            sb.append("🔥 高频聚合异常:\n");
            for (String ex : result.getTopExceptions()) {
                sb.append("  • ").append(ex).append("\n");
            }
        }

        if (result.getDeepestStackTrace() != null) {
            sb.append("🎯 最深报错核心行: ").append(result.getDeepestStackTrace()).append("\n");
        }

        if (result.getMatchedEntries() != null && !result.getMatchedEntries().isEmpty()) {
            sb.append("\n🔍 典型堆栈采样:\n");
            for (LogEntry entry : result.getMatchedEntries()) {
                sb.append(String.format("  [%s] [%s] %s - %s\n",
                        entry.getTimestamp(), entry.getLevel(), entry.getLogger(), entry.getMessage()));
                if (entry.getStackTraceSample() != null && !entry.getStackTraceSample().isBlank()) {
                    sb.append("    堆栈摘要: ").append(entry.getStackTraceSample().trim()).append("\n");
                }
            }
        }

        return sb.toString();
    }

    @Tool(description = "诊断目标微服务数据库运行指标，排查连接池打满水位、正在运行的阻塞慢SQL会话与长事务")
    public String diagnoseDatabase(String service, Long slowThresholdMs) {
        log.info("[TroubleshootTool] 收到数据库诊断请求: service={}, threshold={}ms", service, slowThresholdMs);
        DbDiagnoseCriteria criteria = DbDiagnoseCriteria.builder()
                .service(service)
                .slowThresholdMs(slowThresholdMs != null ? slowThresholdMs : 1000L)
                .checkDeadlock(true)
                .build();

        DbDiagnoseResult result = databaseDiagnosePort.diagnoseDatabase(criteria);
        if (result == null) {
            return "未获取到数据库指标数据。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 【数据库性能与连接池诊断报告】目标服务: %s\n", result.getService()));

        if (result.getPoolMetrics() != null) {
            ConnectionPoolMetrics pool = result.getPoolMetrics();
            sb.append(String.format("⚡ 连接池水位: 活跃 %d / 最大 %d (使用率 %.1f%%), 等待线程数: %d\n",
                    pool.getActiveConnections(), pool.getMaxPoolSize(), pool.getUsageRatio() * 100, pool.getWaitingThreads()));
        }

        if (result.getActiveSlowQueries() != null && !result.getActiveSlowQueries().isEmpty()) {
            sb.append("🐢 当前运行中的慢SQL / 阻塞事务:\n");
            for (SlowQueryItem sq : result.getActiveSlowQueries()) {
                sb.append(String.format("  • 会话ID: %s | 耗时: %dms | 全表扫描: %s | 状态: %s\n    SQL: %s\n",
                        sq.getSessionProcessId(), sq.getExecutionTimeMs(), sq.getIsFullTableScan() ? "是" : "否",
                        sq.getLockStatus(), sq.getSanitizedSql()));
            }
        }

        if (result.getOptimizationAdvices() != null && !result.getOptimizationAdvices().isEmpty()) {
            sb.append("\n💡 优化与止血建议:\n");
            for (String advice : result.getOptimizationAdvices()) {
                sb.append("  • ").append(advice).append("\n");
            }
        }

        return sb.toString();
    }

    @Tool(description = "执行运维应急止血处置动作（如终止慢查会话、临时扩容连接池），产出规范运维工单")
    public String executeEmergencyAction(String service, String actionType, String targetIdentifier, String operator) {
        log.info("[TroubleshootTool] 收到应急处置指令: service={}, actionType={}, target={}, operator={}",
                service, actionType, targetIdentifier, operator);

        TroubleshootActionParam param = TroubleshootActionParam.builder()
                .service(service)
                .actionType(actionType)
                .targetIdentifier(targetIdentifier)
                .reason("通过 TroubleshootTool 执行应急处置")
                .operator(operator != null && !operator.isBlank() ? operator : "SRE-Engineer")
                .build();

        TroubleshootActionResult result = troubleshootActionPort.executeAction(param);
        if (result == null) {
            return "处置指令下发失败，未收到执行结果。";
        }

        return String.format("✅ 应急处置执行完成！单号: %s | 结果: %s",
                result.getTicketId(), result.getAuditMessage());
    }
}
