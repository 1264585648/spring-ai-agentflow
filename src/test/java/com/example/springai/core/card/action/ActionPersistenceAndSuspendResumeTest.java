package com.example.springai.core.card.action;

import com.example.springai.capability.ops.card.OpsActionCardSubmitHandler;
import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.core.agent.GeneralChatAgent;
import com.example.springai.core.agent.SubAgent;
import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.dto.CardCancelRequest;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.CardExecutionUnknownException;
import com.example.springai.core.card.exception.CardNotFoundException;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.port.ActionLedgerPort;
import com.example.springai.core.card.state.ApprovalState;
import com.example.springai.core.card.state.CardState;
import com.example.springai.core.card.state.ExecutionState;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.context.StepResult;
import com.example.springai.core.context.TurnCheckpoint;
import com.example.springai.core.context.TurnCheckpointStore;
import com.example.springai.core.context.TurnState;
import com.example.springai.core.pipeline.impl.AgentPipelineServiceImpl;
import com.example.springai.core.routing.IntentResolver;
import com.example.springai.core.routing.Resolution;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.routing.dto.PlanType;
import com.example.springai.infra.persistence.adapter.JpaActionLedgerAdapter;
import com.example.springai.infra.persistence.checkpoint.PersistentTurnCheckpointStore;
import com.example.springai.infra.persistence.entity.ActionLedgerEntity;
import com.example.springai.infra.persistence.entity.TurnCheckpointEntity;
import com.example.springai.infra.persistence.repository.ActionLedgerRepository;
import com.example.springai.infra.persistence.repository.TurnCheckpointRepository;
import com.example.springai.infra.sse.SseEmitterManager;
import com.example.springai.infra.sse.SseEventPublisher;
import com.example.springai.web.dto.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Answers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

/**
 * 阶段一原型验收场景全覆盖测试：
 * 1. 并发提交同一审批：只有一次有效状态迁移，外部动作只触发一次；
 * 2. 外部写成功、结果落库前崩溃：恢复后识别已有结果或进入对账，不盲目重做；
 * 3. 等待确认时进程重启：回合可恢复，过期或已取消的审批不能继续执行；
 * 4. 等待确认期间卸载/升级工具：按版本规则重新审批或取消，不隐式切换实现。
 */
@Timeout(15)
class ActionPersistenceAndSuspendResumeTest {

    private static InteractiveCard buildOpsCard(String actionId, String turnId, String toolVersion) {
        InteractiveCard card = new InteractiveCard();
        card.setActionId(actionId);
        card.setTurnId(turnId);
        card.setCardType(OpsActionCardSubmitHandler.CARD_TYPE_TROUBLESHOOT);
        card.setTitle("应急处置确认卡片");
        card.setSimulation(true);
        card.setToolVersion(toolVersion);
        CardFormField poolField = new CardFormField("max_pool_size", "连接池上限", "number", 80, true, true);
        poolField.setMinLimit(10);
        poolField.setMaxLimit(500);
        card.setFields(List.of(
                new CardFormField("service", "目标服务", "text", "order-service", false, true),
                new CardFormField("action_type", "处置动作", "text", "KILL_SLOW_SESSION_AND_EXPAND_POOL", false, true),
                new CardFormField("target_identifier", "慢查询会话", "text", "trx_9001", false, true),
                poolField,
                new CardFormField("remark", "备注", "textarea", "批准扩容止血", true, false)
        ));
        return card;
    }

    private static CardSubmitRequest buildSubmitRequest(String actionId, String sessionId) {
        CardSubmitRequest req = new CardSubmitRequest();
        req.setActionId(actionId);
        req.setSessionId(sessionId);
        req.setCardType(OpsActionCardSubmitHandler.CARD_TYPE_TROUBLESHOOT);
        req.setFormValues(Map.of(
                "service", "order-service",
                "action_type", "KILL_SLOW_SESSION_AND_EXPAND_POOL",
                "target_identifier", "trx_9001",
                "max_pool_size", 90,
                "remark", "SRE审批通过"
        ));
        return req;
    }

