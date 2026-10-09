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

        // 默认模拟 4 个核心业务专家全部在线
        AgentDefinition issueAgent = AgentDefinition.builder()
                .agentCode("GITHUB_ISSUE_AGENT")
                .agentName("Issue 治理与表单装配智能体")
                .status("ONLINE").isEnabled(1).layer("BUSINESS").build();

        AgentDefinition prAgent = AgentDefinition.builder()
                .agentCode("GITHUB_PR_AGENT")
                .agentName("PR 代码审查智能体")
                .status("ONLINE").isEnabled(1).layer("BUSINESS").build();

        AgentDefinition releaseAgent = AgentDefinition.builder()
                .agentCode("GITHUB_RELEASE_AGENT")
                .agentName("Release 版本发布智能体")
                .status("ONLINE").isEnabled(1).layer("BUSINESS").build();

        AgentDefinition workflowAgent = AgentDefinition.builder()
                .agentCode("GITHUB_WORKFLOW_AGENT")
                .agentName("CI/CD 流水线排障智能体")
                .status("ONLINE").isEnabled(1).layer("BUSINESS").build();

        when(mockRegistry.getOnlineBusinessAgents())
                .thenReturn(List.of(issueAgent, prAgent, releaseAgent, workflowAgent));
    }

    @Test
    @DisplayName("测试单意图：Issue 缺陷提报与咨询命中 SINGLE 直通计划")
    void testSingleIntentIssueRouting() {
        String query = "在 spring-projects/spring-ai 仓库下遇到高并发连接池泄漏，如何排查并提报 Issue 缺陷工单？";

        DispatchPlan plan = router.route(query);

        assertNotNull(plan);
        assertEquals(PlanType.SINGLE, plan.getPlanType());
        assertEquals(1, plan.getSteps().size());

        DispatchStep step = plan.getSteps().get(0);
        assertEquals(1, step.getStepOrder());
        assertEquals("GITHUB_ISSUE_AGENT", step.getTargetAgent());
        assertEquals("spring-projects/spring-ai", step.getInputParams().get("repo"));
        assertTrue(step.getTaskDesc().contains("Issue"));
    }

    @Test
    @DisplayName("测试单意图：PR 代码审查诉求命中 SINGLE 直通计划并提取 PR 编号")
    void testSingleIntentPrReviewRouting() {
        String query = "请审查 spring-projects/spring-ai PR #518 的代码 Diff 与合入安全风险";

        DispatchPlan plan = router.route(query);

        assertNotNull(plan);
        assertEquals(PlanType.SINGLE, plan.getPlanType());
        assertEquals(1, plan.getSteps().size());

        DispatchStep step = plan.getSteps().get(0);
        assertEquals("GITHUB_PR_AGENT", step.getTargetAgent());
        assertEquals("spring-projects/spring-ai", step.getInputParams().get("repo"));
        assertEquals(518, step.getInputParams().get("pr"));
    }

    @Test
    @DisplayName("测试复合多意图：PR 与 CI 流水线失败联合诉求命中 COMPOSITE 编排计划")
    void testCompositeCiAndPrRouting() {
        String query = "spring-projects/spring-ai 仓库中 PR #512 构建流水线 CI 测试失败挂了，请排查日志并比对代码审查";

        DispatchPlan plan = router.route(query);

        assertNotNull(plan);
        assertEquals(PlanType.COMPOSITE, plan.getPlanType());
        assertEquals(2, plan.getSteps().size(), "复合任务必须拆解为 2 步依次执行");

        // 验证 Step 1: CI 排障先执行
        DispatchStep step1 = plan.getSteps().get(0);
        assertEquals(1, step1.getStepOrder());
        assertEquals("GITHUB_WORKFLOW_AGENT", step1.getTargetAgent());
        assertEquals("spring-projects/spring-ai", step1.getInputParams().get("repo"));
        assertEquals(512, step1.getInputParams().get("pr"));

        // 验证 Step 2: PR Review 后执行并依赖 Step 1 日志
        DispatchStep step2 = plan.getSteps().get(plan.getSteps().size() - 1);
        assertEquals(2, step2.getStepOrder());
        assertEquals("GITHUB_PR_AGENT", step2.getTargetAgent());
        assertTrue(step2.getTaskDesc().contains("Step 1"));

        assertTrue(plan.getReason().contains("复合研发诉求"));
    }

    @Test
    @DisplayName("测试专家下线防线：若目标专家已下线，调度器强行拦截并降级为 FALLBACK")
    void testOfflineAgentIsSafelyBlocked() {
        // 模拟当前只有 IssueAgent 在线，ReleaseAgent 已下线
        AgentDefinition issueAgent = AgentDefinition.builder()
                .agentCode("GITHUB_ISSUE_AGENT")
                .agentName("Issue 治理智能体")
                .status("ONLINE").isEnabled(1).layer("BUSINESS").build();
        when(mockRegistry.getOnlineBusinessAgents()).thenReturn(List.of(issueAgent));

        String query = "帮我生成 spring-projects/spring-ai 仓库最新 Release 版本的 Changelog 并发版";
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
