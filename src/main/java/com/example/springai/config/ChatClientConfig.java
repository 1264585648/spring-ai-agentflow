package com.example.springai.config;

import lombok.extern.slf4j.Slf4j;
import com.example.springai.pipeline.agent.AgentChatClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础设施级 ChatClient 配置类 (ChatClientConfig)
 * 职责:
 * 1. 负责全局底层通信切面配置 (如全局 SimpleLoggerAdvisor 审计日志)；
 * 2. 严禁配置全局一刀切的 System Prompt，保持基础 Builder 纯净；
 * 3. 各专业 Agent 通过 AgentChatClientFactory 派生各自专属的 ChatClient。
 */
@Configuration
@Slf4j
public class ChatClientConfig {


    /**
     * 全局 ChatClient.Builder 定制器：
     * 为全容器内所有 ChatClient.Builder 挂载统一的可观测日志拦截器 (SimpleLoggerAdvisor)
     */
    @Bean
    public ChatClientBuilderCustomizer globalChatClientBuilderCustomizer() {
        log.info("[ChatClientConfig] 注册全局 ChatClientBuilderCustomizer (已挂载 SimpleLoggerAdvisor)");
        return builder -> builder.defaultAdvisors(new SimpleLoggerAdvisor());
    }

    /**
     * 默认通用智能体客户端 Bean (仅供常规未指定 Agent 的通用场景注入)
     */
    @Bean("defaultAgentChatClient")
    public ChatClient defaultAgentChatClient(AgentChatClientFactory factory) {
        log.info("[ChatClientConfig] 初始化默认通用 Agent ChatClient Bean");
        return factory.createDefaultClient();
    }
}
