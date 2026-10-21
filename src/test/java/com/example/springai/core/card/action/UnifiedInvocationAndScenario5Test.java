package com.example.springai.core.card.action;

import com.example.springai.capability.account.tool.UserAccountTool;
import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.tool.DatabaseDiagnoseTool;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.tool.LogQueryTool;
import com.example.springai.capability.ops.card.OpsActionCardSubmitHandler;
import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.tool.OpsActionTool;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.state.ApprovalState;
import com.example.springai.core.card.state.CardState;
import com.example.springai.core.card.state.ExecutionState;
import com.example.springai.core.context.TurnCheckpoint;
import com.example.springai.core.context.TurnCheckpointStore;
import com.example.springai.core.context.TurnState;
import com.example.springai.core.tool.InvocationContext;
import com.example.springai.core.tool.InvocationSource;
import com.example.springai.core.tool.L1ToolCatalog;
import com.example.springai.core.tool.L1ToolDispatcher;
import com.example.springai.core.tool.RevocableInvocationPolicy;
import com.example.springai.core.tool.SideEffectLevel;
import com.example.springai.core.tool.ToolDescriptor;
import com.example.springai.core.tool.ToolMetadata;
import com.example.springai.core.tool.UnifiedInvocationGateway;
import com.example.springai.infra.client.AgentChatClientFactory;
import com.example.springai.infra.persistence.checkpoint.PersistentTurnCheckpointStore;
import com.example.springai.web.controller.CardInteractionController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 优先级 2（统一调用入口、工具副作用分级与实时资源授权）及原型验收场景 5 专项验证测试：
 * - 验证工具注册期副作用分级（READ / WRITE / CRITICAL）与只读边界强制拦截；
 * - 验证 L1 直通、大模型 Function Calling、ActionGate 卡片写执行三条路径统一收敛至 UnifiedInvocationGateway；
 * - 验证原型验收场景 5：审批通过后权限被实时撤销，执行前在统一调用入口按实时策略拒绝，不触发外部写操作。
 */
@Timeout(15)
class UnifiedInvocationAndScenario5Test {

