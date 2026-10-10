package com.example.springai.card.spi;

import com.example.springai.card.dto.CardSubmitRequest;
import com.example.springai.card.dto.CardSubmitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TroubleshootCardSubmitHandlerTest {

    private final TroubleshootCardSubmitHandler handler = new TroubleshootCardSubmitHandler();

    @Test
    @DisplayName("测试 supports 支持 TROUBLESHOOT_ACTION 卡片类型")
    void testSupportsTroubleshootAction() {
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
                "action_type", "Kill阻塞慢查询并临时扩容连接池"
        ));

        CardSubmitResult result = handler.handleSubmit(request);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertNotNull(result.getTicketId());
        assertTrue(result.getTicketId().startsWith("OPS-"), "工单号必须以 OPS- 开头");
        assertTrue(result.getMessage().contains("order-service"));
    }
}
