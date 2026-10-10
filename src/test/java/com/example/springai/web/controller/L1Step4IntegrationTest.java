package com.example.springai.web.controller;

import com.example.springai.web.controller.L1RuleAdminController;
import com.example.springai.web.dto.RuleCreateRequest;
import com.example.springai.web.dto.RuleResponse;
import com.example.springai.core.tool.L1ToolDispatcher;
import com.example.springai.core.routing.IntentMatchResult;
import com.example.springai.core.routing.L1RuleMatcher;
import com.example.springai.core.routing.L1RuleRegistry;
import com.example.springai.infra.persistence.repository.RuleDefinitionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class L1Step4IntegrationTest {

    @Autowired
    private L1ToolDispatcher toolDispatcher;

    @Autowired
    private L1RuleAdminController adminController;

    @Autowired
    private L1RuleMatcher ruleMatcher;

    @Autowired
    private L1RuleRegistry ruleRegistry;

    @Autowired
    private RuleDefinitionRepository ruleRepository;

    @Test
    @DisplayName("测试 L1ToolDispatcher 反射执行 Spring @Tool 并自动解析参数")
    void testToolDispatcherExecution() {
        assertNotNull(toolDispatcher);

        // 1. 测试单参数方法调用
        String result = toolDispatcher.dispatch("userAccountTool.queryBalance", "{\"userId\": \"10001\"}");
        assertNotNull(result);
        assertTrue(result.contains("10001"), "应包含传入的用户编号");
        assertTrue(result.contains("50,000.00"), "应包含工具返回的授信额度");
        System.out.println("【测试验证】Tool 反射执行成功:\n" + result);

        // 2. 测试多参数方法调用
        String txResult = toolDispatcher.dispatch("userAccountTool.queryTransactions", "{\"userId\": \"10001\", \"type\": \"REFUND\"}");
        assertNotNull(txResult);
        assertTrue(txResult.contains("用户：10001"));
        assertTrue(txResult.contains("类型：REFUND"));
        System.out.println("【测试验证】多参数 Tool 执行成功:\n" + txResult);
    }

    @Test
    @DisplayName("测试管理后台 CRUD 与事件驱动零停机热重载闭环")
    void testAdminCrudAndHotReloadLoop() {
        String testRuleCode = "CMD_TEST_VIP_ORDER";

        ResponseEntity<List<RuleResponse>> listResp;
        try {
            listResp = adminController.listRules();
        } catch (Exception ex) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "远程开发数据库不可达，自动跳过集成测试: " + ex.getMessage());
            return;
        }
        try {
            // 1. 查询初始列表
            assertTrue(listResp.getStatusCode().is2xxSuccessful());
            assertNotNull(listResp.getBody());
            int initialCount = listResp.getBody().size();

            // 2. 动态新增一条规则: /vip <订单号>
            RuleCreateRequest req = new RuleCreateRequest();
            req.setRuleCode(testRuleCode);
            req.setRuleName("VIP特权订单极速直出");
            req.setMatchType("REGEX");
            req.setPatternExpr("^/vip\\s+(\\w+)$");
            req.setTargetType("STATIC_TEXT");
            req.setTargetRef("⭐ VIP专属订单已秒级锁定，享受绿色通道！");
            req.setPriority(5);
            req.setIsEnabled(1);

            ResponseEntity<?> createResp = adminController.createRule(req);
            assertTrue(createResp.getStatusCode().is2xxSuccessful(), "新增规则应返回 200");
            RuleResponse created = (RuleResponse) createResp.getBody();
            assertNotNull(created);
            assertNotNull(created.getId());

            // 3. 验证内存热重载：新增后无需重启，L1RuleMatcher 应该立刻命中新规则！
            IntentMatchResult matchResult = ruleMatcher.match("/vip ORD888");
            assertTrue(matchResult.isMatched(), "新增规则应通过 Spring 事件驱动即刻在内存生效");
            assertEquals(testRuleCode, matchResult.getRuleCode());
            assertTrue(matchResult.getDirectReply().contains("VIP专属订单"));
            System.out.println("【测试验证】动态热生效验证成功: " + matchResult.getDirectReply());

            // 4. 一键禁用规则
            ResponseEntity<?> toggleResp = adminController.toggleRuleStatus(created.getId());
            assertTrue(toggleResp.getStatusCode().is2xxSuccessful());

            // 5. 验证禁用后内存热生效：不再命中该规则
            IntentMatchResult afterDisable = ruleMatcher.match("/vip ORD888");
            assertFalse(afterDisable.isMatched(), "禁用规则后应即刻在内存失效");
            System.out.println("【测试验证】动态禁用验证成功，请求平滑放行至大模型");

        } finally {
            try {
                // 清理测试数据，保持远程库整洁
                ruleRepository.findByRuleCode(testRuleCode).ifPresent(e -> {
                    ruleRepository.delete(e);
                    ruleRegistry.reload();
                });
            } catch (Exception ignored) {
            }
        }
    }
}
