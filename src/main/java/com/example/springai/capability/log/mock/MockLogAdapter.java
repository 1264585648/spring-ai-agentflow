package com.example.springai.capability.log.mock;

import com.example.springai.capability.log.model.*;
import com.example.springai.capability.log.port.LogQueryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

/** 演示日志证据，与在线/离线模型状态无关。 */
@Component
@ConditionalOnProperty(name="troubleshoot.mode",havingValue="mock",matchIfMissing=true)
public class MockLogAdapter implements LogQueryPort {
    @Override public boolean isSimulation() { return true; }
    @Override public LogQueryResult queryLogs(LogQueryCriteria criteria) {
        String service=criteria!=null && criteria.getService()!=null && !criteria.getService().isBlank()?criteria.getService().trim():"order-service";
        String now=LocalDateTime.now().toString();
        var first=LogEntry.builder().timestamp(now).level("ERROR").threadName("http-nio-8080-exec-12")
                .logger("com.zaxxer.hikari.pool.HikariPool")
                .message("HikariPool-1 - Connection is not available, request timed out after 30000ms (active=50, idle=0, waiting=28)")
                .stackTraceSample("java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:182)").build();
        var second=LogEntry.builder().timestamp(now).level("WARN").threadName("http-nio-8080-exec-15")
                .logger("org.springframework.web.servlet.DispatcherServlet")
                .message("Failed to complete request for /api/v1/orders/submit: 504 Gateway Timeout (upstream database query blocked)")
                .stackTraceSample("org.springframework.dao.QueryTimeoutException: HikariPool wait timeout exceeded for transaction trx_10423").build();
        return LogQueryResult.builder().service(service).totalMatches(142).matchedEntries(List.of(first,second))
                .topExceptions(List.of("HikariPool-1 - Connection is not available, request timed out after 30000ms (118 occurrences)",
                        "org.springframework.dao.QueryTimeoutException: HikariPool wait timeout (24 occurrences)"))
                .deepestStackTrace("com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:182)").build();
    }
}
