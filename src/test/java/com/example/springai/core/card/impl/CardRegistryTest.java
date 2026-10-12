package com.example.springai.core.card.impl;

import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.CardNotFoundException;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.state.CardState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CardRegistryTest {

    private InMemoryCardRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryCardRegistry();
    }

    private InteractiveCard buildSampleCard(String actionId) {
        InteractiveCard card = new InteractiveCard();
        card.setActionId(actionId);
        card.setCardType("OPS_KILL_ACTION");
        card.setTitle("🚨 应急止血预案确认");

        CardFormField serviceField = new CardFormField("service", "服务名", "text", "order-service", false, true);
        CardFormField reasonField = new CardFormField("reason", "处置理由", "text", "慢查打满连接池", true, false);

        card.setFields(List.of(serviceField, reasonField));
        return card;
    }

    @Test
    @DisplayName("测试卡片签发与正常申领跃迁 (ISSUED -> SUBMITTING)")
    void testIssueAndClaimSuccess() {
        InteractiveCard card = buildSampleCard("act-101");
        registry.issue("sess-1", card);

        CardRecord record = registry.claim("act-101", "sess-1");
        assertNotNull(record);
        assertEquals(CardState.SUBMITTING, record.getState());
        assertEquals("order-service", record.getSnapshot().getFields().get(0).getValue());
    }

    @Test
    @DisplayName("测试未登记 actionId 申领抛出 CardNotFoundException")
    void testUnissuedActionIdThrowsNotFound() {
        assertThrows(CardNotFoundException.class, () -> registry.claim("fake-action-id", "sess-1"));
    }

    @Test
    @DisplayName("测试跨会话越权操作拦截 (抛出 CardSecurityException)")
    void testCrossSessionClaimThrowsSecurityException() {
        InteractiveCard card = buildSampleCard("act-102");
        registry.issue("sess-user-A", card);

        // 用户 B 试图使用该 actionId
        assertThrows(CardSecurityException.class, () -> registry.claim("act-102", "sess-user-B"));
    }

    @Test
    @DisplayName("测试重复提交冲突 (抛出 CardStateConflictException)")
    void testDuplicateClaimReturnsConflict() {
        InteractiveCard card = buildSampleCard("act-103");
        registry.issue("sess-1", card);

        // 第一次申领成功
        registry.claim("act-103", "sess-1");

        // 并发或重复连击申领被拦截
        assertThrows(CardStateConflictException.class, () -> registry.claim("act-103", "sess-1"));
    }

    @Test
    @DisplayName("测试只读字段被恶意篡改时抛出 CardSecurityException")
    void testTamperingReadOnlyFieldThrowsSecurityException() {
        InteractiveCard card = buildSampleCard("act-104");
        registry.issue("sess-1", card);

        CardRecord record = registry.claim("act-104", "sess-1");

        // 恶意用户抓包将只读的 order-service 篡改为 payment-db
        Map<String, Object> tamperedValues = Map.of(
                "service", "payment-db",
                "reason", "常规排查"
        );

        CardSecurityException ex = assertThrows(CardSecurityException.class, () ->
                registry.validate(record, tamperedValues)
        );
        assertTrue(ex.getMessage().contains("只读参数"));
        assertTrue(ex.getMessage().contains("order-service"));
    }

    @Test
    @DisplayName("测试可编辑字段微调允许通过")
    void testEditableFieldModificationAllowed() {
        InteractiveCard card = buildSampleCard("act-105");
        registry.issue("sess-1", card);

        CardRecord record = registry.claim("act-105", "sess-1");

        // 只读字段保持一致，可编辑字段被工程师修改
        Map<String, Object> validValues = Map.of(
                "service", "order-service",
                "reason", "【专员修改】：确认慢查无索引，核准紧急止血"
        );

        assertDoesNotThrow(() -> registry.validate(record, validValues));
    }

    @Test
    @DisplayName("测试成功归档与终态流转 (SUBMITTING -> SUBMITTED)")
    void testCompleteCard() {
        InteractiveCard card = buildSampleCard("act-106");
        registry.issue("sess-1", card);
        registry.claim("act-106", "sess-1");

        registry.complete("act-106", CardSubmitResult.ok("OPS-20261012-001", "执行成功"));

        CardRecord record = registry.getRecord("act-106").orElseThrow();
        assertEquals(CardState.SUBMITTED, record.getState());
        assertEquals("OPS-20261012-001", record.getTicketId());

        // 终态下再次申领必须抛出冲突异常
        assertThrows(CardStateConflictException.class, () -> registry.claim("act-106", "sess-1"));
    }

    @Test
    @DisplayName("测试处理失败时释放锁允许重试")
    void testFailAllowsRetry() {
        InteractiveCard card = buildSampleCard("act-107");
        registry.issue("sess-1", card);
        registry.claim("act-107", "sess-1");

        // 模拟执行失败并允许重试
        registry.fail("act-107", "连接超时", true);

        CardRecord record = registry.getRecord("act-107").orElseThrow();
        assertEquals(CardState.ISSUED, record.getState());

        // 允许重新申领
        assertDoesNotThrow(() -> registry.claim("act-107", "sess-1"));
    }
}