    /**
     * 模拟数据库仓储替身：验证 JpaActionLedgerAdapter 与 PersistentTurnCheckpointStore 在跨实例/重启下的真实持久化行为。
     */
    private static ActionLedgerRepository inMemoryJpaLedgerRepo() {
        Map<String, ActionLedgerEntity> db = new ConcurrentHashMap<>();
        AtomicLong idGen = new AtomicLong(1);
        return mock(ActionLedgerRepository.class, invocation -> {
            String m = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            synchronized (db) {
                if ("save".equals(m)) {
                    ActionLedgerEntity e = (ActionLedgerEntity) args[0];
                    if (e.getId() == null) e.setId(idGen.getAndIncrement());
                    db.put(e.getActionId(), copyLedgerEntity(e));
                    return e;
                }
                if ("findByActionId".equals(m)) {
                    String actionId = (String) args[0];
                    ActionLedgerEntity found = db.get(actionId);
                    return found == null ? Optional.empty() : Optional.of(copyLedgerEntity(found));
                }
                if ("casTransition".equals(m)) {
                    String actionId = (String) args[0];
                    String expectedIssuanceId = (String) args[1];
                    String expectedCardState = (String) args[2];
                    ActionLedgerEntity current = db.get(actionId);
                    if (current == null
                            || !Objects.equals(current.getIssuanceId(), expectedIssuanceId)
                            || !Objects.equals(current.getCardState(), expectedCardState)) {
                        return 0;
                    }
                    current.setApprovalState((String) args[3]);
                    current.setExecutionState((String) args[4]);
                    current.setCardState((String) args[5]);
                    current.setActorId((String) args[6]);
                    current.setSubmittedValuesJson((String) args[7]);
                    current.setTicketId((String) args[8]);
                    current.setFailReason((String) args[9]);
                    current.setSubmitTime((Long) args[10]);
                    return 1;
                }
                if ("updateState".equals(m)) {
                    String actionId = (String) args[0];
                    String expectedCurrentState = (String) args[1];
                    ActionLedgerEntity current = db.get(actionId);
                    if (current == null || !Objects.equals(current.getCardState(), expectedCurrentState)) {
                        return 0;
                    }
                    current.setApprovalState((String) args[2]);
                    current.setExecutionState((String) args[3]);
                    current.setCardState((String) args[4]);
                    current.setTicketId((String) args[5]);
                    current.setFailReason((String) args[6]);
                    current.setSubmitTime((Long) args[7]);
                    return 1;
                }
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static ActionLedgerEntity copyLedgerEntity(ActionLedgerEntity s) {
        return new ActionLedgerEntity(s.getId(), s.getActionId(), s.getSessionId(), s.getTurnId(),
                s.getCardType(), s.getTitle(), s.getIssuanceId(), s.getIdempotencyKey(), s.getToolVersion(),
                s.getApprovalState(), s.getExecutionState(), s.getCardState(), s.getSnapshotJson(),
                s.getSubmittedValuesJson(), s.getActorId(), s.getTicketId(), s.getFailReason(),
                s.getIssueTime(), s.getExpireAt(), s.getSubmitTime(), s.getCreatedAt(), s.getUpdatedAt());
    }

    private static TurnCheckpointRepository inMemoryJpaCheckpointRepo() {
        Map<String, TurnCheckpointEntity> db = new ConcurrentHashMap<>();
        AtomicLong idGen = new AtomicLong(1);
        return mock(TurnCheckpointRepository.class, invocation -> {
            String m = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            synchronized (db) {
                if ("save".equals(m)) {
                    TurnCheckpointEntity e = (TurnCheckpointEntity) args[0];
                    if (e.getId() == null) e.setId(idGen.getAndIncrement());
                    db.put(e.getTurnId(), copyCheckpointEntity(e));
                    return e;
                }
                if ("findByTurnId".equals(m)) {
                    String turnId = (String) args[0];
                    TurnCheckpointEntity found = db.get(turnId);
                    return found == null ? Optional.empty() : Optional.of(copyCheckpointEntity(found));
                }
                if ("findByPendingActionId".equals(m)) {
                    String actionId = (String) args[0];
                    return db.values().stream()
                            .filter(e -> Objects.equals(e.getPendingActionId(), actionId))
                            .findFirst()
                            .map(ActionPersistenceAndSuspendResumeTest::copyCheckpointEntity);
                }
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static TurnCheckpointEntity copyCheckpointEntity(TurnCheckpointEntity s) {
        return new TurnCheckpointEntity(s.getId(), s.getTurnId(), s.getSessionId(), s.getUserId(),
                s.getTurnState(), s.getOriginalQuery(), s.getEffectiveQuery(), s.getPendingActionId(),
                s.getExpectedIssuanceId(), s.getExpectedToolVersion(), s.getRouteStepsJson(),
                s.getCompletedStepsJson(), s.getSuspendedStepOrder(), s.getTotalSteps(),
                s.getTicketId(), s.getResumeOutcome(), s.getExpireAt(), s.getCreatedAt(), s.getUpdatedAt());
    }

    @Test
    @DisplayName("验收场景 1：多线程并发提交同一审批，持久账本 CAS 保证只有一次有效状态迁移，外部动作只触发一次")
    void scenario1_concurrentSubmissionsTriggerExternalActionExactlyOnce() throws Exception {
        ActionLedgerRepository ledgerRepo = inMemoryJpaLedgerRepo();
        ActionLedgerPort ledgerPort = new JpaActionLedgerAdapter(ledgerRepo, new ObjectMapper());
        // 模拟两个独立节点/注册表实例共享同一持久账本
        CardRegistry node1Registry = new InMemoryCardRegistry(Clock.systemUTC(), ledgerPort);
        CardRegistry node2Registry = new InMemoryCardRegistry(Clock.systemUTC(), ledgerPort);

        AtomicInteger externalWriteCount = new AtomicInteger(0);
        MockOpsActionAdapter opsPort = new MockOpsActionAdapter() {
            @Override
            public synchronized com.example.springai.capability.ops.model.OpsActionResult executeAction(
                    com.example.springai.capability.ops.model.OpsActionParam input) {
                externalWriteCount.incrementAndGet();
                return super.executeAction(input);
            }
        };
        OpsActionCardSubmitHandler handler = new OpsActionCardSubmitHandler(opsPort);

        node1Registry.issue("sess-concurrent", buildOpsCard("act-concurrent-1", "turn-1", "v1"));

        ActionGate gate1 = new ActionGate(node1Registry, List.of(handler), ActionAuthorizer.DENY_ALL, true, false);
        ActionGate gate2 = new ActionGate(node2Registry, List.of(handler), ActionAuthorizer.DENY_ALL, true, false);

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final ActionGate gate = (i % 2 == 0) ? gate1 : gate2;
                tasks.add(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        var res = gate.submit(buildSubmitRequest("act-concurrent-1", "sess-concurrent"), null);
                        return res.getResult().isSuccess();
                    } catch (CardStateConflictException ex) {
                        return false;
                    }
                });
            }
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                futures.add(pool.submit(task));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int successCount = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(5, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }
            assertEquals(1, successCount, "并发提交只能有 1 次成功");
            assertEquals(1, externalWriteCount.get(), "外部写操作只能触发 1 次");

            CardRecord finalRecord = node2Registry.getRecord("act-concurrent-1").orElseThrow();
            assertEquals(CardState.SUBMITTED, finalRecord.getState());
            assertEquals(ApprovalState.APPROVED, finalRecord.getApprovalState());
            assertEquals(ExecutionState.SUCCEEDED, finalRecord.getExecutionState());
            assertNotNull(finalRecord.getTicketId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("验收场景 2：外部写成功、结果落库前崩溃：恢复后通过幂等键对账识别已有结果并恢复回合，绝不盲目重做")
    void scenario2_crashAfterExternalWriteBeforeCompleteReconcilesWithoutReExecuting() {
        ActionLedgerRepository ledgerRepo = inMemoryJpaLedgerRepo();
        TurnCheckpointRepository checkpointRepo = inMemoryJpaCheckpointRepo();
        AtomicBoolean simulateCrashOnComplete = new AtomicBoolean(true);

        ActionLedgerPort crashablePort = new JpaActionLedgerAdapter(ledgerRepo, new ObjectMapper()) {
            @Override
            public boolean updateState(String actionId, CardState expectedCurrentState, CardRecord updated) {
                if (simulateCrashOnComplete.get() && updated.getState() == CardState.SUBMITTED) {
                    throw new IllegalStateException("模拟外部写成功后、归档落库前数据库/进程崩溃");
                }
                return super.updateState(actionId, expectedCurrentState, updated);
            }
        };

        CardRegistry registryBeforeCrash = new InMemoryCardRegistry(Clock.systemUTC(), crashablePort);
        TurnCheckpointStore checkpointStoreBeforeCrash = new PersistentTurnCheckpointStore(checkpointRepo, Clock.systemUTC());

        AtomicInteger externalWriteCount = new AtomicInteger(0);
        MockOpsActionAdapter opsPort = new MockOpsActionAdapter() {
            @Override
            public synchronized com.example.springai.capability.ops.model.OpsActionResult executeAction(
                    com.example.springai.capability.ops.model.OpsActionParam input) {
                externalWriteCount.incrementAndGet();
                return super.executeAction(input);
            }
        };
        OpsActionCardSubmitHandler handler = new OpsActionCardSubmitHandler(opsPort);

        registryBeforeCrash.issue("sess-crash", buildOpsCard("act-crash-1", "turn-crash-1", "v1"));
        CardRecord issued = registryBeforeCrash.getRecord("act-crash-1").orElseThrow();

        checkpointStoreBeforeCrash.saveWaitingApproval(TurnCheckpoint.builder()
                .turnId("turn-crash-1")
                .sessionId("sess-crash")
                .originalQuery("order-service 504 慢查询止血")
                .pendingActionId("act-crash-1")
                .expectedIssuanceId(issued.getIssuanceId())
                .expectedToolVersion("v1")
                .expireAt(issued.getExpireAt())
                .build());

        ActionGate gateBeforeCrash = new ActionGate(
                registryBeforeCrash, List.of(handler), ActionAuthorizer.DENY_ALL, true, false, checkpointStoreBeforeCrash);

        // 1. 触发提交：外部 opsPort.executeAction 已经执行成功（externalWriteCount=1），但落库 complete 崩溃
        assertThrows(CardExecutionUnknownException.class,
                () -> gateBeforeCrash.submit(buildSubmitRequest("act-crash-1", "sess-crash"), null));
        assertEquals(1, externalWriteCount.get(), "外部写动作已执行 1 次");

        // 2. 模拟进程恢复：创建全新的 Registry、CheckpointStore 和 ActionGate 实例连接同一数据库
        simulateCrashOnComplete.set(false);
        CardRegistry registryAfterRestart = new InMemoryCardRegistry(
                Clock.systemUTC(), new JpaActionLedgerAdapter(ledgerRepo, new ObjectMapper()));
        TurnCheckpointStore checkpointStoreAfterRestart = new PersistentTurnCheckpointStore(checkpointRepo, Clock.systemUTC());
        ActionGate gateAfterRestart = new ActionGate(
                registryAfterRestart, List.of(handler), ActionAuthorizer.DENY_ALL, true, false, checkpointStoreAfterRestart);

        CardRecord afterCrashRecord = registryAfterRestart.getRecord("act-crash-1").orElseThrow();
        assertEquals(ApprovalState.APPROVED, afterCrashRecord.getApprovalState());
        assertEquals(ExecutionState.EXECUTION_UNKNOWN, afterCrashRecord.getExecutionState());

        // 3. 严禁再次直接 submit 触发重复写
        assertThrows(CardStateConflictException.class,
                () -> gateAfterRestart.submit(buildSubmitRequest("act-crash-1", "sess-crash"), null));
        assertEquals(1, externalWriteCount.get(), "拒绝盲目重试，外部写次数保持为 1");

        // 4. 执行对账恢复 reconcile：识别 opsPort 中已记录的幂等结果，收敛账本并恢复挂起回合
        ActionGate.SubmissionResult reconciled = gateAfterRestart.reconcile("act-crash-1", "sess-crash");
        assertTrue(reconciled.getResult().isSuccess());
        assertNotNull(reconciled.getResult().getTicketId());
        assertEquals(1, externalWriteCount.get(), "对账过程绝不二次触发外部写操作");

        CardRecord converged = registryAfterRestart.getRecord("act-crash-1").orElseThrow();
        assertEquals(CardState.SUBMITTED, converged.getState());
        assertEquals(ApprovalState.APPROVED, converged.getApprovalState());
        assertEquals(ExecutionState.SUCCEEDED, converged.getExecutionState());
        assertEquals(reconciled.getResult().getTicketId(), converged.getTicketId());

        TurnCheckpoint recoveredTurn = checkpointStoreAfterRestart.findByTurnId("turn-crash-1").orElseThrow();
        assertEquals(TurnState.COMPLETED, recoveredTurn.getTurnState());
        assertEquals(reconciled.getResult().getTicketId(), recoveredTurn.getTicketId());
        assertFalse(recoveredTurn.getCompletedSteps().isEmpty());
    }

    @Test
    @DisplayName("验收场景 3：等待确认时进程重启：有效回合可从持久化检查点恢复；已过期或已取消的审批重启后不能继续执行")
    void scenario3_processRestartRecoversValidCheckpointAndBlocksExpiredOrCancelledApprovals() {
        ActionLedgerRepository ledgerRepo = inMemoryJpaLedgerRepo();
        TurnCheckpointRepository checkpointRepo = inMemoryJpaCheckpointRepo();

        Instant t0 = Instant.parse("2026-10-21T10:00:00Z");
        Clock issueClock = Clock.fixed(t0, ZoneOffset.UTC);

        // --- 重启前（节点实例 1）：签发 3 张卡片与对应回合检查点 ---
        CardRegistry registryNode1 = new InMemoryCardRegistry(
                issueClock, 1000, 60_000L, 3600_000L, new JpaActionLedgerAdapter(ledgerRepo, new ObjectMapper()));
        TurnCheckpointStore checkpointNode1 = new PersistentTurnCheckpointStore(checkpointRepo, issueClock);
        OpsActionCardSubmitHandler handler = new OpsActionCardSubmitHandler(new MockOpsActionAdapter());
        ActionGate gateNode1 = new ActionGate(
                registryNode1, List.of(handler), ActionAuthorizer.DENY_ALL, true, false, checkpointNode1);

        // (A) 正常待审批回合 turn-valid
        registryNode1.issue("sess-restart", buildOpsCard("act-valid", "turn-valid", "v1"));
        CardRecord validIssued = registryNode1.getRecord("act-valid").orElseThrow();
        checkpointNode1.saveWaitingApproval(TurnCheckpoint.builder()
                .turnId("turn-valid").sessionId("sess-restart").originalQuery("order-service 扩容")
                .pendingActionId("act-valid").expectedIssuanceId(validIssued.getIssuanceId())
                .expectedToolVersion("v1").expireAt(validIssued.getExpireAt()).build());

        // (B) 重启前用户主动取消的回合 turn-cancelled
        registryNode1.issue("sess-restart", buildOpsCard("act-cancelled", "turn-cancelled", "v1"));
        CardRecord cancelledIssued = registryNode1.getRecord("act-cancelled").orElseThrow();
        checkpointNode1.saveWaitingApproval(TurnCheckpoint.builder()
                .turnId("turn-cancelled").sessionId("sess-restart").originalQuery("order-service 取消测试")
                .pendingActionId("act-cancelled").expectedIssuanceId(cancelledIssued.getIssuanceId())
                .expectedToolVersion("v1").expireAt(cancelledIssued.getExpireAt()).build());
        gateNode1.cancel(new CardCancelRequest("act-cancelled", "sess-restart", "重启前已放弃"), null);

        // (C) 待审批但在重启期间过期的回合 turn-expired
        registryNode1.issue("sess-restart", buildOpsCard("act-expired", "turn-expired", "v1"));
        CardRecord expiredIssued = registryNode1.getRecord("act-expired").orElseThrow();
        checkpointNode1.saveWaitingApproval(TurnCheckpoint.builder()
                .turnId("turn-expired").sessionId("sess-restart").originalQuery("order-service 过期测试")
                .pendingActionId("act-expired").expectedIssuanceId(expiredIssued.getIssuanceId())
                .expectedToolVersion("v1").expireAt(expiredIssued.getExpireAt()).build());

        // --- 模拟进程重启（节点实例 2，时间推进 10 秒：act-valid 未过期） ---
        Clock restartClock10s = Clock.fixed(t0.plusSeconds(10), ZoneOffset.UTC);
        CardRegistry registryAfterRestart = new InMemoryCardRegistry(
                restartClock10s, 1000, 60_000L, 3600_000L, new JpaActionLedgerAdapter(ledgerRepo, new ObjectMapper()));
        TurnCheckpointStore checkpointAfterRestart = new PersistentTurnCheckpointStore(checkpointRepo, restartClock10s);
        ActionGate gateAfterRestart = new ActionGate(
                registryAfterRestart, List.of(handler), ActionAuthorizer.DENY_ALL, true, false, checkpointAfterRestart);

        // 验证 (A)：有效待确认回合在进程重启后成功执行并恢复回合检查点为 COMPLETED
        var validSubmission = gateAfterRestart.submit(buildSubmitRequest("act-valid", "sess-restart"), null);
        assertTrue(validSubmission.getResult().isSuccess());
        TurnCheckpoint resumedValid = checkpointAfterRestart.findByTurnId("turn-valid").orElseThrow();
        assertEquals(TurnState.COMPLETED, resumedValid.getTurnState());
        assertEquals(validSubmission.getResult().getTicketId(), resumedValid.getTicketId());

        // 验证 (B)：已取消的审批在进程重启后严禁执行
        assertThrows(CardStateConflictException.class,
                () -> gateAfterRestart.submit(buildSubmitRequest("act-cancelled", "sess-restart"), null));
        assertEquals(TurnState.CANCELLED, checkpointAfterRestart.findByTurnId("turn-cancelled").orElseThrow().getTurnState());

        // --- 时间推进至 120 秒（超过 60 秒 TTL）：验证 (C) 过期审批重启后严禁执行 ---
        Clock restartClockExpired = Clock.fixed(t0.plusSeconds(120), ZoneOffset.UTC);
        CardRegistry registryExpired = new InMemoryCardRegistry(
                restartClockExpired, 1000, 60_000L, 3600_000L, new JpaActionLedgerAdapter(ledgerRepo, new ObjectMapper()));
        TurnCheckpointStore checkpointExpired = new PersistentTurnCheckpointStore(checkpointRepo, restartClockExpired);
        ActionGate gateExpired = new ActionGate(
                registryExpired, List.of(handler), ActionAuthorizer.DENY_ALL, true, false, checkpointExpired);

        assertThrows(CardNotFoundException.class,
                () -> gateExpired.submit(buildSubmitRequest("act-expired", "sess-restart"), null));
    }

    @Test
    @DisplayName("验收场景 4：等待确认期间卸载或升级工具：按版本规则拒绝隐式切换实现")
    void scenario4_toolVersionChangeDuringWaitingApprovalRejectsImplicitSwitch() {
        InMemoryCardRegistry registry = new InMemoryCardRegistry();
        PersistentTurnCheckpointStore checkpointStore = new PersistentTurnCheckpointStore();

        // 签发时锁定工具版本为 v1
        registry.issue("sess-ver", buildOpsCard("act-ver-1", "turn-ver-1", "v1"));
        CardRecord issued = registry.getRecord("act-ver-1").orElseThrow();
        checkpointStore.saveWaitingApproval(TurnCheckpoint.builder()
                .turnId("turn-ver-1")
                .sessionId("sess-ver")
                .originalQuery("order-service 504")
                .pendingActionId("act-ver-1")
                .expectedIssuanceId(issued.getIssuanceId())
                .expectedToolVersion("v1")
                .expireAt(issued.getExpireAt())
                .build());

        AtomicInteger v2Executions = new AtomicInteger(0);
        // 等待确认期间，工具/处理器升级为 v2
        CardSubmitHandler upgradedV2Handler = new CardSubmitHandler() {
            @Override
            public boolean supports(String cardType) {
                return OpsActionCardSubmitHandler.CARD_TYPE_TROUBLESHOOT.equals(cardType);
            }

            @Override
            public boolean isSimulation() {
                return true;
            }

            @Override
            public String toolVersion() {
                return "v2";
            }

            @Override
            public CardSubmitResult handleSubmit(ApprovedCardSubmission submission) {
                v2Executions.incrementAndGet();
                return CardSubmitResult.ok("T-V2", "executed by v2");
            }
        };

        ActionGate gateWithV2 = new ActionGate(
                registry, List.of(upgradedV2Handler), ActionAuthorizer.DENY_ALL, true, false, checkpointStore);

        CardStateConflictException ex = assertThrows(CardStateConflictException.class,
                () -> gateWithV2.submit(buildSubmitRequest("act-ver-1", "sess-ver"), null));
        assertTrue(ex.getMessage().contains("工具版本已变化"));
        assertEquals(0, v2Executions.get(), "严禁隐式切换至 v2 实现执行");
        assertEquals(CardState.ISSUED, registry.getRecord("act-ver-1").orElseThrow().getState());
        assertEquals(TurnState.WAITING_APPROVAL, checkpointStore.findByTurnId("turn-ver-1").orElseThrow().getTurnState());
    }

    @Test
    @DisplayName("流水线端到端验证：下发卡片自动保存 WAITING_APPROVAL 检查点并释放运行槽位；取消回合联动废止待审批卡片")
    void pipelineSuspendsTurnOnCardIssueAndCancelTurnInvalidatesPendingCard() {
        SseEventPublisher publisher = mock(SseEventPublisher.class, invocation -> {
            if ("isStaleTurn".equals(invocation.getMethod().getName())) return false;
            if (invocation.getMethod().getReturnType() == boolean.class) return true;
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        InMemoryCardRegistry cardRegistry = new InMemoryCardRegistry();
        PersistentTurnCheckpointStore checkpointStore = new PersistentTurnCheckpointStore();

        IntentResolver routeResolver = new IntentResolver() {
            @Override
            public String level() { return "L3"; }
            @Override
            public Optional<Resolution> resolve(AgentContext context) {
                DispatchStep step = DispatchStep.builder()
                        .stepOrder(1).targetAgent("SRE_COPILOT_AGENT").targetAgentName("SRE专家").build();
                return Optional.of(new Resolution.Route("L3", PlanType.SINGLE, List.of(step), "排障路由"));
            }
            @Override
            public int getOrder() { return 1; }
        };

        SubAgent cardIssuingAgent = new SubAgent() {
            @Override
            public String name() { return "SRE_COPILOT_AGENT"; }
            @Override
            public boolean supports(String code) { return "SRE_COPILOT_AGENT".equals(code); }
            @Override
            public void execute(AgentContext context, DispatchStep step) { executeStep(context, step); }
            @Override
            public StepResult executeStep(AgentContext context, DispatchStep step) {
                context.getSink().message("发现连接池打满，请确认应急扩容提案：");
                context.getSink().card(buildOpsCard("act-pipe-1", context.getTurnId(), "v1"));
                StepResult res = StepResult.builder()
                        .stepOrder(1).agentCode("SRE_COPILOT_AGENT").status(StepResult.Status.SUCCESS)
                        .summary("已生成扩容提案卡片").build();
                context.addStepResult(res);
                return res;
            }
        };

        AgentPipelineServiceImpl pipeline = new AgentPipelineServiceImpl(
                publisher,
                List.of(routeResolver),
                null,
                List.of(cardIssuingAgent),
                mock(GeneralChatAgent.class),
                cardRegistry,
                null,
                null,
                checkpointStore,
                Runnable::run
        );

        ChatRequest askReq = new ChatRequest();
        askReq.setSessionId("sess-pipe");
        askReq.setTurnId("turn-pipe-1");
        askReq.setQuery("order-service 504 请止血");

        pipeline.process(askReq);

        // 1. 回合已释放运行槽位（activeTurnCount == 0），且检查点处于 WAITING_APPROVAL
        assertEquals(0, pipeline.getActiveTurnCount(), "等待人工确认期间必须释放执行槽位，不阻塞会话邮箱");
        TurnCheckpoint cp = checkpointStore.findByTurnId("turn-pipe-1").orElseThrow();
        assertEquals(TurnState.WAITING_APPROVAL, cp.getTurnState());
        assertEquals("act-pipe-1", cp.getPendingActionId());
        assertEquals(1, cp.getCompletedSteps().size());

        // 2. 用户在等待审批期间调用 cancelActiveTurn(sessionId, turnId)，联动取消检查点与待确认卡片
        assertTrue(pipeline.cancelActiveTurn("sess-pipe", "turn-pipe-1"));
        assertEquals(TurnState.CANCELLED, checkpointStore.findByTurnId("turn-pipe-1").orElseThrow().getTurnState());
        assertEquals(CardState.CANCELLED, cardRegistry.getRecord("act-pipe-1").orElseThrow().getState());
        assertEquals(ApprovalState.CANCELLED, cardRegistry.getRecord("act-pipe-1").orElseThrow().getApprovalState());
    }
}
