package com.example.springai.troubleshoot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 智能运维排障与应急处置入参模型 (TroubleshootActionParam)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TroubleshootActionParam {

    /**
     * 目标微服务标识 (如 order-service)
     */
    private String service;

    /**
     * 处置动作类型 (如 KILL_SLOW_SESSION, EXPAND_POOL_LIMIT, RESTART_POD)
     */
    private String actionType;

    /**
     * 目标标识: 慢查会话 ID (如 trx_10423) 或新连接池容量 (如 80)
     */
    private String targetIdentifier;

    /**
     * 处置原因与研判依据说明
     */
    private String reason;

    /**
     * 操作工程师标识 (必填审计字段)
     */
    private String operator;
}
