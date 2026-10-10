package com.example.springai.card.spi;

import com.example.springai.card.dto.CardSubmitRequest;
import com.example.springai.card.dto.CardSubmitResult;
import com.example.springai.troubleshoot.dto.TroubleshootActionParam;
import com.example.springai.troubleshoot.dto.TroubleshootActionResult;
import com.example.springai.troubleshoot.port.TroubleshootActionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 智能运维排障与应急处置卡片 SPI 处理器 (TroubleshootCardSubmitHandler)
 * 职责:
 * 1. 响应 TROUBLESHOOT_ACTION 等卡片提交事件 (Human-in-the-loop)；
 * 2. 校验目标服务名与应急处置参数；
 * 3. 通过 TroubleshootActionPort 标准端口下发止血指令（如 Kill 慢会话、扩容连接池），生成应急工单号；
 * 4. 优先级高于通用兜底处理器 (@Order(10))。
 */
@Component
@Order(10)
@Slf4j
public class TroubleshootCardSubmitHandler implements CardSubmitHandler {

    public static final String CARD_TYPE_TROUBLESHOOT = "TROUBLESHOOT_ACTION";

    private final TroubleshootActionPort troubleshootActionPort;

    public TroubleshootCardSubmitHandler(TroubleshootActionPort troubleshootActionPort) {
        this.troubleshootActionPort = troubleshootActionPort;
    }

    public TroubleshootCardSubmitHandler() {
        this(new com.example.springai.troubleshoot.mock.MockActionAdapter());
    }

    @Override
    public boolean supports(String cardType) {
        return CARD_TYPE_TROUBLESHOOT.equalsIgnoreCase(cardType);
    }

    @Override
    public CardSubmitResult handleSubmit(CardSubmitRequest request) {
        Map<String, Object> formValues = request.getFormValues();
        log.info("[CardSPI:SRE] 接收到排障应急处置提交请求, actionId: {}, 表单数据: {}",
                request.getActionId(), formValues);

        String service = "order-service";
        String actionType = "Kill阻塞慢查询并临时扩容连接池";
        String targetIdentifier = "trx_10423";

        if (formValues != null) {
            if (formValues.get("service") != null && !String.valueOf(formValues.get("service")).isBlank()) {
                service = String.valueOf(formValues.get("service")).trim();
            }
            if (formValues.get("action_type") != null && !String.valueOf(formValues.get("action_type")).isBlank()) {
                actionType = String.valueOf(formValues.get("action_type")).trim();
            }
            if (formValues.get("target_identifier") != null && !String.valueOf(formValues.get("target_identifier")).isBlank()) {
                targetIdentifier = String.valueOf(formValues.get("target_identifier")).trim();
            }
        }

        String operator = "SRE-Oncall";
        if (formValues != null && formValues.get("operator") != null && !String.valueOf(formValues.get("operator")).isBlank()) {
            operator = String.valueOf(formValues.get("operator")).trim();
        }

        TroubleshootActionParam param = TroubleshootActionParam.builder()
                .service(service)
                .actionType(actionType)
                .targetIdentifier(targetIdentifier)
                .reason("前端交互卡片人工核验确认 (actionId: " + request.getActionId() + ")")
                .operator(operator)
                .build();

        TroubleshootActionResult result = troubleshootActionPort.executeAction(param);
        log.info("[CardSPI:SRE] 处置成功: 单号: {}, 服务: {}, 动作: {}",
                result.getTicketId(), service, actionType);

        return CardSubmitResult.ok(result.getTicketId(), result.getAuditMessage());
    }
}
