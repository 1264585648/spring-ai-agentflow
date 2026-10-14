package com.example.springai.capability.database.port;

import com.example.springai.capability.database.model.DbDiagnoseCriteria;
import com.example.springai.capability.database.model.DbDiagnoseResult;

public interface DatabaseDiagnosePort {
    DbDiagnoseResult diagnoseDatabase(DbDiagnoseCriteria criteria);
    /** 证据提供方声明，独立于是否连接在线 LLM。 */
    default boolean isSimulation() { return false; }
}
