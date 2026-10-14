package com.example.springai.capability.ops.card;

import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.model.OpsActionResult;
import com.example.springai.capability.ops.port.OpsActionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 智能运维排障与应急处置卡片 SPI 处理器 (OpsActionCardSubmitHandler)
 * 职责:
 * 1. 响应 TROUBLESHOOT_ACTION 等卡片提交事件 (Human-in-the-loop)；
 * 2. 校验目标服务名与应急处置参数，拒绝缺失目标的盲目执行；
 * 3. 通过 OpsActionPort 标准端口下发止血指令（带幂等键与已有结果反查），生成应急工单号；
 * 4. 优先级高于通用兜底处理器 (@Order(10))。
 */
@Component
@Order(10)
@Slf4j
public class OpsActionCardSubmitHandler implements CardSubmitHandler {

    public static final String CARD_TYPE_OPS = "OPS_ACTION";
    public static final String CARD_TYPE_TROUBLESHOOT = "TROUBLESHOOT_ACTION";
    public static final String TRUSTED_DEFAULT_OPERATOR = "SRE-Oncall";

    private final OpsActionPort troubleshootActionPort;

    @Autowired
    public OpsActionCardSubmitHandler(OpsActionPort troubleshootActionPort) {
        this.troubleshootActionPort = Objects.requireNonNull(troubleshootActionPort, "troubleshootActionPort must not be null");
    }

    @Override
    public boolean supports(String cardType) {
        return CARD_TYPE_OPS.equalsIgnoreCase(cardType) || CARD_TYPE_TROUBLESHOOT.equalsIgnoreCase(cardType);
    }

    @Override
    public CardSubmitResult handleSubmit(CardSubmitRequest request) {
        if (request == null) {
            return CardSubmitResult.fail("提交请求不能为空");
        }
        Map<String, Object> formValues = request.getFormValues();
        log.info("[CardSPI:SRE] 接收到排障应急处置提交请求, actionId: {}, 表单数据: {}",
                request.getActionId(), formValues);

        // 1. 优先查询幂等执行记录：若此前外部已成功执行但响应超时，直接返回原执行结果，绝不二次执行
        if (StringUtils.hasText(request.getActionId())) {
            Optional<OpsActionResult> existingOpt = troubleshootActionPort.findExecutedResult(request.getActionId());
            if (existingOpt.isPresent() && existingOpt.get().isSuccess()) {
                OpsActionResult existing = existingOpt.get();
                log.info("[CardSPI:SRE] 命中幂等执行结果, actionId: {}, 单号: {}",
                        request.getActionId(), existing.getTicketId());
                return CardSubmitResult.ok(existing.getTicketId(), existing.getAuditMessage());
            }
        }

        // 2. 提取并校验必需的目标参数，禁止在缺失目标时自动回退到固定生产服务
        String service = extractNonBlank(formValues, "service");
        String actionType = extractNonBlank(formValues, "action_type");
        String targetIdentifier = extractNonBlank(formValues, "target_identifier");

        if (!StringUtils.hasText(service) || !StringUtils.hasText(actionType)) {
            log.warn("[CardSPI:SRE] 拒绝执行：缺少明确的目标服务或处置动作, actionId: {}", request.getActionId());
            return CardSubmitResult.fail("缺少必要的目标服务 (service) 或处置动作 (action_type) 参数，拒绝执行");
        }

        // 3. 操作人身份由服务端可信上下文决定，不接受客户端表单覆盖
        String operator = TRUSTED_DEFAULT_OPERATOR;

        OpsActionParam param = OpsActionParam.builder()
                .service(service)
                .actionType(actionType)
                .targetIdentifier(targetIdentifier != null ? targetIdentifier : "")
                .reason("前端交互卡片人工核验确认 (actionId: " + request.getActionId() + ")")
                .operator(operator)
                .idempotencyKey(request.getActionId())
                .build();

        OpsActionResult result = troubleshootActionPort.executeAction(param);
        if (result == null) {
            log.error("[CardSPI:SRE] 运维端口返回空结果, actionId: {}", request.getActionId());
            return CardSubmitResult.fail("应急处置执行失败：运维端口未返回有效结果");
        }
        if (!result.isSuccess()) {
            String failMsg = StringUtils.hasText(result.getAuditMessage())
                    ? result.getAuditMessage()
                    : "应急处置执行失败";
            log.warn("[CardSPI:SRE] 处置执行失败: actionId: {}, 服务: {}, 原因: {}",
                    request.getActionId(), service, failMsg);
            return CardSubmitResult.fail(failMsg);
        }

        log.info("[CardSPI:SRE] 处置成功: 单号: {}, 服务: {}, 动作: {}",
                result.getTicketId(), service, actionType);

        return CardSubmitResult.ok(result.getTicketId(), result.getAuditMessage());
    }

    private String extractNonBlank(Map<String, Object> formValues, String key) {
        if (formValues == null || formValues.get(key) == null) {
            return null;
        }
        String val = String.valueOf(formValues.get(key)).trim();
        return val.isEmpty() ? null : val;
    }
}
