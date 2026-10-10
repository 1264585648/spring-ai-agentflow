package com.example.springai.core.agent;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.infra.client.AgentChatClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 通用对话兜底智能体 (GeneralChatAgent)
 * 职责:
 * 1. 作为 SubAgent 的微内核默认实现；
 * 2. 当未匹配到专业垂直领域智能体时，提供通用解答；
 * 3. 避免流水线在未匹配场景下中断。
 */
@Component("generalChatAgent")
@Slf4j
@RequiredArgsConstructor
public class GeneralChatAgent implements SubAgent {

    private static final int STREAM_CHUNK_SIZE = 8;
    private static final long CHUNK_DELAY_MS = 20L;

    private final AgentChatClientFactory chatClientFactory;

    @Override
    public String name() {
        return AgentType.GENERAL_AGENT.getCode();
    }

    @Override
    public boolean supports(String agentCode) {
        return AgentType.GENERAL_AGENT.getCode().equalsIgnoreCase(agentCode)
                || "DEFAULT_AGENT".equalsIgnoreCase(agentCode)
                || agentCode == null;
    }

    @Override
    public void execute(AgentContext context, DispatchStep step) {
        String query = context.getQuery();
        log.info("[GeneralChatAgent] 接收到通用问答任务, sessionId: {}, query: {}", context.getSessionId(), query);

        context.getSink().progress("AGENT_DISPATCH", "已委派通用协同专家提供解答...");

        String replyText;
        try {
            ChatClient client = chatClientFactory.createClient(name());
            replyText = client.prompt()
                    .user(query)
                    .call()
                    .content();
        } catch (Exception ex) {
            log.warn("[GeneralChatAgent] 大模型底座调用异常 ({})，启动通用兜底回复", ex.getMessage());
            replyText = "您好！我是企业级通用协同助手。您的问题已收到（`" + query + "`），当前大模型底座暂未连接或超时，您可以稍后再试或通过具体快捷指令与我互动。";
        }

        streamText(context, replyText);
    }

    private void streamText(AgentContext context, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int offset = 0; offset < text.length(); ) {
            int end = Math.min(text.length(), offset + STREAM_CHUNK_SIZE);
            if (!context.getSink().message(text.substring(offset, end))) {
                return;
            }
            offset = end;
            if (offset < text.length() && CHUNK_DELAY_MS > 0) {
                try {
                    Thread.sleep(CHUNK_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
