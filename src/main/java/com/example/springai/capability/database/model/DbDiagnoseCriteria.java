package com.example.springai.capability.database.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据库性能与连接池诊断过滤条件 (DbDiagnoseCriteria)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbDiagnoseCriteria {

    /**
     * 目标服务标识
     */
    private String service;

    /**
     * 数据源名称 (默认 default)
     */
    @Builder.Default
    private String datasourceName = "default";

    /**
     * 慢查耗时判定阈值 (毫秒，默认 1000)
     */
    @Builder.Default
    private Long slowThresholdMs = 1000L;

    /**
     * 是否同时排查死锁与阻塞链 (默认 true)
     */
    @Builder.Default
    private Boolean checkDeadlock = true;
}
