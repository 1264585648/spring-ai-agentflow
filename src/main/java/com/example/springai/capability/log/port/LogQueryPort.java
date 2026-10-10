package com.example.springai.capability.log.port;

import com.example.springai.capability.log.model.LogQueryCriteria;
import com.example.springai.capability.log.model.LogQueryResult;

/**
 * 日志与分布式链路查询标准端口 (LogQueryPort)
 * 供 LogDiagnoseAgent 与排障编排流水线进行统一只读日志检索与聚合分析
 */
public interface LogQueryPort {

    /**
     * 根据排障条件检索标准化日志记录与异常摘要
     *
     * @param criteria 查询过滤条件
     * @return 结构化的日志查询结论
     */
    LogQueryResult queryLogs(LogQueryCriteria criteria);
}
