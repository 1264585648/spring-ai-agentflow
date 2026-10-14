package com.example.springai.core.routing;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.routing.dto.DispatchPlan;
import com.example.springai.core.routing.dto.PlanType;
import com.example.springai.core.routing.impl.L1RuleResolver;
import com.example.springai.core.routing.impl.L2VectorResolver;
import com.example.springai.core.routing.impl.L3MasterAgentResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class IntentResolversTest {

    private L1RuleMatcher l1RuleMatcher;
    private MasterAgentRouter masterAgentRouter;

    private L1RuleResolver l1RuleResolver;
    private L2VectorResolver l2VectorResolver;
    private L3MasterAgentResolver l3MasterAgentResolver;

    @BeforeEach
    void setUp() {
        l1RuleMatcher = mock(L1RuleMatcher.class);
        masterAgentRouter = mock(MasterAgentRouter.class);

        l1RuleResolver = new L1RuleResolver(l1RuleMatcher);
        l2VectorResolver = new L2VectorResolver(null, null);
        l3MasterAgentResolver = new L3MasterAgentResolver(masterAgentRouter);
    }

    @Test
    @DisplayName("测试 L1RuleResolver 命中静态文本并产出 DirectReply")
    void testL1ResolverStaticText() {
        when(l1RuleMatcher.match("#ping")).thenReturn(
                IntentMatchResult.hitL1("CMD_PING", "探活", "STATIC_TEXT", null, null, "🏓 PONG!", 1L)
        );

        AgentContext ctx = AgentContext.builder().query("#ping").effectiveQuery("#ping").build();
        Optional<Resolution> opt = l1RuleResolver.resolve(ctx);

        assertTrue(opt.isPresent());
        Resolution res = opt.get();
        assertInstanceOf(Resolution.DirectReply.class, res);

        Resolution.DirectReply direct = (Resolution.DirectReply) res;
        assertEquals("L1", direct.level());
        assertEquals("CMD_PING", direct.ruleCode());
        assertEquals("🏓 PONG!", direct.text());
    }

    @Test
    @DisplayName("测试 L1RuleResolver 命中命令并产出 ToolCall")
    void testL1ResolverToolCall() {
        when(l1RuleMatcher.match("/query user_id=101")).thenReturn(
                IntentMatchResult.hitL1("CMD_USER", "查用户", "TOOL", "userTool.query", "{\"userId\": \"101\"}", null, 1L)
        );

        AgentContext ctx = AgentContext.builder().query("/query user_id=101").effectiveQuery("/query user_id=101").build();
        Optional<Resolution> opt = l1RuleResolver.resolve(ctx);

        assertTrue(opt.isPresent());
        Resolution res = opt.get();
        assertInstanceOf(Resolution.ToolCall.class, res);

        Resolution.ToolCall tool = (Resolution.ToolCall) res;
        assertEquals("L1", tool.level());
        assertEquals("CMD_USER", tool.ruleCode());
        assertEquals("userTool.query", tool.toolRef());
        assertEquals("{\"userId\": \"101\"}", tool.jsonArgs());
    }

    @Test
    @DisplayName("测试 L1RuleResolver 未命中时放行")
    void testL1ResolverMiss() {
        when(l1RuleMatcher.match(anyString())).thenReturn(IntentMatchResult.miss());

        AgentContext ctx = AgentContext.builder().query("未知指令").effectiveQuery("未知指令").build();
        Optional<Resolution> opt = l1RuleResolver.resolve(ctx);

        assertTrue(opt.isEmpty());
    }

    @Test
    @DisplayName("测试 L2VectorResolver 预留插槽默认放行")
    void testL2ResolverStub() {
        assertEquals("L2", l2VectorResolver.level());
        assertEquals(200, l2VectorResolver.getOrder());

        AgentContext ctx = AgentContext.builder().query("任意问题").build();
        Optional<Resolution> opt = l2VectorResolver.resolve(ctx);

        assertTrue(opt.isEmpty(), "L2 目前处于插槽预留状态，必须返回 empty 放行至下一级");
    }

    @Test
    @DisplayName("测试 L3MasterAgentResolver 包装调度计划并产出 Route")
    void testL3ResolverProducesRoute() {
        DispatchPlan plan = DispatchPlan.single(
                "LOG_DIAGNOSE_AGENT",
                "日志专家",
                "排查日志",
                Map.of("service", "order-service")
        );
        when(masterAgentRouter.route(anyString())).thenReturn(plan);

        AgentContext ctx = AgentContext.builder().query("order-service 504 异常").build();
        Optional<Resolution> opt = l3MasterAgentResolver.resolve(ctx);

        assertTrue(opt.isPresent());
        Resolution res = opt.get();
        assertInstanceOf(Resolution.Route.class, res);

        Resolution.Route route = (Resolution.Route) res;
        assertEquals("L3", route.level());
        assertEquals(PlanType.SINGLE, route.planType());
        assertEquals(1, route.steps().size());
        assertEquals("LOG_DIAGNOSE_AGENT", route.steps().get(0).getTargetAgent());
    }
}
