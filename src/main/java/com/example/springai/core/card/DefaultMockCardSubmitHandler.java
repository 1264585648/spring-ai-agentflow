package com.example.springai.core.card;

import lombok.extern.slf4j.Slf4j;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 默认兜底卡片提交处理器 (DefaultMockCardSubmitHandler)
 * 作用: 开源通用版自带实现，负责打印日志并生成模拟工单号，保证框架开箱即用。
 * 特性: 使用 @Order(Ordered.LOWEST_PRECEDENCE)，当外部业务提供了特定类型的处理器时，业务处理器优先被匹配。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class DefaultMockCardSubmitHandler implements CardSubmitHandler {


    @Override
    public boolean supports(String cardType) {
        // 兜底处理器支持所有未被特定业务拦截的卡片类型
        return true;
    }

    @Override
    public CardSubmitResult handleSubmit(CardSubmitRequest request) {
        log.info("[CardSPI] 触发通用默认卡片处理器, actionId: {}, cardType: {}, 提交参数: {}",
                request.getActionId(), request.getCardType(), request.getFormValues());

        // 模拟生成通用业务单号
        String dateStr = new SimpleDateFormat("yyyyMMdd").format(new Date());
        long randomSuffix = (long) (Math.random() * 9000 + 1000);
        String mockTicketId = "TICKET-" + dateStr + "-" + randomSuffix;

        log.info("[CardSPI] 模拟工单创建成功, 单号: {}", mockTicketId);
        return CardSubmitResult.ok(mockTicketId, "工单已成功提报并进入审批流程");
    }
}
