package com.example.springai.core.routing;

import com.example.springai.core.event.L1RuleReloadEvent;
import com.example.springai.infra.persistence.entity.RuleDefinitionEntity;
import com.example.springai.infra.persistence.repository.RuleDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class L1RuleRegistryTest {

    private RuleDefinitionRepository ruleRepository;
    private L1RuleRegistry ruleRegistry;
    private L1RuleMatcher ruleMatcher;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(RuleDefinitionRepository.class);
        List<RuleDefinitionEntity> mockRules = List.of(
                createEntity("CMD_HELP", "帮助", "PREFIX", "/help", "STATIC_TEXT", "可用快捷指令清单:\n#ping\n/query", 1),
                createEntity("CMD_SYS_PING", "探活", "EXACT", "#ping", "STATIC_TEXT", "🏓 PONG!", 2),
                createEntity("CMD_QUERY_ACCOUNT", "查账", "REGEX", "^/query\\s+user_id=(\\d+)$", "TOOL", "userAccountTool.queryBalance", "{\"userId\": \"$1\"}", 3)
        );
        when(ruleRepository.findByIsEnabledOrderByPriorityAsc(1)).thenReturn(mockRules);

        ruleRegistry = new L1RuleRegistry(ruleRepository, Runnable::run);
        ruleRegistry.init();
        ruleMatcher = new L1RuleMatcher(ruleRegistry);
    }

    private RuleDefinitionEntity createEntity(String code, String name, String matchType, String pattern,
                                               String targetType, String targetRef, int priority) {
        return createEntity(code, name, matchType, pattern, targetType, targetRef, null, priority);
    }

    private RuleDefinitionEntity createEntity(String code, String name, String matchType, String pattern,
                                               String targetType, String targetRef, String paramTemplate, int priority) {
        RuleDefinitionEntity entity = new RuleDefinitionEntity();
        entity.setRuleCode(code);
        entity.setRuleName(name);
        entity.setMatchType(matchType);
        entity.setPatternExpr(pattern);
        entity.setTargetType(targetType);
        entity.setTargetRef(targetRef);
        entity.setParamTemplate(paramTemplate);
        entity.setPriority(priority);
        entity.setIsEnabled(1);
        entity.setDescription(name + "说明");
        return entity;
    }

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
            ruleRegistry.onRuleReload(new L1RuleReloadEvent(this, "自动化单元测试触发刷新"));
        });
        assertFalse(ruleRegistry.getActiveRules().isEmpty());
    }

    @Test
    @DisplayName("测试斜杠快捷指令清单读取: getCommandPalette()")
    void testCommandPaletteList() {
        var paletteList = ruleRegistry.getCommandPalette();
        assertNotNull(paletteList, "快捷指令清单不应为空");
        assertFalse(paletteList.isEmpty(), "应提取出已注册的快捷指令");
        assertTrue(paletteList.stream().anyMatch(item -> item.getPrefix().equals("#ping")), "应包含 #ping 探活指令");
        assertTrue(paletteList.stream().anyMatch(item -> item.getPrefix().equals("/help")), "应包含 /help 帮助指令");
        assertTrue(paletteList.stream().anyMatch(item -> item.getPrefix().equals("/query")), "应包含 /query 查账指令");

        paletteList.forEach(item -> {
            System.out.println("【Palette Item】" + item.getPrefix() + " (" + item.getName() + ") -> template: " + item.getTemplate());
        });
    }
}
