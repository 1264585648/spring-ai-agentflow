package com.example.springai.pipeline.agent;

import com.example.springai.pipeline.entity.AgentDefinitionEntity;
import com.example.springai.pipeline.repository.AgentDefinitionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentPromptRegistryTest {

    @Test
    @DisplayName("测试当数据库为空或未配置时，默认预置 6 大核心智能体且冷启动可用")
    void testColdStartWithDefaultDefinitions() {
        AgentDefinitionRepository mockRepo = mock(AgentDefinitionRepository.class);
        when(mockRepo.findAll()).thenReturn(List.of());

        AgentPromptRegistry registry = new AgentPromptRegistry(mockRepo);
        registry.init();

        List<AgentDefinition> all = registry.getAllAgents();
        assertEquals(6, all.size(), "默认应包含 6 大核心智能体");

        Optional<AgentDefinition> masterOpt = registry.getAgent(AgentType.MASTER_AGENT);
        assertTrue(masterOpt.isPresent());
        assertEquals("PIPELINE_CORE", masterOpt.get().getAgentType());
        assertEquals("ORCHESTRATION", masterOpt.get().getLayer());
        assertEquals(1, masterOpt.get().getIsSystemCore());
    }

    @Test
    @DisplayName("测试从数据库加载记录并成功覆写 (Overlay) 对应智能体的人设与温度")
    void testDatabaseOverlay() {
        AgentDefinitionRepository mockRepo = mock(AgentDefinitionRepository.class);

        AgentDefinitionEntity dbEntity = AgentDefinitionEntity.builder()
                .id(1L)
                .agentCode("QUERY_REWRITER")
                .agentName("数据库最新人设-改写智能体")
                .agentType("PIPELINE_CORE")
                .layer("ANALYSIS")
                .systemPrompt("【数据库版本】你是一个专门针对电商大促会话的查询改写专家。")
                .dispatchDesc("大促专场重写")
                .temperature(0.05)
                .isSystemCore(1)
                .status("ONLINE")
                .isEnabled(1)
                .version(3)
                .build();

        when(mockRepo.findAll()).thenReturn(List.of(dbEntity));

        AgentPromptRegistry registry = new AgentPromptRegistry(mockRepo);
        registry.init();

        Optional<AgentDefinition> rewriterOpt = registry.getAgent("QUERY_REWRITER");
        assertTrue(rewriterOpt.isPresent());
        assertEquals("数据库最新人设-改写智能体", rewriterOpt.get().getAgentName());
        assertEquals(0.05, rewriterOpt.get().getTemperature());
        assertTrue(rewriterOpt.get().getSystemPrompt().contains("【数据库版本】"));

        // 未被数据库覆写的智能体，依然保留代码默认值
        Optional<AgentDefinition> logOpt = registry.getAgent(AgentType.LOG_DIAGNOSE_AGENT);
        assertTrue(logOpt.isPresent());
        assertEquals(0.10, logOpt.get().getTemperature());
    }

    @Test
    @DisplayName("测试 getOnlineBusinessAgents 仅返回业务层且处于 ONLINE 状态的子智能体")
    void testGetOnlineBusinessAgents() {
        AgentDefinitionRepository mockRepo = mock(AgentDefinitionRepository.class);
        when(mockRepo.findAll()).thenReturn(List.of());

        AgentPromptRegistry registry = new AgentPromptRegistry(mockRepo);
        registry.init();

        List<AgentDefinition> businessAgents = registry.getOnlineBusinessAgents();
        // 6 个默认中，QUERY_REWRITER (ANALYSIS) 与 MASTER_AGENT (ORCHESTRATION) 不属于 BUSINESS 层
        // 剩余 4 个是 BUSINESS 层：GENERAL_AGENT, LOG_DIAGNOSE_AGENT, DB_DIAGNOSE_AGENT, SRE_COPILOT_AGENT
        assertEquals(4, businessAgents.size());
        assertTrue(businessAgents.stream().allMatch(a -> "BUSINESS".equalsIgnoreCase(a.getLayer())));
        assertTrue(businessAgents.stream().allMatch(a -> "ONLINE".equalsIgnoreCase(a.getStatus())));
    }

    @Test
    @DisplayName("测试当智能体被下线 (OFFLINE) 后，getSystemPrompt 自动返回安全兜底提示词")
    void testOfflineAgentReturnsFallbackPrompt() {
        AgentDefinitionRepository mockRepo = mock(AgentDefinitionRepository.class);
        when(mockRepo.findAll()).thenReturn(List.of());

        AgentPromptRegistry registry = new AgentPromptRegistry(mockRepo);
        registry.init();

        AgentDefinitionEntity offlineEntity = AgentDefinitionEntity.builder()
                .agentCode("OFFLINE_BOT")
                .agentName("已下线智能体")
                .systemPrompt("机密提示词")
                .layer("BUSINESS")
                .status("OFFLINE")
                .isEnabled(0)
                .build();

        when(mockRepo.findAll()).thenReturn(List.of(offlineEntity));
        registry.reloadFromDatabase();

        String prompt = registry.getSystemPrompt("OFFLINE_BOT");
        assertFalse(prompt.contains("机密提示词"));
        assertTrue(prompt.contains("客观、严谨、条理清晰"));
    }

    @Test
    @DisplayName("测试 MasterAgent 动态组装业务子专家清单：新增与下线专家时 Prompt 实时增减")
    void testMasterAgentPromptDynamicAssembly() {
        AgentDefinitionRepository mockRepo = mock(AgentDefinitionRepository.class);
        when(mockRepo.findAll()).thenReturn(List.of());

        AgentPromptRegistry registry = new AgentPromptRegistry(mockRepo);
        registry.init();

        // 1. 验证默认初始化状态下的 MasterAgent 提示词
        String initialPrompt = registry.getSystemPrompt(AgentType.MASTER_AGENT);
        assertNotNull(initialPrompt);
        assertTrue(initialPrompt.contains("MasterAgent"), "应包含 MasterAgent 基底人设");
        assertTrue(initialPrompt.contains("【当前已挂载的可调度业务专家清单（动态热装载）】"));

        // 验证 4 个默认在线业务专家均被动态注入
        assertTrue(initialPrompt.contains("[GENERAL_AGENT]"));
        assertTrue(initialPrompt.contains("[LOG_DIAGNOSE_AGENT]"));
        assertTrue(initialPrompt.contains("[DB_DIAGNOSE_AGENT]"));
        assertTrue(initialPrompt.contains("[SRE_COPILOT_AGENT]"));

        // 验证非业务层的 QUERY_REWRITER 不会作为业务专家被注入
        assertFalse(initialPrompt.contains("[QUERY_REWRITER]"));

        // 2. 模拟动态新增一个业务专家 (如：依赖漏洞安全扫描专家)
        AgentDefinition securityExpert = AgentDefinition.builder()
                .agentCode("SECURITY_SCAN_AGENT")
                .agentName("依赖安全漏洞扫描专家")
                .layer("BUSINESS")
                .systemPrompt("扫描仓库 CVE 依赖安全")
                .dispatchDesc("负责检查 pom.xml 与 package.json 依赖中的已知安全漏洞与合规许可证风险")
                .status("ONLINE")
                .isEnabled(1)
                .build();
        registry.registerOrUpdate(securityExpert);

        // 再次获取 MasterAgent 提示词，验证已无锁感知并动态包含了新专家
        String updatedPrompt = registry.getSystemPrompt(AgentType.MASTER_AGENT);
        assertTrue(updatedPrompt.contains("[SECURITY_SCAN_AGENT]"), "MasterAgent 必须自动纳入新注册的在线专家");
        assertTrue(updatedPrompt.contains("负责检查 pom.xml 与 package.json 依赖中的已知安全漏洞"));

        // 3. 模拟动态下线一个专家 (将 SRE_COPILOT_AGENT 标记为 OFFLINE)
        AgentDefinition offlineSre = AgentDefinition.builder()
                .agentCode(AgentType.SRE_COPILOT_AGENT.getCode())
                .agentName(AgentType.SRE_COPILOT_AGENT.getName())
                .layer("BUSINESS")
                .systemPrompt("止血提示词")
                .dispatchDesc("止血描述")
                .status("OFFLINE")
                .isEnabled(0)
                .build();
        registry.registerOrUpdate(offlineSre);

        // 验证 MasterAgent 提示词实时剔除了已下线的专家
        String promptAfterOffline = registry.getSystemPrompt(AgentType.MASTER_AGENT);
        assertFalse(promptAfterOffline.contains("[SRE_COPILOT_AGENT]"), "已下线的专家必须立即从 MasterAgent 调度清单中剔除");
        // 但其余在线专家依然存在
        assertTrue(promptAfterOffline.contains("[LOG_DIAGNOSE_AGENT]"));
        assertTrue(promptAfterOffline.contains("[SECURITY_SCAN_AGENT]"));
    }
}
