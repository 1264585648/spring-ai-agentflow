package com.example.springai.capability.ops.card;

import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpsActionCardSubmitHandlerTest {

    private final OpsActionCardSubmitHandler handler = new OpsActionCardSubmitHandler(new MockOpsActionAdapter());

    @Test
    @DisplayName("测试 supports 支持 OPS_ACTION 与 TROUBLESHOOT_ACTION 卡片类型")
    void testSupportsTroubleshootAction() {
        assertTrue(handler.supports("OPS_ACTION"));
        assertTrue(handler.supports("ops_action"));
        assertTrue(handler.supports("TROUBLESHOOT_ACTION"));
        assertTrue(handler.supports("troubleshoot_action"));
        assertFalse(handler.supports("UNKNOWN_CARD"));
    }

    @Test
    @DisplayName("测试正常提交排障应急处置卡片成功生成 OPS- 格式工单号")
    void testSubmitTroubleshootCardSuccess() {
        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("act_ops_001");
        request.setSessionId("sess_ops_001");
        request.setCardType("TROUBLESHOOT_ACTION");
        request.setFormValues(Map.of(
                "service", "order-service",
                "action_type", "Kill阻塞慢查询并临时扩容连接池",
                "target_identifier", "trx_10423"
        ));

        CardSubmitResult result = handler.handleSubmit(request);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertNotNull(result.getTicketId());
        assertTrue(result.getTicketId().startsWith("OPS-"), "工单号必须以 OPS- 开头");
        assertTrue(result.getMessage().contains("order-service"));
    }
}
