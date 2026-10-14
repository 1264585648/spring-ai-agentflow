package com.example.springai.core.card;

import com.example.springai.core.card.action.ApprovedCardSubmission;
import com.example.springai.core.card.dto.CardSubmitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 仅模拟、不发起外部写操作。受 ActionGate 的模拟提交开关控制。 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class DefaultMockCardSubmitHandler implements CardSubmitHandler {
    @Override
    public boolean supports(String cardType) {
        return true;
    }

    @Override
    public boolean isSimulation() {
        return true;
    }

    @Override
    public CardSubmitResult handleSubmit(ApprovedCardSubmission submission) {
        String ticketId = "TICKET-" + UUID.randomUUID();
        log.info("[CardSPI:Mock] actionId={}, cardType={}, actor={}", submission.getActionId(),
                submission.getCardType(), submission.getActorId());
        return CardSubmitResult.ok(ticketId, "模拟提交完成；未执行外部系统操作");
    }
}