    private static InteractiveCard buildOpsCard(String actionId, String turnId, boolean simulation) {
        InteractiveCard card = new InteractiveCard();
        card.setActionId(actionId);
        card.setTurnId(turnId);
        card.setCardType(OpsActionCardSubmitHandler.CARD_TYPE_TROUBLESHOOT);
        card.setTitle("生产服务应急处置卡片");
        card.setSimulation(simulation);
        card.setToolVersion("v1");
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

    static class MisannotatedWriteTool {
        @Tool(description = "尝试违规标 @Tool 的写操作")
        @ToolMetadata(sideEffect = SideEffectLevel.WRITE, version = "v1", resourceType = "DANGER_DB")
        public String dropTable(String table) {
            return "dropped:" + table;
        }
    }

    @Test
    @DisplayName("工具副作用分级验证：READ 工具正常登记，CRITICAL/WRITE 工具即使误加 @Tool 也严禁进入 L1 直通或模型工具池")
    void sideEffectLevelRegistrationAndReadBoundaryEnforcement() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("logQueryTool", LogQueryTool.class, () -> new LogQueryTool(new MockLogAdapter()));
            ctx.registerBean("databaseDiagnoseTool", DatabaseDiagnoseTool.class, () -> new DatabaseDiagnoseTool(new MockDbAdapter()));
            ctx.registerBean("userAccountTool", UserAccountTool.class, UserAccountTool::new);
            ctx.registerBean("opsActionTool", OpsActionTool.class, () -> new OpsActionTool(new MockOpsActionAdapter()));
            ctx.registerBean("misannotatedWriteTool", MisannotatedWriteTool.class, MisannotatedWriteTool::new);
            ctx.register(L1ToolCatalog.class, UnifiedInvocationGateway.class, L1ToolDispatcher.class);
            ctx.refresh();

            L1ToolCatalog catalog = ctx.getBean(L1ToolCatalog.class);
            L1ToolDispatcher dispatcher = ctx.getBean(L1ToolDispatcher.class);

            // 1. 验证只读工具的描述符均为 SideEffectLevel.READ
            ToolDescriptor logDesc = catalog.findDescriptor("logQueryTool.queryServiceLogs").orElseThrow();
            assertEquals(SideEffectLevel.READ, logDesc.sideEffectLevel());
            assertEquals("SERVICE_LOG", logDesc.resourceType());

            ToolDescriptor dbDesc = catalog.findDescriptor("databaseDiagnoseTool.diagnoseDatabase").orElseThrow();
            assertEquals(SideEffectLevel.READ, dbDesc.sideEffectLevel());

            ToolDescriptor accountDesc = catalog.findDescriptor("userAccountTool.queryBalance").orElseThrow();
            assertEquals(SideEffectLevel.READ, accountDesc.sideEffectLevel());

            // 2. 验证高危工具 OpsActionTool 登记为 CRITICAL 且不可被 L1 调用
            ToolDescriptor opsDesc = catalog.findDescriptor("opsActionTool.executeEmergencyAction").orElseThrow();
            assertEquals(SideEffectLevel.CRITICAL, opsDesc.sideEffectLevel());
            assertFalse(catalog.isRegistered("opsActionTool.executeEmergencyAction"));
            assertThrows(IllegalArgumentException.class,
                    () -> dispatcher.dispatch("opsActionTool.executeEmergencyAction", "{}"));

            // 3. 即使有人在 WRITE 工具方法上误加了 @Tool 注解，因 @ToolMetadata 声明为 WRITE，仍被注册表拒绝直通调用
            ToolDescriptor misDesc = catalog.findDescriptor("misannotatedWriteTool.dropTable").orElseThrow();
            assertEquals(SideEffectLevel.WRITE, misDesc.sideEffectLevel());
            assertFalse(catalog.isRegistered("misannotatedWriteTool.dropTable"));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> dispatcher.dispatch("misannotatedWriteTool.dropTable", "{\"table\":\"users\"}"));
            assertTrue(ex.getMessage().contains("写/高危工具"));
        }
    }

    @Test
    @DisplayName("三条调用路径收敛验证：L1 直通、大模型 Function Calling、ActionGate 写执行全部汇聚至同一 UnifiedInvocationGateway 并支持实时资源拦截")
    void allThreePathsConvergeThroughUnifiedInvocationGateway() {
        RevocableInvocationPolicy policy = new RevocableInvocationPolicy(true);
        policy.grantWrite("sre-alice", "order-service");
        UnifiedInvocationGateway gateway = new UnifiedInvocationGateway(policy);

        // --- 路径 1：L1 规则直通 ---
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("userAccountTool", UserAccountTool.class, UserAccountTool::new);
            ctx.registerBean(UnifiedInvocationGateway.class, () -> gateway);
            ctx.register(L1ToolCatalog.class, L1ToolDispatcher.class);
            ctx.refresh();

            L1ToolDispatcher l1Dispatcher = ctx.getBean(L1ToolDispatcher.class);
            String l1Reply = l1Dispatcher.dispatch("userAccountTool.queryBalance", "{\"userId\":\"10001\"}", "sess-l1", "sre-alice");
            assertTrue(l1Reply.contains("10001"));
            assertEquals(1L, gateway.getReadInvocations());

            // 实时封禁 sre-alice 对账户 10001 的访问权限，L1 直通立即拦截
            policy.revokeResource("sre-alice", "10001");
            assertThrows(CardSecurityException.class,
                    () -> l1Dispatcher.dispatch("userAccountTool.queryBalance", "{\"userId\":\"10001\"}", "sess-l1", "sre-alice"));
            assertEquals(1L, gateway.getRejectedInvocations());
        }

        // --- 路径 2：大模型 Function Calling (AgentChatClientFactory) ---
        List<Prompt> capturedPrompts = new ArrayList<>();
        var defaults = ToolCallingChatOptions.builder().build();
        ChatModel model = mock(ChatModel.class, invocation -> {
            String m = invocation.getMethod().getName();
            if ("getOptions".equals(m) || "getDefaultOptions".equals(m)) return defaults;
            if ("stream".equals(m)) {
                capturedPrompts.add((Prompt) invocation.getArguments()[0]);
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("OK")))));
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        AgentPromptRegistry promptRegistry = new AgentPromptRegistry(null);
        promptRegistry.init();
        AgentChatClientFactory clientFactory = new AgentChatClientFactory(ChatClient.builder(model), promptRegistry, gateway);
        LogQueryTool logTool = new LogQueryTool(new MockLogAdapter());

        clientFactory.createClient("LOG_DIAGNOSE_AGENT", logTool).prompt().user("查日志").stream().content().blockLast();
        ToolCallingChatOptions chatOptions = assertInstanceOf(ToolCallingChatOptions.class, capturedPrompts.get(0).getOptions());
        ToolCallback logCallback = chatOptions.getToolCallbacks().get(0);

        try (var ignored = InvocationContext.openScope("sess-agent", "turn-agent-1", "sre-alice", "tenant-1")) {
            String toolOutput = logCallback.call("{\"service\":\"order-service\",\"keyword\":\"504\",\"logLevel\":\"ERROR\"}");
            assertTrue(toolOutput.contains("order-service"));
            assertEquals(2L, gateway.getReadInvocations());

            // 实时撤销会话 sess-agent 对 payment-service 的读取权限，大模型回调立即被拦截
            policy.revokeSessionResource("sess-agent", "payment-service");
            assertThrows(CardSecurityException.class,
                    () -> logCallback.call("{\"service\":\"payment-service\",\"keyword\":\"504\",\"logLevel\":\"ERROR\"}"));
            assertEquals(2L, gateway.getRejectedInvocations());
        }

        // --- 路径 3：ActionGate 卡片提交写操作 ---
        InMemoryCardRegistry cardRegistry = new InMemoryCardRegistry();
        cardRegistry.issue("sess-write", buildOpsCard("act-conv-1", "turn-w-1", false));
        OpsActionCardSubmitHandler writeHandler = new OpsActionCardSubmitHandler(new MockOpsActionAdapter() {
            @Override
            public boolean isSimulation() {
                return false;
            }
        });
        ActionGate actionGate = new ActionGate(
                cardRegistry, List.of(writeHandler), policy, false, true, null, gateway);

        ActionGate.SubmissionResult writeRes = actionGate.submit(
                buildSubmitRequest("act-conv-1", "sess-write"), () -> "sre-alice");
        assertTrue(writeRes.getResult().isSuccess());
        assertEquals(1L, gateway.getWriteInvocations());

        // 验证审计日志完整记录了 L1_DIRECT、AGENT_MODEL_TOOL、ACTION_GATE_WRITE 三类来源
        List<InvocationSource> sources = gateway.getRecentAudits().stream()
                .map(UnifiedInvocationGateway.AuditEntry::source)
                .distinct()
                .toList();
        assertTrue(sources.containsAll(List.of(
                InvocationSource.L1_DIRECT,
                InvocationSource.AGENT_MODEL_TOOL,
                InvocationSource.ACTION_GATE_WRITE
        )));
    }

    @Test
    @DisplayName("验收场景 5（分步审批后权限被撤销）：先 approve 通过，执行前实时撤销权限，executeApproved 在统一入口按实时策略拒绝，不触发外部写")
    void scenario5_permissionRevokedAfterApprovalIsRejectedBeforeExecution() {
        RevocableInvocationPolicy realTimePolicy = new RevocableInvocationPolicy(true);
        realTimePolicy.grantWrite("sre-alice", "order-service");
        UnifiedInvocationGateway gateway = new UnifiedInvocationGateway(realTimePolicy);

        CardRegistry registry = new InMemoryCardRegistry();
        TurnCheckpointStore checkpointStore = new PersistentTurnCheckpointStore();

        AtomicInteger externalPortCalls = new AtomicInteger(0);
        OpsActionCardSubmitHandler prodHandler = new OpsActionCardSubmitHandler(new MockOpsActionAdapter() {
            @Override
            public boolean isSimulation() {
                return false;
            }

            @Override
            public synchronized com.example.springai.capability.ops.model.OpsActionResult executeAction(
                    com.example.springai.capability.ops.model.OpsActionParam input) {
                externalPortCalls.incrementAndGet();
                return super.executeAction(input);
            }
        });

        registry.issue("sess-s5", buildOpsCard("act-s5-1", "turn-s5-1", false));
        CardRecord issued = registry.getRecord("act-s5-1").orElseThrow();
        checkpointStore.saveWaitingApproval(TurnCheckpoint.builder()
                .turnId("turn-s5-1")
                .sessionId("sess-s5")
                .originalQuery("order-service 504 止血")
                .pendingActionId("act-s5-1")
                .expectedIssuanceId(issued.getIssuanceId())
                .expectedToolVersion("v1")
                .expireAt(issued.getExpireAt())
                .build());

        ActionGate gate = new ActionGate(
                registry, List.of(prodHandler), realTimePolicy, false, true, checkpointStore, gateway);

        // 1. 第一步：sre-alice 审批提案通过（此时持有 order-service 写权限）
        ActionGate.ApprovalResult approved = gate.approve(
                buildSubmitRequest("act-s5-1", "sess-s5"), () -> "sre-alice");
        assertEquals("APPROVED", approved.getApprovalState());
        assertEquals("NOT_STARTED", approved.getExecutionState());
        assertEquals(0, externalPortCalls.get(), "仅审批阶段不得触发外部执行");

        CardRecord afterApprove = registry.getRecord("act-s5-1").orElseThrow();
        assertEquals(ApprovalState.APPROVED, afterApprove.getApprovalState());
        assertEquals(ExecutionState.NOT_STARTED, afterApprove.getExecutionState());

        // 2. 审批通过后、真正执行前：安全管理员实时撤销 sre-alice 对 order-service 的权限
        realTimePolicy.revokeResource("sre-alice", "order-service");

        // 3. 第二步：尝试执行已批准的动作 -> 统一调用入口在进入 handler 前按实时策略拒绝
        CardSecurityException rejectedEx = assertThrows(CardSecurityException.class,
                () -> gate.executeApproved("act-s5-1", "sess-s5", () -> "sre-alice"));
        assertTrue(rejectedEx.getMessage().contains("已被实时撤销"));
        assertEquals(0, externalPortCalls.get(), "权限撤销后严禁触发外部写操作");

        // 4. 验证账本状态收敛为 REJECTED + FAILED（而非 EXECUTION_UNKNOWN），且后续即使恢复权限也不可直接重放旧提案
        CardRecord afterReject = registry.getRecord("act-s5-1").orElseThrow();
        assertEquals(CardState.FAILED, afterReject.getState());
        assertEquals(ApprovalState.REJECTED, afterReject.getApprovalState());
        assertEquals(ExecutionState.FAILED, afterReject.getExecutionState());

        TurnCheckpoint cpAfterReject = checkpointStore.findByTurnId("turn-s5-1").orElseThrow();
        assertEquals(TurnState.CANCELLED, cpAfterReject.getTurnState());

        realTimePolicy.grantWrite("sre-alice", "order-service");
        assertThrows(CardStateConflictException.class,
                () -> gate.executeApproved("act-s5-1", "sess-s5", () -> "sre-alice"));
        assertEquals(0, externalPortCalls.get());
    }

    @Test
    @DisplayName("验收场景 5（Controller HTTP 端到端）：/api/v1/card/approve 批准后权限被撤，调用 /api/v1/card/execute 返回 403 CARD_FORBIDDEN")
    void scenario5_controllerApproveThenExecuteRejectsWith403WhenPermissionRevoked() {
        RevocableInvocationPolicy policy = new RevocableInvocationPolicy(true);
        policy.grantWrite("sre-bob", "order-service");
        UnifiedInvocationGateway gateway = new UnifiedInvocationGateway(policy);

        InMemoryCardRegistry registry = new InMemoryCardRegistry();
        registry.issue("sess-http-s5", buildOpsCard("act-http-s5", "turn-http-s5", false));

        OpsActionCardSubmitHandler handler = new OpsActionCardSubmitHandler(new MockOpsActionAdapter() {
            @Override
            public boolean isSimulation() {
                return false;
            }
        });
        ActionGate gate = new ActionGate(registry, List.of(handler), policy, false, true, null, gateway);
        CardInteractionController controller = new CardInteractionController(gate, null);

        // 1. 调用 /approve 成功
        ResponseEntity<Map<String, Object>> approveResp = controller.approveCard(
                buildSubmitRequest("act-http-s5", "sess-http-s5"), () -> "sre-bob");
        assertEquals(200, approveResp.getStatusCode().value());
        assertEquals("APPROVED", approveResp.getBody().get("approvalState"));
        assertEquals("NOT_STARTED", approveResp.getBody().get("executionState"));

        // 2. 撤销权限后调用 /execute 返回 403 CARD_FORBIDDEN
        policy.revokeActor("sre-bob");
        ResponseEntity<Map<String, Object>> execResp = controller.executeApprovedCard(
                Map.of("actionId", "act-http-s5", "sessionId", "sess-http-s5"), () -> "sre-bob");
        assertEquals(403, execResp.getStatusCode().value());
        assertEquals("CARD_FORBIDDEN", execResp.getBody().get("errorType"));
    }

    static class MixedReadClassWithWriteMethodTool {
        @Tool(description = "只读方法")
        @ToolMetadata(sideEffect = SideEffectLevel.READ, version = "v1", resourceType = "SAFE_RES")
        public String safeQuery(String id) {
            return "ok:" + id;
        }

        @Tool(description = "隐藏在类中的写方法")
        @ToolMetadata(sideEffect = SideEffectLevel.WRITE, version = "v1", resourceType = "DANGER_RES")
        public String hiddenMutate(String id) {
            return "mutated:" + id;
        }
    }

    @Test
    @DisplayName("安全加固回归：防审批后通过 /submit 篡改参数、防无 Principal 冒用审批人、防 revokeActor 后旧资源权限复活、防嵌套 Scope 丢失")
    void regression_securityHardeningForSplitApprovalAndPolicyAndNestedScope() {
        // 1. 验证嵌套 CallScope 关闭内层后自动恢复外层上下文
        try (var outer = InvocationContext.openScope("sess-outer", "turn-outer", "alice", "t1")) {
            assertEquals("sess-outer", InvocationContext.currentScope().sessionId());
            try (var inner = InvocationContext.openScope("sess-inner", "turn-inner", "bob", null)) {
                assertEquals("sess-inner", InvocationContext.currentScope().sessionId());
                assertEquals("bob", InvocationContext.currentScope().actorId());
                assertEquals("t1", InvocationContext.currentScope().tenantId());
            }
            assertNotNull(InvocationContext.currentScope(), "内层 Scope 关闭后不得清空外层 Scope");
            assertEquals("sess-outer", InvocationContext.currentScope().sessionId());
            assertEquals("alice", InvocationContext.currentScope().actorId());
        }
        assertNull(InvocationContext.currentScope());

        // 2. 验证 revokeActor 会彻底清除此前授予的多资源写权限，重新授权单资源不会复活旧资源权限
        RevocableInvocationPolicy policy = new RevocableInvocationPolicy(true);
        policy.grantWrite("sre-alice", "order-service");
        policy.grantWrite("sre-alice", "payment-service");
        policy.revokeActor("sre-alice");
        policy.grantWrite("sre-alice", "order-service");

        CardRecord dummyProposal = CardRecord.builder().sessionId("s1").turnId("t1").cardType("OPS").build();
        assertDoesNotThrow(() -> policy.authorize("sre-alice", dummyProposal, Map.of("service", "order-service")));
        assertThrows(CardSecurityException.class,
                () -> policy.authorize("sre-alice", dummyProposal, Map.of("service", "payment-service")),
                "revokeActor 后未重新授权的 payment-service 严禁复活写权限");

        // 3. 验证 approve 锁定参数后，严禁再通过 submit 篡改参数；且 executeApproved 严禁匿名调用或绕过已撤销的原审批人
        policy.grantWrite("sre-bob", "order-service");
        UnifiedInvocationGateway gateway = new UnifiedInvocationGateway(policy);
        InMemoryCardRegistry registry = new InMemoryCardRegistry();
        registry.issue("sess-hard", buildOpsCard("act-hard-1", "turn-hard-1", false));

        OpsActionCardSubmitHandler prodHandler = new OpsActionCardSubmitHandler(new MockOpsActionAdapter() {
            @Override
            public boolean isSimulation() {
                return false;
            }
        });
        ActionGate gate = new ActionGate(registry, List.of(prodHandler), policy, false, true, null, gateway);

        gate.approve(buildSubmitRequest("act-hard-1", "sess-hard"), () -> "sre-alice");

        // (a) 已处于 APPROVED 状态，拒绝再通过 submit 覆盖参数
        CardSubmitRequest tamperedSubmit = buildSubmitRequest("act-hard-1", "sess-hard");
        tamperedSubmit.setFormValues(Map.of(
                "service", "order-service",
                "action_type", "KILL_SLOW_SESSION_AND_EXPAND_POOL",
                "target_identifier", "trx_9001",
                "max_pool_size", 450,
                "remark", "篡改参数"
        ));
        assertThrows(CardStateConflictException.class,
                () -> gate.submit(tamperedSubmit, () -> "sre-alice"));

        // (b) 真实写操作下，executeApproved 传入 null Principal 必须拒绝，不得静默冒用 proposal.getActorId()
        assertThrows(CardSecurityException.class,
                () -> gate.executeApproved("act-hard-1", "sess-hard", null));

        // (c) 原审批人 sre-alice 权限被撤销后，即便 sre-bob 持有权限也不能代为执行已失效的审批
        policy.revokeResource("sre-alice", "order-service");
        assertThrows(CardSecurityException.class,
                () -> gate.executeApproved("act-hard-1", "sess-hard", () -> "sre-bob"));

        // 4. 验证混合了 WRITE 方法的工具对象被 ToolDescriptor.fromBeanInstance 识别为 WRITE 并被拒绝挂载
        ToolDescriptor mixedDesc = ToolDescriptor.fromBeanInstance(new MixedReadClassWithWriteMethodTool());
        assertEquals(SideEffectLevel.WRITE, mixedDesc.sideEffectLevel());
    }
}
