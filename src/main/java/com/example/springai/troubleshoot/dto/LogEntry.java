package com.example.springai.troubleshoot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标准日志条目模型 (LogEntry)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogEntry {

    /**
     * 日志时间戳 (yyyy-MM-dd HH:mm:ss.SSS)
     */
    private String timestamp;

    /**
     * 日志级别 (ERROR, WARN)
     */
    private String level;

    /**
     * 执行线程名称 (如 "http-nio-8080-exec-1")
     */
    private String threadName;

    /**
     * 日志记录器 Logger 类名
     */
    private String logger;

    /**
     * 简短日志摘要信息
     */
    private String message;

    /**
     * 报错核心堆栈切片 (前 5 行关键根因)
     */
    private String stackTraceSample;
}
