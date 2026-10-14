package com.example.springai.capability.mock;

import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.model.DbDiagnoseCriteria;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.model.LogQueryCriteria;
import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.model.OpsActionParam;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MockAdaptersTest {
    @Test void readOnlyMockQueriesReturnExplicitServiceEvidence() {
        var log=new MockLogAdapter().queryLogs(LogQueryCriteria.builder().service("order-service").keyword("504").build());
        assertEquals("order-service",log.getService()); assertTrue(log.getTotalMatches()>0); assertFalse(log.getTopExceptions().isEmpty());
        var db=new MockDbAdapter().diagnoseDatabase(DbDiagnoseCriteria.builder().service("order-service").build());
        assertEquals("order-service",db.getService()); assertNotNull(db.getPoolMetrics()); assertFalse(db.getActiveSlowQueries().isEmpty());
    }
    @Test void writesRequireExplicitTargetEvenInDemo() {
        var adapter=new MockOpsActionAdapter();
        assertThrows(IllegalArgumentException.class,()->adapter.executeAction(null));
        assertThrows(IllegalArgumentException.class,()->adapter.executeAction(new OpsActionParam()));
        assertEquals(0,adapter.size());
    }
    @Test void sameKeyReusesReceiptButDifferentParametersAreRejected() {
        var adapter=new MockOpsActionAdapter();
        var param=OpsActionParam.builder().service("order-service").actionType("KILL_SLOW_SESSION").targetIdentifier("trx-1")
                .operator("test-actor").reason("approved").idempotencyKey("a").build();
        var result=adapter.executeAction(param); assertTrue(result.isSuccess()); assertTrue(result.getTicketId().startsWith("OPS-"));
        assertEquals(result.getTicketId(),adapter.executeAction(param).getTicketId()); assertEquals(1,adapter.size());
        result.setTicketId("mutated"); assertNotEquals("mutated",adapter.findExecutedResult("a").orElseThrow().getTicketId());
        param.setTargetIdentifier("different"); assertThrows(IllegalArgumentException.class,()->adapter.executeAction(param));
        assertEquals(1,adapter.size());
    }
}
