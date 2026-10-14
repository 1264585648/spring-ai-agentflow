package com.example.springai.capability.ops.tool;

import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.model.OpsActionResult;
import com.example.springai.capability.ops.port.OpsActionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 运维操作与应急处置后端执行辅助类 (OpsActionTool)。
 * 保留现有 Java 调用接口，但不再作为模型工具或 L1 直通工具暴露。
 * 写执行方法不得添加 Spring AI 的工具注解；未来应由统一审批入口调用执行端口。
 * 当前方法本身不提供鉴权或审批保障，不能将普通 Java 可调用性视为已获授权。
 */
@Component("opsActionTool")
@Slf4j
@RequiredArgsConstructor
public class OpsActionTool {

    private final OpsActionPort opsActionPort;

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
