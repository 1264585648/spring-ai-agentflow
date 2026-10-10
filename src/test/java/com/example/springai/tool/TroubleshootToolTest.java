package com.example.springai.tool;

import com.example.springai.troubleshoot.mock.MockActionAdapter;
import com.example.springai.troubleshoot.mock.MockDbAdapter;
import com.example.springai.troubleshoot.mock.MockLogAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TroubleshootToolTest {

    private final TroubleshootTool tool = new TroubleshootTool(
            new MockLogAdapter(),
            new MockDbAdapter(),
            new MockActionAdapter()
    );

    @Test
    @DisplayName("测试 TroubleshootTool 日志检索功能")
    void testQueryServiceLogs() {
        String logReport = tool.queryServiceLogs("order-service", "504", "ERROR");
        assertNotNull(logReport);
        assertTrue(logReport.contains("order-service"));
        assertTrue(logReport.contains("HikariPool"));
        assertTrue(logReport.contains("142"));
        System.out.println("【单测验证】TroubleshootTool 日志分析报告:\n" + logReport);
    }

    @Test
    @DisplayName("测试 TroubleshootTool 数据库性能诊断功能")
    void testDiagnoseDatabase() {
        String dbReport = tool.diagnoseDatabase("order-service", 1000L);
        assertNotNull(dbReport);
        assertTrue(dbReport.contains("order-service"));
        assertTrue(dbReport.contains("trx_10423"));
        assertTrue(dbReport.contains("HikariCP"));
        System.out.println("【单测验证】TroubleshootTool 数据库诊断报告:\n" + dbReport);
    }

    @Test
    @DisplayName("测试 TroubleshootTool 应急处置动作执行与工单输出")
    void testExecuteEmergencyAction() {
        String actionReport = tool.executeEmergencyAction("order-service", "KILL_SLOW_SESSION", "trx_10423", "SRE-Engineer");
        assertNotNull(actionReport);
        assertTrue(actionReport.contains("OPS-"));
        assertTrue(actionReport.contains("order-service"));
        assertTrue(actionReport.contains("trx_10423"));
        System.out.println("【单测验证】TroubleshootTool 应急处置结果:\n" + actionReport);
    }
}
