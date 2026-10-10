package com.example.springai.troubleshoot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 智能运维排障与应急处置执行结论模型 (TroubleshootActionResult)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TroubleshootActionResult {

    /**
     * 处置动作是否执行成功
     */
    private boolean success;

    /**
     * 运维工单/审批流水单号 (如 OPS-20261010-8421)
     */
    private String ticketId;

    /**
     * 执行结果描述与影响评估说明
     */
    private String auditMessage;

    /**
     * 执行完成时间戳 (毫秒)
     */
    private Long executedTimestamp;
}
