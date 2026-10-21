package com.example.springai.capability.ops.card;

import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.model.OpsActionResult;
import com.example.springai.capability.ops.port.OpsActionPort;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.action.ApprovedCardSubmission;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.model.CardRecord;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 原始 HTTP DTO 不能传入执行器；纯参数预检在 ActionGate 申领之前完成。 */
@Component
@Order(10)
public class OpsActionCardSubmitHandler implements CardSubmitHandler {
    public static final String CARD_TYPE_OPS = "OPS_ACTION";
    public static final String CARD_TYPE_TROUBLESHOOT = "TROUBLESHOOT_ACTION";
    private final OpsActionPort port;
    public OpsActionCardSubmitHandler(OpsActionPort port) { this.port = Objects.requireNonNull(port); }
    @Override public boolean supports(String type) {
        return CARD_TYPE_OPS.equalsIgnoreCase(type) || CARD_TYPE_TROUBLESHOOT.equalsIgnoreCase(type);
    }
    @Override public boolean isSimulation() { return port.isSimulation(); }
    @Override public void validate(ApprovedCardSubmission submission) { parameters(submission); }
    @Override public CardSubmitResult handleSubmit(ApprovedCardSubmission submission) {
        OpsActionParam param = parameters(submission);
        OpsActionResult result = port.executeAction(param);
        if (result == null || !result.isSuccess()) return CardSubmitResult.fail("执行端口未返回确定成功结果");
        return CardSubmitResult.ok(result.getTicketId(), (isSimulation() ? "模拟执行（未变更真实系统）：" : "")
                + (result.getAuditMessage() == null ? "" : result.getAuditMessage()));
    }
    @Override
    public Optional<CardSubmitResult> reconcile(CardRecord record) {
        if (record == null) return Optional.empty();
        String key = record.getEffectiveIdempotencyKey();
        return port.findExecutedResult(key)
                .filter(r -> r != null && r.isSuccess() && r.getTicketId() != null && !r.getTicketId().isBlank())
                .map(r -> CardSubmitResult.ok(r.getTicketId(),
                        (isSimulation() ? "模拟执行（对账恢复）：" : "对账恢复：")
                                + (r.getAuditMessage() == null ? "" : r.getAuditMessage())));
    }
    private static OpsActionParam parameters(ApprovedCardSubmission submission) {
        Map<String, Object> values = submission.getFormValues();
        String action = required(values, "action_type");
        // 仅兼容已经签发的旧版展示值；未知自由文本不能直接成为执行命令。
        if ("Kill阻塞慢查询并临时扩容连接池".equals(action)) action = "KILL_SLOW_SESSION_AND_EXPAND_POOL";
        else if ("Kill阻塞慢查询".equals(action)) action = "KILL_SLOW_SESSION";
        else if ("临时扩容连接池".equals(action)) action = "EXPAND_POOL_LIMIT";
        if (!Set.of("KILL_SLOW_SESSION", "EXPAND_POOL_LIMIT", "KILL_SLOW_SESSION_AND_EXPAND_POOL", "RESTART_POD").contains(action)) {
            throw new IllegalArgumentException("不支持的运维动作类型");
        }
        Integer pool = null;
        if (values.get("max_pool_size") != null) {
            try { pool = new BigDecimal(values.get("max_pool_size").toString()).intValueExact(); }
            catch (ArithmeticException | NumberFormatException ex) { throw new IllegalArgumentException("连接池上限必须为整数"); }
            if (pool < 10 || pool > 500) throw new IllegalArgumentException("连接池上限须在 10~500 之间");
        }
        if ((action.equals("EXPAND_POOL_LIMIT") || action.equals("KILL_SLOW_SESSION_AND_EXPAND_POOL")) && pool == null) {
            throw new IllegalArgumentException("扩容动作缺少 max_pool_size");
        }
        String reason = values.get("remark") instanceof String text && !text.isBlank() ? text : "人工确认";
        String idemKey = submission.getIdempotencyKey() != null && !submission.getIdempotencyKey().isBlank()
                ? submission.getIdempotencyKey() : submission.getActionId();
        return OpsActionParam.builder().service(required(values, "service")).actionType(action)
                .targetIdentifier(required(values, "target_identifier")).maxPoolSize(pool)
                .reason("actionId=" + submission.getActionId() + "; " + reason)
                .operator(submission.getActorId()).idempotencyKey(idemKey).build();
    }
    private static String required(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof String text) || text.isBlank()) throw new IllegalArgumentException("缺少有效执行参数: " + key);
        return text;
    }
}
