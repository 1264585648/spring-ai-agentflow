package com.example.springai.troubleshoot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 日志与分布式链路查询过滤条件 (LogQueryCriteria)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogQueryCriteria {

    /**
     * 目标微服务标识 (如 order-service)
     */
    private String service;

    /**
     * 分布式链路 TraceID (可选)
     */
    private String traceId;

    /**
     * 过滤日志级别: ERROR / WARN / ALL (默认 ERROR)
     */
    @Builder.Default
    private String logLevel = "ERROR";

    /**
     * 检索起始时间戳 (毫秒)
     */
    private Long startTime;

    /**
     * 检索截止时间戳 (毫秒)
     */
    private Long endTime;

    /**
     * 关键字模糊过滤 (如 "Connection timeout", "OutOfMemory")
     */
    private String keyword;

    /**
     * 最大抓取条数 (默认 50，防止大模型上下文溢出)
     */
    @Builder.Default
    private Integer limit = 50;
}
