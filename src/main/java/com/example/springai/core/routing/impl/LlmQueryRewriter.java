package com.example.springai.core.routing.impl;

import com.example.springai.core.agent.AgentType;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.memory.ConversationMessage;
import com.example.springai.core.routing.QueryRewriter;
import com.example.springai.infra.client.AgentChatClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于大模型与离线自适应的查询重写器实现 (LlmQueryRewriter)
 * 职责:
 * 1. 结合历史上下文对当前用户问题进行指代消除与实体补齐 (如“它” ➔ “order-service”);
 * 2. 绕行机制: 命中 L1 极速指令 (如以 / 或 # 开头) 直接放行，绝不增加 LLM 调用时延;
 * 3. 优雅降级: 当大模型不可用、超时或处于离线单测环境时，平滑降级返回原 Query 或基于启发式上下文实体替换。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LlmQueryRewriter implements QueryRewriter {

    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("([a-zA-Z0-9_-]+(?:service|app|gateway|api|db))", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRONOUN_PATTERN = Pattern.compile("(它|它们|这个服务|该服务|刚才那个|前面那个|这个)");

    private final AgentChatClientFactory chatClientFactory;

    @Override
    public String rewrite(AgentContext context, List<ConversationMessage> history) {
        if (context == null) {
            return "";
        }

        String rawQuery = context.getQuery();
        if (!StringUtils.hasText(rawQuery)) {
            return "";
        }

        String trimmed = rawQuery.trim();

        // 1. 极速绕行：若以命令前缀 / 或 # 开头，属于 L1 极速指令，绝对不进入 LLM 改写链路
        if (trimmed.startsWith("/") || trimmed.startsWith("#")) {
            log.debug("[QueryRewriter] 检测到 L1 极速指令前缀，跳过改写: {}", trimmed);
            return rawQuery;
        }

        // 2. 若无历史记录，无需进行指代消除
        if (history == null || history.isEmpty()) {
            return rawQuery;
        }

        // 3. 尝试调用专用的 QUERY_REWRITER ChatClient 进行语义补齐
        long start = System.currentTimeMillis();
        try {
            ChatClient chatClient = chatClientFactory.createClient(AgentType.QUERY_REWRITER);
            String promptText = buildRewritePrompt(history, trimmed);

            String response = chatClient.prompt()
                    .user(promptText)
                    .call()
                    .content();

            String cleaned = cleanRewrittenText(response, trimmed);
            if (StringUtils.hasText(cleaned) && !cleaned.equalsIgnoreCase(trimmed)) {
                log.info("[QueryRewriter] ✨ LLM 指代改写成功 ({}ms): '{}' ➔ '{}'",
                        System.currentTimeMillis() - start, trimmed, cleaned);
                return cleaned;
            }
        } catch (Exception ex) {
            log.debug("[QueryRewriter] LLM 改写未命中或发生异常 ({})，进入离线启发式降级", ex.getMessage());
            // 离线/单测自适应启发式降级
            String heuristicResult = tryHeuristicRewrite(history, trimmed);
            if (StringUtils.hasText(heuristicResult) && !heuristicResult.equalsIgnoreCase(trimmed)) {
                log.info("[QueryRewriter] 💡 离线启发式指代补齐成功: '{}' ➔ '{}'", trimmed, heuristicResult);
                return heuristicResult;
            }
        }

        return rawQuery;
    }

    /**
     * 构建改写 Prompt
     */
    private String buildRewritePrompt(List<ConversationMessage> history, String currentQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append("【多轮会话历史】:\n");

        int startIndex = Math.max(0, history.size() - 6);
        for (int i = startIndex; i < history.size(); i++) {
            ConversationMessage msg = history.get(i);
            String roleName = msg.getRole() == ConversationMessage.Role.USER ? "用户" : "助手";
            sb.append(roleName).append(": ").append(msg.getContent()).append("\n");
        }

        sb.append("\n【用户当前提问】:\n").append(currentQuery).append("\n\n");
        sb.append("【要求】:\n");
        sb.append("1. 结合历史消除当前提问中的模糊指代(如'它'、'这个'、'刚才的慢查询')，补齐缺失的关键服务名、单号等实体;\n");
        sb.append("2. 仅输出改写后的完整提问语句，禁止添加任何解释、前缀、说明或引号;\n");
        sb.append("3. 若当前提问语义完整无需改写，直接原样输出当前提问。");

        return sb.toString();
    }

    /**
     * 规范化并清洗 LLM 的改写输出
     */
    private String cleanRewrittenText(String text, String fallback) {
        if (!StringUtils.hasText(text)) {
            return fallback;
        }

        String cleaned = text.trim();
        // 去除常见的模型开场白包装
        cleaned = cleaned.replaceFirst("^(改写后[:：]|改写结果[:：]|重写后[:：]|输出[:：])\\s*", "");

        // 去除前后引号
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.startsWith("“") && cleaned.endsWith("”") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }

        if ("无需改写".equals(cleaned) || "无需修改".equals(cleaned) || cleaned.isEmpty()) {
            return fallback;
        }

        return cleaned;
    }

    /**
     * 离线环境与单元测试的自适应启发式指代替换
     */
    private String tryHeuristicRewrite(List<ConversationMessage> history, String currentQuery) {
        Matcher matcher = PRONOUN_PATTERN.matcher(currentQuery);
        if (!matcher.find()) {
            return currentQuery;
        }

        // 从最近历史倒序寻找明确的服务名 (如 order-service)
        String foundService = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            String content = history.get(i).getContent();
            if (content != null) {
                Matcher svcMatcher = SERVICE_NAME_PATTERN.matcher(content);
                if (svcMatcher.find()) {
                    foundService = svcMatcher.group(1);
                    break;
                }
            }
        }

        if (foundService != null) {
            return matcher.replaceFirst(foundService);
        }

        return currentQuery;
    }
}
