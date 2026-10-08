package com.example.springai.pipeline;

import com.example.springai.pipeline.event.L1RuleReloadEvent;
import com.example.springai.pipeline.intent.IntentMatchResult;
import com.example.springai.pipeline.intent.L1RuleMatcher;
import com.example.springai.pipeline.intent.L1RuleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class L1RuleRegistryTest {

    @Autowired
    private L1RuleRegistry ruleRegistry;

    @Autowired
    private L1RuleMatcher ruleMatcher;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("测试 MySQL 预热加载生效规则快照")
    void testRegistryStartupPreload() {
        assertNotNull(ruleRegistry);
        assertFalse(ruleRegistry.getActiveRules().isEmpty(), "启动时应成功从 MySQL 加载生效规则");
        System.out.println("【测试验证】当前内存预热规则数: " + ruleRegistry.getActiveRules().size());
    }

    @Test
    @DisplayName("测试命令式直出: /help 前缀匹配")
    void testMatchHelpCommand() {
        IntentMatchResult result = ruleMatcher.match("/help");
        assertTrue(result.isMatched(), "应命中 /help 指令");
        assertEquals("CMD_HELP", result.getRuleCode());
        assertEquals("STATIC_TEXT", result.getTargetType());
        assertNotNull(result.getDirectReply());
        assertTrue(result.getDirectReply().contains("可用快捷指令清单"));
        System.out.println("【测试验证】/help 直出内容: \n" + result.getDirectReply());
    }

    @Test
    @DisplayName("测试系统探活: #ping 完全匹配")
    void testMatchPingCommand() {
        IntentMatchResult result = ruleMatcher.match("#ping");
        assertTrue(result.isMatched(), "应命中 #ping 指令");
        assertEquals("CMD_SYS_PING", result.getRuleCode());
        assertEquals("STATIC_TEXT", result.getTargetType());
        assertTrue(result.getDirectReply().contains("PONG!"));
        System.out.println("【测试验证】#ping 耗时: " + result.getCostMs() + "ms, 答复: " + result.getDirectReply());
    }

    @Test
    @DisplayName("测试工具直通与正则变量提取: /query user_id=12345")
    void testMatchToolQueryWithParamExtraction() {
        IntentMatchResult result = ruleMatcher.match("/query user_id=12345");
        assertTrue(result.isMatched(), "应命中查账正则指令");
        assertEquals("CMD_QUERY_ACCOUNT", result.getRuleCode());
        assertEquals("TOOL", result.getTargetType());
        assertEquals("userAccountTool.queryBalance", result.getTargetRef());
        assertNotNull(result.getExtractedParams());
        assertTrue(result.getExtractedParams().contains("\"userId\": \"12345\""), "提取的入参应替换 $1 变量为 12345");
        System.out.println("【测试验证】Tool 目标: " + result.getTargetRef() + ", 解析参数: " + result.getExtractedParams());
    }

    @Test
    @DisplayName("测试未命中命令时放行至大模型")
    void testMissNaturalLanguage() {
        IntentMatchResult result = ruleMatcher.match("请问我的账单为什么有逾期罚息？");
        assertFalse(result.isMatched(), "自然语言咨询不应命中 L1 规则，应平滑放行至 L2/L3");
    }

    @Test
    @DisplayName("测试事件驱动零停机热重载")
    void testEventDrivenReload() {
        assertDoesNotThrow(() -> {
            eventPublisher.publishEvent(new L1RuleReloadEvent(this, "自动化单元测试触发刷新"));
        });
        assertFalse(ruleRegistry.getActiveRules().isEmpty());
    }
}
