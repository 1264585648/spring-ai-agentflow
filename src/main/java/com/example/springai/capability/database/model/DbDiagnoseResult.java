package com.example.springai.capability.database.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 数据库性能诊断综合结论模型 (DbDiagnoseResult)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbDiagnoseResult {

    /**
     * 目标服务标识
     */
    private String service;

    /**
     * 连接池实时运行水位
     */
    private ConnectionPoolMetrics poolMetrics;

    /**
     * 当前正在运行的长耗时慢 SQL 与阻塞事务列表
     */
    @Builder.Default
    private List<SlowQueryItem> activeSlowQueries = Collections.emptyList();

    /**
     * 是否存在死锁或严重锁阻塞阻断
     */
    @Builder.Default
    private Boolean hasDeadlock = false;

    /**
     * 数据库索引与性能调优建议列表
     */
    @Builder.Default
    private List<String> optimizationAdvices = Collections.emptyList();
}
