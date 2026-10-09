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
        Optional<AgentDefinition> prOpt = registry.getAgent(AgentType.GITHUB_PR_AGENT);
        assertTrue(prOpt.isPresent());
        assertEquals(0.20, prOpt.get().getTemperature());
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
        // 剩余 4 个是 BUSINESS 层：GITHUB_ISSUE_AGENT, GITHUB_PR_AGENT, GITHUB_RELEASE_AGENT, GITHUB_WORKFLOW_AGENT
        assertEquals(4, businessAgents.size());
        assertTrue(businessAgents.stream().allMatch(a -> "BUSINESS".equalsIgnoreCase(a.getLayer())));
        assertTrue(businessAgents.stream().allMatch(a -> "ONLINE".equalsIgnoreCase(a.getStatus())));
    }

    @Test
    @DisplayName("测试当智能体被下线 (OFFLINE) 后，getSystemPrompt 自动返回安全兜底提示词")
    void testOfflineAgentReturnsFallbackPrompt() {
        AgentDefinitionRepository mockRepo = mock(AgentDefinitionRepository.class);

        AgentDefinitionEntity offlineEntity = AgentDefinitionEntity.builder()
                .agentCode("OFFLINE_BOT")
                .agentName("已下线智能体")
                .systemPrompt("机密提示词")
                .layer("BUSINESS")
                .status("OFFLINE")
                .isEnabled(0)
                .build();

        when(mockRepo.findAll()).thenReturn(List.of(offlineEntity));

        AgentPromptRegistry registry = new AgentPromptRegistry(mockRepo);
        registry.init();

        String prompt = registry.getSystemPrompt("OFFLINE_BOT");
        assertFalse(prompt.contains("机密提示词"));
        assertTrue(prompt.contains("客观、严谨、条理清晰"));
    }
}
