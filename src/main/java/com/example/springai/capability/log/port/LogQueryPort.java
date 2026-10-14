package com.example.springai.capability.log.port;

import com.example.springai.capability.log.model.LogQueryCriteria;
import com.example.springai.capability.log.model.LogQueryResult;

public interface LogQueryPort {
    LogQueryResult queryLogs(LogQueryCriteria criteria);
    /** 证据提供方声明，独立于是否连接在线 LLM。 */
    default boolean isSimulation() { return false; }
}
