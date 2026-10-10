package com.example.springai.infra.client;

import com.example.springai.infra.client.AgentChatClientFactory;
import com.example.springai.core.agent.AgentDefinition;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.capability.account.tool.UserAccountTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ChatClientConfigTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private AgentPromptRegistry promptRegistry;

    @Autowired
    private AgentChatClientFactory clientFactory;

    @Autowired
    @Qualifier("defaultAgentChatClient")
    private ChatClient defaultAgentChatClient;

    @Autowired
    private UserAccountTool userAccountTool;

    @Test
    @DisplayName("测试基础 ChatClient.Builder 与默认 Client 成功装配")
    void testBasicInfrastructureLoaded() {
        assertNotNull(chatClientBuilder, "ChatClient.Builder 应被 Spring AI 成功装配");
        assertNotNull(defaultAgentChatClient, "默认通用 defaultAgentChatClient 应被成功装配");
        assertNotNull(clientFactory, "AgentChatClientFactory 应被成功装配");
    }

    @Test
    @DisplayName("测试 AgentPromptRegistry 预置 6 大核心智能体人设与隔离 (支持 AgentType 枚举)")
    void testPromptRegistryPreloadedAgents() {
        assertNotNull(promptRegistry);
        List<AgentDefinition> allAgents = promptRegistry.getAllAgents();
        assertEquals(6, allAgents.size(), "初始应包含 6 大核心智能体");

        // 验证改写智能体 (通过枚举获取)
        Optional<AgentDefinition> rewriterOpt = promptRegistry.getAgent(com.example.springai.core.agent.AgentType.QUERY_REWRITER);
        assertTrue(rewriterOpt.isPresent());
        assertTrue(rewriterOpt.get().getSystemPrompt().contains("QueryRewritingAgent"));
        assertEquals(0.1, rewriterOpt.get().getTemperature());
        assertEquals("会话分析与查询重写智能体", rewriterOpt.get().getAgentName());

        // 验证 日志诊断 智能体 (通过枚举获取)
        Optional<AgentDefinition> logOpt = promptRegistry.getAgent(com.example.springai.core.agent.AgentType.LOG_DIAGNOSE_AGENT);
        assertTrue(logOpt.isPresent());
        assertTrue(logOpt.get().getSystemPrompt().contains("LogDiagnoseAgent"));
        assertEquals("日志异常分析智能体", logOpt.get().getAgentName());

        // 验证 数据库诊断 智能体
        Optional<AgentDefinition> dbOpt = promptRegistry.getAgent(com.example.springai.core.agent.AgentType.DB_DIAGNOSE_AGENT);
        assertTrue(dbOpt.isPresent());
        assertTrue(dbOpt.get().getSystemPrompt().contains("DbDiagnoseAgent"));

        // 验证枚举安全解析 fromCode
        assertEquals(com.example.springai.core.agent.AgentType.QUERY_REWRITER,
                com.example.springai.core.agent.AgentType.fromCode("query_rewriter"));
        assertEquals(com.example.springai.core.agent.AgentType.LOG_DIAGNOSE_AGENT,
                com.example.springai.core.agent.AgentType.fromCode("log_diagnose_agent"));
    }

    @Test
    @DisplayName("测试通过 AgentChatClientFactory 为不同智能体独立派生专用 ChatClient (支持枚举与字符串)")
    void testCreateDedicatedClientsForAgents() {
        // 1. 通过 AgentType 枚举为改写 Agent 派生专用 Client (无工具)
        ChatClient rewriterClient = clientFactory.createClient(com.example.springai.core.agent.AgentType.QUERY_REWRITER);
        assertNotNull(rewriterClient, "应能成功派生改写 Agent 的专用客户端");

        // 2. 通过 AgentType 枚举为 日志诊断 Agent 派生专用 Client 并绑定专属工具
        ChatClient logClient = clientFactory.createClient(
                com.example.springai.core.agent.AgentType.LOG_DIAGNOSE_AGENT,
                userAccountTool
        );
        assertNotNull(logClient, "应能成功派生带专属工具的客户端");

        // 3. 验证两个客户端是独立实例 (非同一引用)
        assertNotSame(rewriterClient, logClient, "不同智能体应具有独立的客户端实例与人设");
    }
}
