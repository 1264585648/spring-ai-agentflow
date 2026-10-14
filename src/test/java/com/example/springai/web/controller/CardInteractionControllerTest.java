package com.example.springai.web.controller;

import com.example.springai.capability.ops.card.OpsActionCardSubmitHandler;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.infra.sse.SseEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CardInteractionControllerTest {

    private InMemoryCardRegistry cardRegistry;
    private SseEventPublisher ssePublisher;

    @BeforeEach
    void setUp() {
        cardRegistry = new InMemoryCardRegistry();
        ssePublisher = mock(SseEventPublisher.class);
    }

    private InteractiveCard buildSampleCard(String actionId) {
        InteractiveCard card = new InteractiveCard();
        card.setActionId(actionId);
        card.setCardType("TROUBLESHOOT_ACTION");
        card.setTitle("🚨 应急止血预案确认");

        CardFormField serviceField = new CardFormField("service", "服务名", "text", "order-service", false, true);
        CardFormField actionField = new CardFormField("action_type", "处置动作", "text", "Kill阻塞慢查询并临时扩容连接池", true, true);

        card.setFields(List.of(serviceField, actionField));
        return card;
    }

    @Test
    @DisplayName("测试重复提交返回 409 且失败释放后允许重试")
    void duplicateSubmitReturns409AndFailureReleasesTheKey() {
        // 先在注册中心签发该卡片
        InteractiveCard card = buildSampleCard("act-1");
        cardRegistry.issue("sess-1", card);

        AtomicInteger attempts = new AtomicInteger();
        CardSubmitHandler handler = new CardSubmitHandler() {
            @Override
            public boolean supports(String cardType) {
                return "TROUBLESHOOT_ACTION".equals(cardType);
            }

            @Override
            public CardSubmitResult handleSubmit(CardSubmitRequest request) {
                if (attempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("boom");
                }
                return CardSubmitResult.ok("T-1", "ok");
            }
        };

        CardInteractionController controller = new CardInteractionController(
                List.of(handler), ssePublisher, cardRegistry);

        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("act-1");
        request.setSessionId("sess-1");
        request.setCardType("TROUBLESHOOT_ACTION");
        request.setFormValues(Map.of(
                "service", "order-service",
                "action_type", "Kill阻塞慢查询并临时扩容连接池"
        ));

        // 第一次提交：Handler 内部抛出异常 -> 500，状态释放回 ISSUED 允许重试
        ResponseEntity<Map<String, Object>> failed = controller.submitCard(request);
        assertEquals(500, failed.getStatusCode().value());

        // 第二次提交（重试）：成功 -> 200，状态流转至 SUBMITTED 终态
        ResponseEntity<Map<String, Object>> retried = controller.submitCard(request);
        assertEquals(200, retried.getStatusCode().value());
        assertEquals("T-1", retried.getBody().get("ticketId"));

        // 第三次提交：终态下重复提交 -> 409 Conflict
        ResponseEntity<Map<String, Object>> duplicate = controller.submitCard(request);
        assertEquals(409, duplicate.getStatusCode().value());
        assertEquals(409, duplicate.getBody().get("code"));
    }

    @Test
    @DisplayName("测试排障应急卡片正常提交与工单生成")
    void testTroubleshootCardSubmission() {
        InteractiveCard card = buildSampleCard("act-ops-101");
        cardRegistry.issue("sess-ops-1", card);

        OpsActionCardSubmitHandler handler = new OpsActionCardSubmitHandler(
                new com.example.springai.capability.ops.mock.MockOpsActionAdapter());
        CardInteractionController controller = new CardInteractionController(
                List.of(handler), ssePublisher, cardRegistry);

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
        assertTrue(String.valueOf(response.getBody().get("ticketId")).startsWith("OPS-"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("order-service"));
    }

    @Test
    @DisplayName("测试只读参数被恶意篡改拦截返回 400 安全警告")
    void testTamperingReadOnlyFieldReturns400() {
        InteractiveCard card = buildSampleCard("act-tamper-1");
        cardRegistry.issue("sess-1", card);

        OpsActionCardSubmitHandler handler = new OpsActionCardSubmitHandler(
                new com.example.springai.capability.ops.mock.MockOpsActionAdapter());
        CardInteractionController controller = new CardInteractionController(
                List.of(handler), ssePublisher, cardRegistry);

        // 恶意用户抓包将只读的 order-service 改为 payment-db
        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("act-tamper-1");
        request.setSessionId("sess-1");
        request.setCardType("TROUBLESHOOT_ACTION");
        request.setFormValues(Map.of(
                "service", "payment-db",
                "action_type", "Kill阻塞慢查询并临时扩容连接池"
        ));

        ResponseEntity<Map<String, Object>> response = controller.submitCard(request);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("安全警告"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("只读参数"));
    }

    @Test
    @DisplayName("测试伪造未下发的 actionId 提交被拒绝返回 400")
    void testUnissuedActionIdReturns400() {
        OpsActionCardSubmitHandler handler = new OpsActionCardSubmitHandler(
                new com.example.springai.capability.ops.mock.MockOpsActionAdapter());
        CardInteractionController controller = new CardInteractionController(
                List.of(handler), ssePublisher, cardRegistry);

        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("fake-action-id-999");
        request.setSessionId("sess-1");
        request.setCardType("TROUBLESHOOT_ACTION");

        ResponseEntity<Map<String, Object>> response = controller.submitCard(request);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("不存在"));
    }

    @Test
    @DisplayName("测试跨会话越权使用他人 actionId 被拦截返回 403 Forbidden")
    void testCrossSessionSubmitReturns403() {
        InteractiveCard card = buildSampleCard("act-user-A");
        cardRegistry.issue("session-user-A", card);

        OpsActionCardSubmitHandler handler = new OpsActionCardSubmitHandler(
                new com.example.springai.capability.ops.mock.MockOpsActionAdapter());
        CardInteractionController controller = new CardInteractionController(
                List.of(handler), ssePublisher, cardRegistry);

        // 用户 B 拿着用户 A 的 actionId 发起提交
        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("act-user-A");
        request.setSessionId("session-user-B");
        request.setCardType("TROUBLESHOOT_ACTION");
        request.setFormValues(Map.of("service", "order-service"));

        ResponseEntity<Map<String, Object>> response = controller.submitCard(request);
        assertEquals(403, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("越权操作拦截"));
    }
}
