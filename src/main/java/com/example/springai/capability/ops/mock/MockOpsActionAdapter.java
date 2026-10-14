package com.example.springai.capability.ops.mock;

import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.model.OpsActionResult;
import com.example.springai.capability.ops.port.OpsActionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.*;

/** 有界的模拟执行记录；同键并发执行与记录提交处于同一临界区。没有外部副作用。 */
@Component
@ConditionalOnProperty(name = "troubleshoot.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockOpsActionAdapter implements OpsActionPort {
    private final Clock clock;
    private final int capacity;
    private final long retentionMs;
    private final Map<String, Entry> records = new LinkedHashMap<>();
    private static final class Entry {
        final OpsActionParam parameters;
        final OpsActionResult result;
        final long completedAt;
        Entry(OpsActionParam parameters, OpsActionResult result, long completedAt) {
            this.parameters = parameters; this.result = result; this.completedAt = completedAt;
        }
    }
    @Autowired
    public MockOpsActionAdapter() { this(Clock.systemUTC(), 10000, 3600000L); }
    public MockOpsActionAdapter(Clock clock, int capacity, long retentionMs) {
        this.clock = Objects.requireNonNull(clock);
        if (capacity < 1 || retentionMs < 1) throw new IllegalArgumentException("容量与保留时间必须为正");
        this.capacity = capacity; this.retentionMs = retentionMs;
    }
    @Override public boolean isSimulation() { return true; }
    @Override public synchronized Optional<OpsActionResult> findExecutedResult(String key) {
        evict();
        Entry entry = records.get(key);
        return entry == null ? Optional.empty() : Optional.of(copy(entry.result));
    }
    @Override public synchronized OpsActionResult executeAction(OpsActionParam input) {
        if (input == null || blank(input.getService()) || blank(input.getActionType()) || blank(input.getTargetIdentifier()) || blank(input.getOperator())) {
            throw new IllegalArgumentException("模拟执行也必须提供明确目标、动作和操作人");
        }
        OpsActionParam param = OpsActionParam.builder().service(input.getService()).actionType(input.getActionType())
                .targetIdentifier(input.getTargetIdentifier()).reason(input.getReason()).operator(input.getOperator())
                .idempotencyKey(input.getIdempotencyKey()).maxPoolSize(input.getMaxPoolSize()).build();
        evict();
        String key = param.getIdempotencyKey();
        Entry existing = blank(key) ? null : records.get(key);
        if (existing != null) {
            if (!existing.parameters.equals(param)) throw new IllegalArgumentException("同一幂等键不能绑定不同参数");
            return copy(existing.result);
        }
        if (!blank(key) && records.size() >= capacity) throw new IllegalStateException("模拟执行记录已满");
        OpsActionResult result = OpsActionResult.builder().success(true).ticketId("OPS-" + UUID.randomUUID())
                .auditMessage("模拟执行: service=" + param.getService() + ", action=" + param.getActionType()
                        + ", target=" + param.getTargetIdentifier() + ", maxPoolSize=" + param.getMaxPoolSize()
                        + ", operator=" + param.getOperator()).executedTimestamp(clock.millis()).build();
        if (!blank(key)) records.put(key, new Entry(param, result, clock.millis()));
        log.info("[Ops:Mock] 模拟动作已记录 actionId={}, ticketId={}", key, result.getTicketId());
        return copy(result);
    }
    public synchronized int size() { evict(); return records.size(); }
    private void evict() { records.values().removeIf(e -> clock.millis() - e.completedAt >= retentionMs); }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static OpsActionResult copy(OpsActionResult r) {
        return OpsActionResult.builder().success(r.isSuccess()).ticketId(r.getTicketId()).auditMessage(r.getAuditMessage())
                .executedTimestamp(r.getExecutedTimestamp()).build();
    }
}
