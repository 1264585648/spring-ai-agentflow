package com.example.springai.capability.ops.mock;

import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.model.OpsActionResult;
import com.example.springai.capability.ops.port.OpsActionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 开源仿真应急处置适配器 (MockOpsActionAdapter)
 * 职责:
 * 1. 响应 Human-in-the-loop 人工二次确认后的应急止血写操作；
 * 2. 模拟向运维网关或集群下发 Kill 慢查会话、动态扩容连接池指令；
 * 3. 产出规范运维工单号并输出带彩色标识的高可靠审计日志。
 */
@Component
@ConditionalOnProperty(name = "troubleshoot.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockOpsActionAdapter implements OpsActionPort {

    @Override
    public OpsActionResult executeAction(OpsActionParam param) {
        String service = (param != null && param.getService() != null && !param.getService().isBlank())
                ? param.getService().trim()
                : "order-service";
        String actionType = (param != null && param.getActionType() != null && !param.getActionType().isBlank())
                ? param.getActionType().trim()
                : "KILL_SLOW_SESSION";
        String targetIdentifier = (param != null && param.getTargetIdentifier() != null)
                ? param.getTargetIdentifier().trim()
                : "trx_10423";
        String operator = (param != null && param.getOperator() != null && !param.getOperator().isBlank())
                ? param.getOperator().trim()
                : "SRE-Engineer";

        // 模拟生成规范运维工单号 (OPS-yyyyMMdd-xxxx)
        String dateStr = new SimpleDateFormat("yyyyMMdd").format(new Date());
        long randomNum = 1000 + (long) (Math.random() * 9000);
        String ticketId = "OPS-" + dateStr + "-" + randomNum;

        log.info("================================================================================");
        log.info("🚨 [SRE-AUDIT] 应急止血处置指令执行成功 (仿真网关)");
        log.info("工单单号: {}", ticketId);
        log.info("目标服务: {}", service);
        log.info("执行动作: {}", actionType);
        log.info("目标标识: {}", targetIdentifier);
        log.info("操作人员: {}", operator);
        log.info("处置原因: {}", param != null ? param.getReason() : "无");
        log.info("================================================================================");

        String auditMessage = String.format("应急止血指令已成功下发至网关！工单号: %s，目标服务: %s，执行动作: %s，目标标识: %s，操作人: %s。",
                ticketId, service, actionType, targetIdentifier, operator);

        return OpsActionResult.builder()
                .success(true)
                .ticketId(ticketId)
                .auditMessage(auditMessage)
                .executedTimestamp(System.currentTimeMillis())
                .build();
    }
}
