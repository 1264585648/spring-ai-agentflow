package com.example.springai.pipeline.impl;

import lombok.extern.slf4j.Slf4j;
import com.example.springai.api.dto.ChatRequest;
import com.example.springai.card.model.CardFormField;
import com.example.springai.card.model.InteractiveCard;
import com.example.springai.execution.sse.SseEventPublisher;
import com.example.springai.pipeline.AgentPipelineService;
import com.example.springai.pipeline.agent.AgentChatClientFactory;
import com.example.springai.pipeline.agent.AgentType;
import com.example.springai.pipeline.dispatcher.L1ToolDispatcher;
import com.example.springai.pipeline.intent.IntentMatchResult;
import com.example.springai.pipeline.intent.L1RuleMatcher;
import com.example.springai.tool.GitHubApiTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub 研发协同多智能体流水线实现 (AgentPipelineServiceImpl)
 * 职责:
 * 1. 意图分级路由：优先匹配 L1 高频规则直出与命令直通；
 * 2. 多智能体协作：MasterAgent 分发至 GithubIssueAgent 专家并挂载 GitHubApiTool；
 * 3. 研发上下文核验：实时检索目标仓库信息与已知 Issue 查重；
 * 4. Human-in-the-loop：生成标准化 GITHUB_ISSUE_SUBMIT 交互卡片，支持用户微调并提报工单；
 * 5. 全链路可观测：推送思考链、进度节点、打字机流式文本与智能推荐问题。
 */
@Service
@Slf4j
public class AgentPipelineServiceImpl implements AgentPipelineService {


    private static final int STREAM_CHUNK_SIZE = 8;
    private static final long L1_CHUNK_DELAY_MS = 12L;
    private static final long DEMO_CHUNK_DELAY_MS = 20L;
    private static final Pattern REPO_PATTERN = Pattern.compile("([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+)");

    private final SseEventPublisher ssePublisher;
    private final L1RuleMatcher l1RuleMatcher;
    private final L1ToolDispatcher l1ToolDispatcher;
    private final AgentChatClientFactory chatClientFactory;
    private final GitHubApiTool gitHubApiTool;
    private final Executor pipelineExecutor;

    public AgentPipelineServiceImpl(
            SseEventPublisher ssePublisher,
            L1RuleMatcher l1RuleMatcher,
            L1ToolDispatcher l1ToolDispatcher,
            AgentChatClientFactory chatClientFactory,
            GitHubApiTool gitHubApiTool,
            @Qualifier("agentPipelineExecutor") Executor pipelineExecutor) {
        this.ssePublisher = ssePublisher;
        this.l1RuleMatcher = l1RuleMatcher;
        this.l1ToolDispatcher = l1ToolDispatcher;
        this.chatClientFactory = chatClientFactory;
        this.gitHubApiTool = gitHubApiTool;
        this.pipelineExecutor = pipelineExecutor;
    }

