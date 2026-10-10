package com.example.springai.infra.client;

import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.agent.AgentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 多智能体专用 ChatClient 工厂 (AgentChatClientFactory)
 * 职责:
 * 1. 结合基础 ChatClient.Builder 与 AgentPromptRegistry；
 * 2. 按需为每个专业 Agent 派生独立的、携带专属 System Prompt 与专属 Tools 的 ChatClient 实例；
 * 3. 避免全局 System Prompt 污染，实现真正的 Multi-Agent 职责边界隔离。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AgentChatClientFactory {

    private final ChatClient.Builder baseChatClientBuilder;
    private final AgentPromptRegistry promptRegistry;

    /**
     * 为指定智能体枚举类型创建专用 ChatClient 实例
     *
     * @param agentType 智能体枚举
     * @return 具备该智能体专属人设的 ChatClient
     */
    public ChatClient createClient(AgentType agentType) {
        return createClient(agentType != null ? agentType.getCode() : null);
    }

    /**
     * 为指定智能体枚举类型创建专用 ChatClient 实例并绑定专属 Tools
     *
     * @param agentType 智能体枚举
     * @param tools 该智能体专属挂载的 Spring @Tool Bean (可变参数)
     * @return 专属于该智能体的 ChatClient
     */
    public ChatClient createClient(AgentType agentType, Object... tools) {
        return createClient(agentType != null ? agentType.getCode() : null, tools);
    }

    /**
     * 为指定智能体创建专用的 ChatClient 实例 (无挂载工具)
     *
     * @param agentCode 智能体唯一编码 (如 QUERY_REWRITER, MASTER_AGENT 等)
     * @return 具备该智能体专属人设的 ChatClient
     */
    public ChatClient createClient(String agentCode) {
        return createClient(agentCode, new Object[0]);
    }

    /**
     * 为指定智能体创建专用的 ChatClient 实例并绑定专属 Tools
     *
     * @param agentCode 智能体唯一编码
     * @param tools 该智能体专属挂载的 Spring @Tool Bean (可变参数)
     * @return 专属于该智能体的 ChatClient
     */
    public ChatClient createClient(String agentCode, Object... tools) {
        String systemPrompt = promptRegistry.getSystemPrompt(agentCode);
        log.debug("[AgentChatClientFactory] 为智能体 [{}] 创建专用 ChatClient, 挂载工具数: {}",
                agentCode, tools != null ? tools.length : 0);

        ChatClient.Builder builder = baseChatClientBuilder.clone()
                .defaultSystem(systemPrompt);

        if (tools != null && tools.length > 0) {
            builder.defaultTools(tools);
        }

        return builder.build();
    }

    /**
     * 获取通用的纯净 ChatClient (使用通用兜底系统人设)
     */
    public ChatClient createDefaultClient() {
        return baseChatClientBuilder.clone()
                .defaultSystem("你是一个企业级智能协同助手，请保持客观、严谨、条理清晰的沟通风格。")
                .build();
    }
}
