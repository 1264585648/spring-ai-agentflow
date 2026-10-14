package com.example.springai.core.agent;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.context.PromptContextAssembler;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.infra.client.AgentChatClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用对话兜底智能体 (GeneralChatAgent)
 * 职责:
 * 1. 作为 SubAgent 的微内核默认实现；
 * 2. 当未匹配到专业垂直领域智能体时，结合多轮历史、有效提问与 L2 知识参考提供通用解答；
 * 3. 支持流式超时边界与客户端断连快速取消。
 */
@Component("generalChatAgent")
@Slf4j
@RequiredArgsConstructor
public class GeneralChatAgent implements SubAgent {

    private static final int STREAM_CHUNK_SIZE = 8;
    private static final long CHUNK_DELAY_MS = 20L;
    private static final Duration DEFAULT_MODEL_STREAM_TIMEOUT = Duration.ofSeconds(30);

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
        if (context == null || context.isCancelledOrExpired(System.currentTimeMillis())) {
            return;
        }
        String query = context.getQuery();
        String assembledPrompt = PromptContextAssembler.assembleGeneralUserPrompt(context);
        log.info("[GeneralChatAgent] 接收到通用问答任务, sessionId: {}, query: {}", context.getSessionId(), query);

        if (!context.getSink().progress("AGENT_DISPATCH", "已委派通用协同专家提供解答...")) {
            context.cancel();
            return;
        }

        boolean clientActive = true;
        try {
            ChatClient client = chatClientFactory.createClient(name());
            if (client == null) {
                throw new IllegalStateException("ChatClient 未能正确初始化");
            }

            Flux<String> streamFlux = client.prompt()
                    .user(assembledPrompt)
                    .stream()
                    .content();

            if (streamFlux == null) {
                throw new IllegalStateException("大模型底座未返回有效流式响应");
            }

            Duration streamTimeout = resolveStreamTimeout(context);
            AtomicBoolean aborted = new AtomicBoolean(false);
            streamFlux
                    .timeout(streamTimeout)
                    .takeWhile(token -> !aborted.get() && !context.isCancelledOrExpired(System.currentTimeMillis()))
                    .doOnNext(token -> {
                        if (token != null && !token.isEmpty()) {
                            boolean ok = context.getSink().message(token);
                            if (!ok) {
                                aborted.set(true);
                                context.cancel();
                                log.info("[GeneralChatAgent] 客户端断开连接，快速熔断流式生成");
                            }
                        }
                    })
                    .blockLast();

            if (aborted.get() || context.isCancelled()) {
                clientActive = false;
            }
        } catch (Exception ex) {
            log.warn("[GeneralChatAgent] 大模型底座调用异常 ({})，启动通用兜底回复", ex.getMessage());
            String displayQuery = (context.getEffectiveQuery() != null && !context.getEffectiveQuery().isBlank())
                    ? context.getEffectiveQuery()
                    : query;
            String replyText = "您好！我是企业级通用协同助手。您的问题已收到（`" + displayQuery + "`），当前大模型底座暂未连接或超时，您可以稍后再试或通过具体快捷指令与我互动。";
            clientActive = streamText(context, replyText);
        }

        if (!clientActive) {
            context.cancel();
            log.info("[GeneralChatAgent] 客户端已断开或取消，流式推送终止");
        }
    }

    private Duration resolveStreamTimeout(AgentContext context) {
        if (context != null && context.getAttribute("MODEL_STREAM_TIMEOUT_MS") instanceof Number num) {
            long ms = num.longValue();
            if (ms > 0) {
                return Duration.ofMillis(ms);
            }
        }
        return DEFAULT_MODEL_STREAM_TIMEOUT;
    }

    private boolean streamText(AgentContext context, String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int offset = 0; offset < text.length(); ) {
            int end = Math.min(text.length(), offset + STREAM_CHUNK_SIZE);
            if (!context.getSink().message(text.substring(offset, end))) {
                return false;
            }
            offset = end;
            if (offset < text.length() && CHUNK_DELAY_MS > 0) {
                try {
                    Thread.sleep(CHUNK_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }
}
