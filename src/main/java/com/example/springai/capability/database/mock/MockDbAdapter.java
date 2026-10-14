package com.example.springai.capability.database.mock;

import com.example.springai.capability.database.model.*;
import com.example.springai.capability.database.port.DatabaseDiagnosePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

/** 演示诊断数据；不得作为真实写操作依据。 */
@Component
@ConditionalOnProperty(name="troubleshoot.mode",havingValue="mock",matchIfMissing=true)
public class MockDbAdapter implements DatabaseDiagnosePort {
    @Override public boolean isSimulation() { return true; }
    @Override public DbDiagnoseResult diagnoseDatabase(DbDiagnoseCriteria criteria) {
        String service=criteria!=null && criteria.getService()!=null && !criteria.getService().isBlank()?criteria.getService().trim():"order-service";
        var pool=ConnectionPoolMetrics.builder().activeConnections(50).idleConnections(0).maxPoolSize(50).waitingThreads(28).usageRatio(1.0).build();
        var first=SlowQueryItem.builder().sessionProcessId("trx_10423").executionTimeMs(45200L)
                .sanitizedSql("SELECT * FROM t_order WHERE status = ? AND create_time >= ? ORDER BY id DESC")
                .isFullTableScan(true).lockStatus("Running (Holding record locks on t_order)").build();
        var second=SlowQueryItem.builder().sessionProcessId("trx_10428").executionTimeMs(28400L)
                .sanitizedSql("UPDATE t_order SET lock_version = lock_version + 1 WHERE status = ? AND is_deleted = 0")
                .isFullTableScan(false).lockStatus("Waiting for row lock: blocked by session trx_10423").build();
        return DbDiagnoseResult.builder().service(service).poolMetrics(pool).activeSlowQueries(List.of(first,second)).hasDeadlock(false)
                .optimizationAdvices(List.of("【缺少联合索引】建议核验 (status, create_time, id) 联合复合索引；",
                        "【演示水位】连接池 50/50，28 个线程等待；",
                        "【演示长事务】trx_10423 占用行锁，实际操作前必须重新核验。")).build();
    }
}
