package com.example.springai.capability.log.tool;

import com.example.springai.capability.log.model.LogEntry;
import com.example.springai.capability.log.model.LogQueryCriteria;
import com.example.springai.capability.log.model.LogQueryResult;
import com.example.springai.capability.log.port.LogQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 日志检索与分析原子工具 (LogQueryTool)
 * 职责:
 * 1. 作为一级正交原子能力提供 Spring AI 原生 @Tool 注解；
 * 2. 供大模型自主 Function Calling 推理调用，也可被 L1 规则引擎反射调度；
 * 3. 依赖 LogQueryPort SPI，实现能力与底层基础设施/Mock适配器的完全解耦。
 */
@Component("logQueryTool")
@Slf4j
@RequiredArgsConstructor
public class LogQueryTool {

    private final LogQueryPort logQueryPort;

    @Tool(description = "根据微服务标识与过滤条件检索异常日志，提取报错堆栈与高频错误根因")
    public String queryServiceLogs(String service, String keyword, String logLevel) {
        log.info("[LogQueryTool] 收到日志检索请求: service={}, keyword={}, logLevel={}", service, keyword, logLevel);
        LogQueryCriteria criteria = LogQueryCriteria.builder()
                .service(service)
                .keyword(keyword)
                .logLevel(logLevel != null && !logLevel.isBlank() ? logLevel : "ERROR")
                .limit(50)
                .build();

        LogQueryResult result = logQueryPort.queryLogs(criteria);
        if (result == null) {
            return "未检索到相关日志记录。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📋 【服务日志分析报告】服务: %s | 命中异常数: %d 条\n", result.getService(), result.getTotalMatches()));

        if (result.getTopExceptions() != null && !result.getTopExceptions().isEmpty()) {
            sb.append("🔥 高频聚合异常:\n");
            for (String ex : result.getTopExceptions()) {
                sb.append("  • ").append(ex).append("\n");
            }
        }

        if (result.getDeepestStackTrace() != null) {
            sb.append("🎯 最深报错核心行: ").append(result.getDeepestStackTrace()).append("\n");
        }

        if (result.getMatchedEntries() != null && !result.getMatchedEntries().isEmpty()) {
            sb.append("\n🔍 典型堆栈采样:\n");
            for (LogEntry entry : result.getMatchedEntries()) {
                sb.append(String.format("  [%s] [%s] %s - %s\n",
                        entry.getTimestamp(), entry.getLevel(), entry.getLogger(), entry.getMessage()));
                if (entry.getStackTraceSample() != null && !entry.getStackTraceSample().isBlank()) {
                    sb.append("    堆栈摘要: ").append(entry.getStackTraceSample().trim()).append("\n");
                }
            }
        }

        return sb.toString();
    }
}
