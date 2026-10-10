package com.example.springai.web.controller;

import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.infra.sse.SseEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CardInteractionControllerTest {

    @Test
    void duplicateSubmitReturns409AndFailureReleasesTheKey() {
        AtomicInteger attempts = new AtomicInteger();
        CardSubmitHandler handler = new CardSubmitHandler() {
            @Override
            public boolean supports(String cardType) {
                return "OK".equals(cardType) || "BOOM".equals(cardType);
            }

            @Override
            public CardSubmitResult handleSubmit(CardSubmitRequest request) {
                if ("BOOM".equals(request.getCardType()) && attempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("boom");
                }
                return CardSubmitResult.ok("T-1", "ok");
            }
        };
        CardInteractionController controller = new CardInteractionController(
                List.of(handler), mock(SseEventPublisher.class));

        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("act-1");
        request.setSessionId("sess-1");
        request.setCardType("BOOM");

        ResponseEntity<Map<String, Object>> failed = controller.submitCard(request);
        assertEquals(500, failed.getStatusCode().value());

        request.setCardType("OK");
        ResponseEntity<Map<String, Object>> retried = controller.submitCard(request);
        assertEquals(200, retried.getStatusCode().value());
        assertEquals("T-1", retried.getBody().get("ticketId"));

        ResponseEntity<Map<String, Object>> duplicate = controller.submitCard(request);
        assertEquals(409, duplicate.getStatusCode().value());
        assertEquals(409, duplicate.getBody().get("code"));
    }

    @Test
    void testTroubleshootCardSubmission() {
        com.example.springai.capability.ops.card.OpsActionCardSubmitHandler handler = new com.example.springai.capability.ops.card.OpsActionCardSubmitHandler();
        SseEventPublisher publisher = mock(SseEventPublisher.class);
        CardInteractionController controller = new CardInteractionController(List.of(handler), publisher);

        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("act-ops-101");
        request.setSessionId("sess-ops-1");
        request.setCardType("TROUBLESHOOT_ACTION");
        request.setFormValues(Map.of(
                "service", "order-service",
                "action_type", "Kill阻塞慢查询并临时扩容连接池"
        ));

        ResponseEntity<Map<String, Object>> response = controller.submitCard(request);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().get("ticketId"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(response.getBody().get("ticketId")).startsWith("OPS-"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(response.getBody().get("message")).contains("order-service"));
    }
}
