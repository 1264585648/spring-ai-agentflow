package com.example.springai.card.spi;

import lombok.extern.slf4j.Slf4j;
import com.example.springai.card.dto.CardSubmitRequest;
import com.example.springai.card.dto.CardSubmitResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * 智能运维排障与应急处置卡片 SPI 处理器 (TroubleshootCardSubmitHandler)
 * 职责:
 * 1. 响应 TROUBLESHOOT_ACTION 等卡片提交事件 (Human-in-the-loop)；
 * 2. 校验目标服务名与应急处置参数；
 * 3. 模拟调用运维网关下发止血指令（如 Kill 慢会话、扩容连接池），生成应急工单号；
 * 4. 优先级高于通用兜底处理器 (@Order(10))。
 */
@Component
@Order(10)
@Slf4j
public class TroubleshootCardSubmitHandler implements CardSubmitHandler {

    public static final String CARD_TYPE_TROUBLESHOOT = "TROUBLESHOOT_ACTION";

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

        if (formValues != null) {
            if (formValues.get("service") != null && !String.valueOf(formValues.get("service")).isBlank()) {
                service = String.valueOf(formValues.get("service")).trim();
            }
            if (formValues.get("action_type") != null) {
                actionType = String.valueOf(formValues.get("action_type")).trim();
            }
        }

        // 模拟生成规范运维止血工单号 (例如 OPS-20261009-8421)
        String dateStr = new SimpleDateFormat("yyyyMMdd").format(new Date());
        long randomNum = 1000 + (long) (Math.random() * 9000);
        String ticketId = "OPS-" + dateStr + "-" + randomNum;

        String successMessage = String.format("应急止血指令已成功下发至网关！工单号: %s，目标服务: %s，执行动作: %s。",
                ticketId, service, actionType);
        log.info("[CardSPI:SRE] 处置成功: 单号: {}, 服务: {}, 动作: {}", ticketId, service, actionType);

        return CardSubmitResult.ok(ticketId, successMessage);
    }
}
