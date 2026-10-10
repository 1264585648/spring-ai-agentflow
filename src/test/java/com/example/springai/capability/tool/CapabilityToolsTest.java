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
        System.out.println("【单测验证】LogQueryTool 日志分析报告:\n" + logReport);
    }

    @Test
    @DisplayName("测试 DatabaseDiagnoseTool 原子工具数据库性能诊断功能")
    void testDiagnoseDatabase() {
        String dbReport = dbTool.diagnoseDatabase("order-service", 1000L);
        assertNotNull(dbReport);
        assertTrue(dbReport.contains("order-service"));
        assertTrue(dbReport.contains("trx_10423"));
        assertTrue(dbReport.contains("HikariCP"));
        System.out.println("【单测验证】DatabaseDiagnoseTool 数据库诊断报告:\n" + dbReport);
    }

    @Test
    @DisplayName("测试 OpsActionTool 原子工具应急处置动作执行与工单输出")
    void testExecuteEmergencyAction() {
        String actionReport = opsTool.executeEmergencyAction("order-service", "KILL_SLOW_SESSION", "trx_10423", "SRE-Engineer");
        assertNotNull(actionReport);
        assertTrue(actionReport.contains("OPS-"));
        assertTrue(actionReport.contains("order-service"));
        assertTrue(actionReport.contains("trx_10423"));
        System.out.println("【单测验证】OpsActionTool 应急处置结果:\n" + actionReport);
    }
}
