package com.example.springai.troubleshoot.mock;

import com.example.springai.troubleshoot.dto.ConnectionPoolMetrics;
import com.example.springai.troubleshoot.dto.DbDiagnoseCriteria;
import com.example.springai.troubleshoot.dto.DbDiagnoseResult;
import com.example.springai.troubleshoot.dto.SlowQueryItem;
import com.example.springai.troubleshoot.port.DatabaseDiagnosePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 开源仿真数据库诊断适配器 (MockDbAdapter)
 * 职责:
 * 1. 模拟连接池打满 (50/50)、线程等待与典型慢 SQL 全表扫描场景；
 * 2. 精准输出会话 ID trx_10423，为后续 SRE 应急止血处置卡片提供数据锚点；
 * 3. 采用 @ConditionalOnProperty，企业私有环境下自动让位给私有监控系统适配器。
 */
@Component
@ConditionalOnProperty(name = "troubleshoot.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockDbAdapter implements DatabaseDiagnosePort {

    @Override
    public DbDiagnoseResult diagnoseDatabase(DbDiagnoseCriteria criteria) {
        String service = (criteria != null && criteria.getService() != null && !criteria.getService().isBlank())
                ? criteria.getService().trim()
                : "order-service";

        log.info("[MockDbAdapter] 仿真诊断数据库运行指标: service={}, datasource={}, slowThreshold={}ms",
                service,
                criteria != null ? criteria.getDatasourceName() : "default",
                criteria != null ? criteria.getSlowThresholdMs() : 1000);

        // 1. 模拟连接池 100% 打满水位
        ConnectionPoolMetrics poolMetrics = ConnectionPoolMetrics.builder()
                .activeConnections(50)
                .idleConnections(0)
                .maxPoolSize(50)
                .waitingThreads(28)
                .usageRatio(1.00)
                .build();

        // 2. 模拟当前运行的阻塞慢 SQL
        SlowQueryItem slowQuery1 = SlowQueryItem.builder()
                .sessionProcessId("trx_10423")
                .executionTimeMs(45200L)
                .sanitizedSql("SELECT * FROM t_order WHERE status = ? AND create_time >= ? ORDER BY id DESC")
                .isFullTableScan(true)
                .lockStatus("Running (Holding record locks on t_order)")
                .build();

        SlowQueryItem slowQuery2 = SlowQueryItem.builder()
                .sessionProcessId("trx_10428")
                .executionTimeMs(28400L)
                .sanitizedSql("UPDATE t_order SET lock_version = lock_version + 1 WHERE status = ? AND is_deleted = 0")
                .isFullTableScan(false)
                .lockStatus("Waiting for row lock: blocked by session trx_10423")
                .build();

        List<SlowQueryItem> slowQueries = List.of(slowQuery1, slowQuery2);

        // 3. 综合优化建议
        List<String> advices = List.of(
                "【缺少联合索引】t_order 表全表扫描导致查询耗时 45.2s，建议对 (status, create_time, id) 添加联合复合索引；",
                "【连接池水位告警】HikariCP 连接池容量已完全打满 (50/50)，28 个工作线程挂起排队，建议临时调大最大连接数至 80-100；",
                "【长事务阻塞】会话 trx_10423 占用行锁阻断后续更新，建议立即 Kill 慢查会话释放连接并止血恢复可用性。"
        );

        return DbDiagnoseResult.builder()
                .service(service)
                .poolMetrics(poolMetrics)
                .activeSlowQueries(slowQueries)
                .hasDeadlock(false)
                .optimizationAdvices(advices)
                .build();
    }
}
