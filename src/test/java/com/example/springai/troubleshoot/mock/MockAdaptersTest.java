package com.example.springai.troubleshoot.mock;

import com.example.springai.troubleshoot.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MockAdaptersTest {

    private final MockLogAdapter logAdapter = new MockLogAdapter();
    private final MockDbAdapter dbAdapter = new MockDbAdapter();
    private final MockActionAdapter actionAdapter = new MockActionAdapter();

    @Test
    @DisplayName("测试 MockLogAdapter 仿真日志检索")
    void testMockLogAdapterQuery() {
        LogQueryCriteria criteria = LogQueryCriteria.builder()
                .service("order-service")
                .logLevel("ERROR")
                .keyword("504")
                .limit(50)
                .build();

        LogQueryResult result = logAdapter.queryLogs(criteria);

        assertNotNull(result);
        assertEquals("order-service", result.getService());
        assertEquals(142, result.getTotalMatches());
        assertFalse(result.getMatchedEntries().isEmpty());
        assertFalse(result.getTopExceptions().isEmpty());
        assertTrue(result.getTopExceptions().stream().anyMatch(ex -> ex.contains("HikariPool")));
        assertNotNull(result.getDeepestStackTrace());
        assertTrue(result.getDeepestStackTrace().contains("HikariPool"));
    }

    @Test
    @DisplayName("测试 MockDbAdapter 仿真数据库诊断")
    void testMockDbAdapterDiagnose() {
        DbDiagnoseCriteria criteria = DbDiagnoseCriteria.builder()
                .service("order-service")
                .datasourceName("default")
                .slowThresholdMs(1000L)
                .build();

        DbDiagnoseResult result = dbAdapter.diagnoseDatabase(criteria);

        assertNotNull(result);
        assertEquals("order-service", result.getService());
        assertNotNull(result.getPoolMetrics());
        assertEquals(50, result.getPoolMetrics().getActiveConnections());
        assertEquals(50, result.getPoolMetrics().getMaxPoolSize());
        assertEquals(1.00, result.getPoolMetrics().getUsageRatio());
        assertEquals(28, result.getPoolMetrics().getWaitingThreads());

        assertFalse(result.getActiveSlowQueries().isEmpty());
        SlowQueryItem slowQuery = result.getActiveSlowQueries().get(0);
        assertEquals("trx_10423", slowQuery.getSessionProcessId());
        assertTrue(slowQuery.getIsFullTableScan());
        assertTrue(slowQuery.getSanitizedSql().contains("t_order"));

        assertFalse(result.getOptimizationAdvices().isEmpty());
        assertTrue(result.getOptimizationAdvices().stream().anyMatch(a -> a.contains("联合复合索引") || a.contains("联合索引")));
    }

    @Test
    @DisplayName("测试 MockActionAdapter 仿真应急处置与工单生成")
    void testMockActionAdapterExecution() {
        TroubleshootActionParam param = TroubleshootActionParam.builder()
                .service("order-service")
                .actionType("KILL_SLOW_SESSION")
                .targetIdentifier("trx_10423")
                .reason("单元测试验证应急止血")
                .operator("SRE-Tester")
                .build();

        TroubleshootActionResult result = actionAdapter.executeAction(param);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertNotNull(result.getTicketId());
        assertTrue(result.getTicketId().startsWith("OPS-"));
        assertTrue(result.getAuditMessage().contains("order-service"));
        assertTrue(result.getAuditMessage().contains("trx_10423"));
        assertTrue(result.getAuditMessage().contains("SRE-Tester"));
        assertTrue(result.getExecutedTimestamp() > 0);
    }
}
