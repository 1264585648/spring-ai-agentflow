package com.example.springai.capability.log.mock;

import com.example.springai.capability.log.model.LogEntry;
import com.example.springai.capability.log.model.LogQueryCriteria;
import com.example.springai.capability.log.model.LogQueryResult;
import com.example.springai.capability.log.port.LogQueryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 开源仿真日志检索适配器 (MockLogAdapter)
 * 职责:
 * 1. 开源开箱即用，无需连入任何企业私有 SLS/ELK/Loki，通过 mock 数据集提供 100% 真实场景仿真；
 * 2. 模拟典型高并发下 HikariCP 连接池打满、504 Gateway Timeout 报错堆栈；
 * 3. 采用 @ConditionalOnProperty，若配置 troubleshoot.mode=company 则自动让位给私有 ACL 适配器。
 */
@Component
@ConditionalOnProperty(name = "troubleshoot.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockLogAdapter implements LogQueryPort {

    @Override
    public LogQueryResult queryLogs(LogQueryCriteria criteria) {
        String service = (criteria != null && criteria.getService() != null && !criteria.getService().isBlank())
                ? criteria.getService().trim()
                : "order-service";

        log.info("[MockLogAdapter] 仿真检索日志与异常堆栈: service={}, level={}, keyword={}, limit={}",
                service,
                criteria != null ? criteria.getLogLevel() : "ERROR",
                criteria != null ? criteria.getKeyword() : null,
                criteria != null ? criteria.getLimit() : 50);

        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());

        List<LogEntry> entries = new ArrayList<>();

        // 典型故障日志样本 1: HikariCP 连接池获取连接超时
        entries.add(LogEntry.builder()
                .timestamp(now)
                .level("ERROR")
                .threadName("http-nio-8080-exec-12")
                .logger("com.zaxxer.hikari.pool.HikariPool")
                .message("HikariPool-1 - Connection is not available, request timed out after 30000ms (active=50, idle=0, waiting=28)")
                .stackTraceSample("""
                        java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.
                        \tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                        \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:182)
                        \tat com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:100)
                        \tat org.springframework.jdbc.datasource.DataSourceUtils.doGetConnection(DataSourceUtils.java:117)
                        \tat org.springframework.jdbc.core.JdbcTemplate.execute(JdbcTemplate.java:383)
                        """)
                .build());

        // 典型故障日志样本 2: 504 网关超时与下游调用中断
        entries.add(LogEntry.builder()
                .timestamp(now)
                .level("WARN")
                .threadName("http-nio-8080-exec-15")
                .logger("org.springframework.web.servlet.DispatcherServlet")
                .message("Failed to complete request for /api/v1/orders/submit: 504 Gateway Timeout (upstream database query blocked)")
                .stackTraceSample("""
                        org.springframework.dao.QueryTimeoutException: HikariPool wait timeout exceeded for transaction trx_10423; nested exception is java.sql.SQLTimeoutException
                        \tat org.springframework.jdbc.support.SQLStateSQLExceptionTranslator.doTranslate(SQLStateSQLExceptionTranslator.java:108)
                        \tat com.example.order.repository.OrderRepository.findPendingOrders(OrderRepository.java:76)
                        """)
                .build());

        return LogQueryResult.builder()
                .service(service)
                .totalMatches(142)
                .matchedEntries(entries)
                .topExceptions(List.of(
                        "HikariPool-1 - Connection is not available, request timed out after 30000ms (118 occurrences)",
                        "org.springframework.dao.QueryTimeoutException: HikariPool wait timeout (24 occurrences)"
                ))
                .deepestStackTrace("com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:182)")
                .build();
    }
}