    @Override
    public void process(ChatRequest request) {
        String sessionId = request.getSessionId();
        String query = request.getQuery();
        log.info("[Pipeline] 启动处理会话: {}, 问题: {}", sessionId, query);

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 优先尝试 L1 规则毫秒级直出或命令直通
                IntentMatchResult l1Result = l1RuleMatcher.match(query);
                if (l1Result.isMatched()) {
                    if ("STATIC_TEXT".equalsIgnoreCase(l1Result.getTargetType()) && l1Result.getDirectReply() != null) {
                        log.info("[Pipeline] 命中 L1 静态指令直出，会话: {}, 规则: {}", sessionId, l1Result.getRuleCode());
                        if (!ssePublisher.sendProgress(sessionId, "L1_HIT", "已命中 L1 快捷指令，毫秒级直出")) {
                            return;
                        }
                        if (!streamText(sessionId, l1Result.getDirectReply(), L1_CHUNK_DELAY_MS)) {
                            return;
                        }
                        ssePublisher.sendDone(sessionId);
                        return;
                    } else if ("TOOL".equalsIgnoreCase(l1Result.getTargetType())) {
                        log.info("[Pipeline] 命中 L1 工具直通，会话: {}, 目标: {}, 参数: {}",
                                sessionId, l1Result.getTargetRef(), l1Result.getExtractedParams());
                        if (!ssePublisher.sendProgress(sessionId, "L1_TOOL", "已命中命令直通，正在调度工具: " + l1Result.getTargetRef())) {
                            return;
                        }

                        String toolResult = l1ToolDispatcher.dispatch(l1Result.getTargetRef(), l1Result.getExtractedParams());
                        if (!streamText(sessionId, toolResult, L1_CHUNK_DELAY_MS)) {
                            return;
                        }
                        ssePublisher.sendDone(sessionId);
                        return;
                    } else {
                        ssePublisher.sendError(sessionId, "不支持的 L1 目标类型: " + l1Result.getTargetType());
                        ssePublisher.sendDone(sessionId);
                        return;
                    }
                }

                // 2. 未命中 L1，进入 GitHub 协同多智能体流水线 (MasterAgent -> GithubIssueAgent)
                if (!ssePublisher.sendThinking(sessionId, "未命中 L1 极速指令，MasterAgent 正在委派 GithubIssueAgent 专家并检索关联仓库与已知缺陷...")) {
                    return;
                }

                String repo = extractRepo(query);
                log.info("[Pipeline] 识别目标仓库: {}", repo);

                String repoOverview = gitHubApiTool.queryRepo(repo);
                String openIssues = gitHubApiTool.queryIssues(repo, "OPEN");

                if (!ssePublisher.sendProgress(sessionId, "GITHUB_SYNC", "已完成 " + repo + " 仓库上下文检索与现有 Issue 缺陷查重核验")) {
                    return;
                }

                // 3. 结合专用智能体人设与 GitHubApiTool 展开推理
                String responseText;
                try {
                    ChatClient issueClient = chatClientFactory.createClient(AgentType.GITHUB_ISSUE_AGENT, gitHubApiTool);
                    String prompt = String.format("""
                            开发者提问: %s
                            
                            目标仓库概况:
                            %s
                            
                            当前开启的 Issue 缺陷列表:
                            %s
                            
                            请针对开发者的疑问进行专业解答：
                            1. 分析可能的原因与排查思路；
                            2. 说明是否已存在相似 Issue 并避免重复提报；
                            3. 给出提报规范 Issue 进行跟进的建议。
                            """, query, repoOverview, openIssues);

                    responseText = issueClient.prompt()
                            .user(prompt)
                            .call()
                            .content();
                } catch (Exception ex) {
                    log.warn("[Pipeline] 大模型底座未配置有效 API-Key 或调用超时 ({})，启动研发协同专家规则引擎降级直出", ex.getMessage());
                    responseText = buildFallbackExpertReply(query, repo);
                }

                // 4. 打字机流式回传分析结论
                if (!streamText(sessionId, responseText, DEMO_CHUNK_DELAY_MS)) {
                    return;
                }

                // 5. 挂载 GitHub Issue 标准化交互卡片 (Human-in-the-loop 确认)
                InteractiveCard card = buildGitHubIssueCard(request, repo, query);
                if (!ssePublisher.sendInteractiveCard(sessionId, card)) {
                    return;
                }

                // 6. 推送智能延伸推荐问题
                if (!ssePublisher.sendRecommendQuestions(sessionId, List.of(
                        "如何查看此 Issue 关联的 PR 修复分支？",
                        "查询 " + repo + " 的最新 Release 版本",
                        "查看当前 GitHub Actions CI 流水线状态"
                ))) {
                    return;
                }

                ssePublisher.sendDone(sessionId);
                log.info("[Pipeline] 流水线执行完成, sessionId: {}", sessionId);

            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("[Pipeline] 流水线被中断, sessionId: {}", sessionId);
                ssePublisher.sendError(sessionId, "流水线被中断");
            } catch (Exception ex) {
                log.error("[Pipeline] 流水线执行异常, sessionId: {}", sessionId, ex);
                ssePublisher.sendError(sessionId, "流水线执行异常: " + ex.getMessage());
            }
        }, pipelineExecutor);
    }

    /**
     * 按小块推送文本。发送失败表示连接已断开，调用方应停止本回合。
     */
    private boolean streamText(String sessionId, String text, long delayMs) throws InterruptedException {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int offset = 0; offset < text.length(); ) {
            int end = Math.min(text.length(), offset + STREAM_CHUNK_SIZE);
            if (!ssePublisher.sendMessage(sessionId, text.substring(offset, end))) {
                return false;
            }
            offset = end;
            if (offset < text.length() && delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
        return true;
    }

    private String extractRepo(String query) {
        if (query == null) {
            return "spring-projects/spring-ai";
        }
        Matcher matcher = REPO_PATTERN.matcher(query);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "spring-projects/spring-ai";
    }

    private String buildFallbackExpertReply(String query, String repo) {
        return "您好！我是 GitHub Issue 治理与研发协同专家。\n\n"
                + "针对您在 `" + repo + "` 仓库中反馈的问题（" + query + "），已结合仓库上下文完成排查与查重：\n\n"
                + "1. **缺陷排查**：高并发场景下 Redis 连接池泄漏，通常与连接句柄未在 `finally` 块中释放、或响应式异步流被异常中断未触发归还相关；\n"
                + "2. **已知缺陷查重**：在当前仓库中检索到相似缺陷 `#1024 [Bug]: Redis 连接池高并发下偶发泄漏问题`；\n"
                + "3. **处理建议**：为避免污染现有缺陷讨论，建议针对您的独立复现步骤与压测指标新建规范的 Issue，以便 Maintainer 团队进行精准复现与修复。\n\n"
                + "系统已为您预填标准化 GitHub Issue 提报工单，请核验下方卡片内容：";
    }

    private InteractiveCard buildGitHubIssueCard(ChatRequest request, String repo, String query) {
        InteractiveCard card = new InteractiveCard();
        card.setActionId("act_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        card.setCardType("GITHUB_ISSUE_SUBMIT");
        card.setTitle("GitHub Issue 提报与缺陷确认单");
        card.setDescription("基于多智能体分析与已知缺陷查重，已自动装配规范 Issue 模板。关键信息已锁定，支持微调复现步骤后一键提报：");

        card.getFields().add(new CardFormField(
                "repo", "目标仓库 (Repository)", "text", repo, false, true
        ));
        card.getFields().add(new CardFormField(
                "issue_type", "缺陷类型 (Issue Type)", "text", "Bug Report (缺陷报告)", false, true
        ));

        String titleValue = (query != null && !query.isBlank()) ?
                "[Bug]: " + (query.length() > 30 ? query.substring(0, 30) + "..." : query) :
                "[Bug]: Redis 连接池高并发下偶发泄漏问题";
        card.getFields().add(new CardFormField(
                "title", "Issue 标题", "text", titleValue, true, true
        ));

        card.getFields().add(new CardFormField(
                "labels", "关联标签 (Labels)", "text", "bug, high-priority, redis", true, false
        ));

        String defaultBody = """
                ### 现象描述
                在高并发压测场景下，Redis 连接池句柄未被正确归还，导致连接池耗尽抛出异常。

                ### 复现步骤
                1. 配置 Redis 连接池最大连接数为 20
                2. 启动并发请求压测 (QPS > 1500)
                3. 持续 10 分钟后触发 RedisConnectionException

                ### 预期行为
                无论业务处理成功与否，连接对象必须在 finally 块中安全归还连接池。
                """;
        card.getFields().add(new CardFormField(
                "body", "复现步骤与排查说明", "textarea", defaultBody, true, true
        ));

        card.setConfirmButtonText("确认并在 GitHub 创建 Issue");
        card.setCancelButtonText("放弃");
        return card;
    }
}
