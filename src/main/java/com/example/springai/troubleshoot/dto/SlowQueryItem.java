package com.example.springai.troubleshoot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据库慢查询与阻塞事务项模型 (SlowQueryItem)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowQueryItem {

    /**
     * 数据库会话/进程 ID (供后续 Kill 动作精准定位，如 trx_10423)
     */
    private String sessionProcessId;

    /**
     * 已经执行的耗时 (毫秒)
     */
    private Long executionTimeMs;

    /**
     * 脱敏参数后的 SQL 模板
     */
    private String sanitizedSql;

    /**
     * 是否触发全表扫描
     */
    private Boolean isFullTableScan;

    /**
     * 锁状态描述 (如 "Waiting for table metadata lock", "Row lock held")
     */
    private String lockStatus;
}
