package com.example.springai.capability.ops.tool;

import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.model.OpsActionResult;
import com.example.springai.capability.ops.port.OpsActionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 运维操作与应急处置原子工具 (OpsActionTool)
 * 职责:
 * 1. 作为一级正交原子能力提供 Spring AI 原生 @Tool 注解；
 * 2. 统一调度底层应急处置动作（终止慢查询、扩容连接池、服务重启、流量剔除等）；
 * 3. 产出规范化审计工单 (TicketId)，保障变更安全性。
 */
@Component("opsActionTool")
@Slf4j
@RequiredArgsConstructor
public class OpsActionTool {

    private final OpsActionPort opsActionPort;

    @Tool(description = "执行运维应急止血处置动作（如终止慢查会话、临时扩容连接池），产出规范运维工单")
    public String executeEmergencyAction(String service, String actionType, String targetIdentifier, String operator) {
        log.info("[OpsActionTool] 收到应急处置指令: service={}, actionType={}, target={}, operator={}",
                service, actionType, targetIdentifier, operator);

        OpsActionParam param = OpsActionParam.builder()
                .service(service)
                .actionType(actionType)
                .targetIdentifier(targetIdentifier)
                .reason("通过 OpsActionTool 执行应急处置")
                .operator(operator != null && !operator.isBlank() ? operator : "SRE-Engineer")
                .build();

        OpsActionResult result = opsActionPort.executeAction(param);
        if (result == null) {
            return "处置指令下发失败，未收到执行结果。";
        }

        return String.format("✅ 应急处置执行完成！单号: %s | 结果: %s",
                result.getTicketId(), result.getAuditMessage());
    }
}
