package com.example.springai.capability.ops.card;

import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.model.OpsActionResult;
import com.example.springai.capability.ops.port.OpsActionPort;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.action.ApprovedCardSubmission;
import com.example.springai.core.card.dto.CardSubmitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** 只接受 ActionGate 批准的输入；不得自行创建 Mock 或使用缺失目标的默认值。 */
@Component
@Order(10)
@Slf4j
public class OpsActionCardSubmitHandler implements CardSubmitHandler {
    public static final String CARD_TYPE_OPS = "OPS_ACTION";
    public static final String CARD_TYPE_TROUBLESHOOT = "TROUBLESHOOT_ACTION";
    private final OpsActionPort troubleshootActionPort;

    public OpsActionCardSubmitHandler(OpsActionPort troubleshootActionPort) {
        this.troubleshootActionPort = Objects.requireNonNull(troubleshootActionPort);
    }

    @Override
    public boolean supports(String cardType) {
        return CARD_TYPE_OPS.equalsIgnoreCase(cardType) || CARD_TYPE_TROUBLESHOOT.equalsIgnoreCase(cardType);
    }

    @Override
    public boolean isSimulation() {
        // 检查实际注入的适配器，不根据配置字符串就宣称执行是模拟的。
        return troubleshootActionPort instanceof MockOpsActionAdapter;
    }

    @Override
    public void validate(ApprovedCardSubmission submission) {
        required(submission.getFormValues(), "service");
        required(submission.getFormValues(), "action_type");
        required(submission.getFormValues(), "target_identifier");
    }

    @Override
    public CardSubmitResult handleSubmit(ApprovedCardSubmission submission) {
        validate(submission);
        Map<String, Object> values = submission.getFormValues();
        OpsActionParam param = OpsActionParam.builder()
                .service(required(values, "service"))
                .actionType(required(values, "action_type"))
                .targetIdentifier(required(values, "target_identifier"))
                .operator(submission.getActorId())
                .reason("actionId=" + submission.getActionId() + "; " + values.getOrDefault("remark", "人工确认"))
                .build();
        OpsActionResult result = troubleshootActionPort.executeAction(param);
        if (result == null || !result.isSuccess()) {
            return CardSubmitResult.fail("执行端口未返回确定成功结果");
        }
        log.info("[CardSPI:Ops] actionId={}, actor={}, ticketId={}", submission.getActionId(),
                submission.getActorId(), result.getTicketId());
        return CardSubmitResult.ok(result.getTicketId(),
                (isSimulation() ? "模拟执行（未变更真实系统）：" : "")
                        + (result.getAuditMessage() == null ? "" : result.getAuditMessage()));
    }

    private static String required(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("缺少有效执行参数: " + key);
        }
        return text;
    }
}
