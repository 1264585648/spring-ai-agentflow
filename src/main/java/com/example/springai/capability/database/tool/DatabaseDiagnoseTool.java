package com.example.springai.capability.database.tool;

import com.example.springai.capability.database.model.ConnectionPoolMetrics;
import com.example.springai.capability.database.model.DbDiagnoseCriteria;
import com.example.springai.capability.database.model.DbDiagnoseResult;
import com.example.springai.capability.database.model.SlowQueryItem;
import com.example.springai.capability.database.port.DatabaseDiagnosePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 数据库性能诊断原子工具 (DatabaseDiagnoseTool)
 * 职责:
 * 1. 作为一级正交原子能力提供 Spring AI 原生 @Tool 注解；
 * 2. 诊断连接池水位、长事务、阻塞慢SQL等性能瓶颈；
 * 3. 依赖 DatabaseDiagnosePort SPI，支持生产与仿真无缝切换。
 */
@Component("databaseDiagnoseTool")
@Slf4j
@RequiredArgsConstructor
public class DatabaseDiagnoseTool {

    private final DatabaseDiagnosePort databaseDiagnosePort;

    @Tool(description = "诊断目标微服务数据库运行指标，排查连接池打满水位、正在运行的阻塞慢SQL会话与长事务")
    public String diagnoseDatabase(String service, Long slowThresholdMs) {
        log.info("[DatabaseDiagnoseTool] 收到数据库诊断请求: service={}, threshold={}ms", service, slowThresholdMs);
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
}
