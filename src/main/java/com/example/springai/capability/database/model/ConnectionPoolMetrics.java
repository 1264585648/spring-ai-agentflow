package com.example.springai.capability.database.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据库连接池实时运行指标模型 (ConnectionPoolMetrics)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionPoolMetrics {

    /**
     * 活跃运行连接数
     */
    private int activeConnections;

    /**
     * 空闲等待连接数
     */
    private int idleConnections;

    /**
     * 最大连接池容量配置
     */
    private int maxPoolSize;

    /**
     * 当前排队等待连接的线程数
     */
    private int waitingThreads;

    /**
     * 水位百分比 (如 1.00 表示 100% 打满)
     */
    private double usageRatio;
}
