package com.example.springai.pipeline.agent;

import com.example.springai.pipeline.agent.dto.DispatchPlan;
import com.example.springai.pipeline.agent.dto.DispatchStep;
import com.example.springai.pipeline.agent.dto.PlanType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterAgentRouterTest {

    private AgentPromptRegistry mockRegistry;
    private MasterAgentRouter router;

    @BeforeEach
    void setUp() {
        mockRegistry = mock(AgentPromptRegistry.class);
        router = new MasterAgentRouter(mockRegistry);

        // 默认模拟 3 个核心排障业务专家全部在线
        AgentDefinition logAgent = AgentDefinition.builder()
                .agentCode("LOG_DIAGNOSE_AGENT")
                .agentName("日志异常分析智能体")
                .status("ONLINE").isEnabled(1).layer("BUSINESS").build();

        AgentDefinition dbAgent = AgentDefinition.builder()
                .agentCode("DB_DIAGNOSE_AGENT")
                .agentName("数据库诊断智能体")
                .status("ONLINE").isEnabled(1).layer("BUSINESS").build();

        AgentDefinition sreAgent = AgentDefinition.builder()
                .agentCode("SRE_COPILOT_AGENT")
                .agentName("应急止血与运维协同智能体")
                .status("ONLINE").isEnabled(1).layer("BUSINESS").build();

        when(mockRegistry.getOnlineBusinessAgents())
                .thenReturn(List.of(logAgent, dbAgent, sreAgent));
    }

    @Test
    @DisplayName("测试单意图：服务报错堆栈排查命中 LOG_DIAGNOSE_AGENT 直通计划")
    void testSingleIntentLogDiagnoseRouting() {
        String query = "order-service 最近 10 分钟抛出大量 NullPointerException 异常日志，帮我排查一下堆栈";

        DispatchPlan plan = router.route(query);

        assertNotNull(plan);
        assertEquals(PlanType.SINGLE, plan.getPlanType());
        assertEquals(1, plan.getSteps().size());

        DispatchStep step = plan.getSteps().get(0);
        assertEquals(1, step.getStepOrder());
        assertEquals("LOG_DIAGNOSE_AGENT", step.getTargetAgent());
        assertEquals("order-service", step.getInputParams().get("service"));
        assertTrue(step.getTaskDesc().contains("日志"));
    }

    @Test
    @DisplayName("测试单意图：数据库慢SQL排查命中 DB_DIAGNOSE_AGENT 直通计划")
    void testSingleIntentDbDiagnoseRouting() {
        String query = "payment-service 数据库出现严重死锁与慢查询，连接池打满，排查一下 slow sql";

        DispatchPlan plan = router.route(query);

        assertNotNull(plan);
        assertEquals(PlanType.SINGLE, plan.getPlanType());
        assertEquals(1, plan.getSteps().size());
        assertEquals("DB_DIAGNOSE_AGENT", plan.getSteps().get(0).getTargetAgent());
    }

    @Test
    @DisplayName("测试纯单意图数据库诊断：命中 DB_DIAGNOSE_AGENT 直通计划")
    void testPureDbDiagnoseRouting() {
        String query = "帮我诊断当前 mysql 数据库活跃连接水位与长事务锁等待情况";

        DispatchPlan plan = router.route(query);

        assertNotNull(plan);
        assertEquals(PlanType.SINGLE, plan.getPlanType());
        assertEquals(1, plan.getSteps().size());

        DispatchStep step = plan.getSteps().get(0);
        assertEquals("DB_DIAGNOSE_AGENT", step.getTargetAgent());
    }

    @Test
    @DisplayName("测试复合多意图：504 网关超时与慢查询打满连接池联合诉求命中 COMPOSITE 编排计划")
    void testCompositeLogAndDbRouting() {
        String query = "order-service 线上出现 504 Gateway Timeout 报错，日志提示 Hikari 连接超时，请分析日志并排查数据库慢查";

        DispatchPlan plan = router.route(query);

        assertNotNull(plan);
        assertEquals(PlanType.COMPOSITE, plan.getPlanType());
        assertEquals(2, plan.getSteps().size(), "复合任务必须拆解为 2 步依次执行");

        // 验证 Step 1: 日志专家先执行
        DispatchStep step1 = plan.getSteps().get(0);
        assertEquals(1, step1.getStepOrder());
        assertEquals("LOG_DIAGNOSE_AGENT", step1.getTargetAgent());
        assertEquals("order-service", step1.getInputParams().get("service"));
        assertEquals("504", step1.getInputParams().get("statusCode"));

        // 验证 Step 2: 数据库专家后执行
        DispatchStep step2 = plan.getSteps().get(plan.getSteps().size() - 1);
        assertEquals(2, step2.getStepOrder());
        assertEquals("DB_DIAGNOSE_AGENT", step2.getTargetAgent());
        assertTrue(step2.getTaskDesc().contains("Step 1"));

        assertTrue(plan.getReason().contains("复合排障诉求"));
    }

    @Test
    @DisplayName("测试专家下线防线：若目标专家已下线，调度器强行拦截并降级为 FALLBACK")
    void testOfflineAgentIsSafelyBlocked() {
        // 模拟当前只有日志专家在线，数据库诊断专家已下线
        AgentDefinition logAgent = AgentDefinition.builder()
                .agentCode("LOG_DIAGNOSE_AGENT")
                .agentName("日志异常分析智能体")
                .status("ONLINE").isEnabled(1).layer("BUSINESS").build();
        when(mockRegistry.getOnlineBusinessAgents()).thenReturn(List.of(logAgent));

        String query = "帮我分析数据库当前慢查询与死锁指标";
        DispatchPlan plan = router.route(query);

        assertNotNull(plan);
        assertEquals(PlanType.FALLBACK, plan.getPlanType());
        assertTrue(plan.getReason().contains("处于下线状态"), "必须提示专家处于下线状态");
        assertTrue(plan.getSteps().isEmpty());
    }

    @Test
    @DisplayName("测试未知意图与空输入：优雅降级为 FALLBACK")
    void testFallbackOnUnmatchedOrEmptyQuery() {
        // 1. 空输入
        DispatchPlan emptyPlan = router.route("   ");
        assertEquals(PlanType.FALLBACK, emptyPlan.getPlanType());

        // 2. 无匹配业务意图的闲聊输入
        DispatchPlan chatPlan = router.route("今天天气真好，你叫什么名字？");
        assertEquals(PlanType.FALLBACK, chatPlan.getPlanType());
        assertTrue(chatPlan.getReason().contains("未命中已挂载的特定业务专家"));
    }
}
