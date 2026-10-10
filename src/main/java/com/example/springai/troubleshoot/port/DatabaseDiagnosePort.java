package com.example.springai.troubleshoot.port;

import com.example.springai.troubleshoot.dto.DbDiagnoseCriteria;
import com.example.springai.troubleshoot.dto.DbDiagnoseResult;

/**
 * 数据库性能与连接池诊断标准端口 (DatabaseDiagnosePort)
 * 供 DbDiagnoseAgent 与排障编排流水线分析慢查询、长事务锁等待与连接池水位
 */
public interface DatabaseDiagnosePort {

    /**
     * 诊断指定服务或数据源的数据库运行指标
     *
     * @param criteria 诊断过滤条件
     * @return 数据库运行状态与慢SQL诊断结果
     */
    DbDiagnoseResult diagnoseDatabase(DbDiagnoseCriteria criteria);
}
