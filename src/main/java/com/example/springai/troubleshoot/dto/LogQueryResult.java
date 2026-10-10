package com.example.springai.troubleshoot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 标准日志检索结论结果模型 (LogQueryResult)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogQueryResult {

    /**
     * 目标服务标识
     */
    private String service;

    /**
     * 命中总日志条数
     */
    private int totalMatches;

    /**
     * 关键日志条目样本列表
     */
    @Builder.Default
    private List<LogEntry> matchedEntries = Collections.emptyList();

    /**
     * 聚类提取的高频异常类名列表 (如 "HikariPool Connection Timeout")
     */
    @Builder.Default
    private List<String> topExceptions = Collections.emptyList();

    /**
     * 最深层报错核心代码行 (辅助快速定位源头类与行号)
     */
    private String deepestStackTrace;
}
