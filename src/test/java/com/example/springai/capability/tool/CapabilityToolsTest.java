package com.example.springai.capability.tool;

import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.tool.DatabaseDiagnoseTool;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.tool.LogQueryTool;
import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.tool.OpsActionTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityToolsTest {
    private final LogQueryTool logTool = new LogQueryTool(new MockLogAdapter());
    private final DatabaseDiagnoseTool dbTool = new DatabaseDiagnoseTool(new MockDbAdapter());
    private final OpsActionTool opsTool = new OpsActionTool(new MockOpsActionAdapter());

    @Test
    @DisplayName("测试 LogQueryTool 原子工具日志检索功能")
    void testQueryServiceLogs() {
        String logReport = logTool.queryServiceLogs("order-service", "504", "ERROR");
        assertNotNull(logReport);
        assertTrue(logReport.contains("order-service"));
        assertTrue(logReport.contains("HikariPool"));
        assertTrue(logReport.contains("142"));
    }

    @Test
    @DisplayName("数据库报告必须包含服务、实际水位、目标会话与模拟来源，而非固定产品名")
    void testDiagnoseDatabase() {
        String dbReport = dbTool.diagnoseDatabase("order-service", 1000L);
        assertNotNull(dbReport);
        assertAll(
                () -> assertTrue(dbReport.contains("order-service")),
                () -> assertTrue(dbReport.contains("trx_10423")),
                () -> assertTrue(dbReport.contains("活跃 50 / 最大 50")),
                () -> assertTrue(dbReport.contains("等待线程数: 28")),
                () -> assertTrue(dbReport.contains("45200ms")),
                () -> assertTrue(dbReport.contains("演示")),
                () -> assertTrue(dbReport.contains("实际操作前必须重新核验"))
        );
    }

    @Test
    @DisplayName("测试 OpsActionTool 原子工具应急处置动作执行与工单输出")
    void testExecuteEmergencyAction() {
        String actionReport = opsTool.executeEmergencyAction("order-service", "KILL_SLOW_SESSION", "trx_10423", "SRE-Engineer");
        assertNotNull(actionReport);
        assertTrue(actionReport.contains("OPS-"));
        assertTrue(actionReport.contains("order-service"));
        assertTrue(actionReport.contains("trx_10423"));
    }
}
