package com.example.springai.pipeline.impl;

import com.example.springai.api.dto.ChatRequest;
import com.example.springai.card.model.InteractiveCard;
import com.example.springai.execution.sse.SseEventPublisher;
import com.example.springai.pipeline.agent.AgentChatClientFactory;
import com.example.springai.pipeline.dispatcher.L1ToolDispatcher;
import com.example.springai.pipeline.intent.IntentMatchResult;
import com.example.springai.pipeline.intent.L1RuleMatcher;
import com.example.springai.tool.GitHubApiTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentPipelineServiceImplTest {

    private SseEventPublisher ssePublisher;
    private L1RuleMatcher l1RuleMatcher;
    private L1ToolDispatcher l1ToolDispatcher;
    private AgentChatClientFactory chatClientFactory;
    private GitHubApiTool gitHubApiTool;
    private Executor directExecutor;

    private AgentPipelineServiceImpl pipelineService;

    @BeforeEach
    void setUp() {
        ssePublisher = mock(SseEventPublisher.class);
        when(ssePublisher.sendThinking(anyString(), anyString())).thenReturn(true);
        when(ssePublisher.sendProgress(anyString(), anyString(), anyString())).thenReturn(true);
        when(ssePublisher.sendMessage(anyString(), anyString())).thenReturn(true);
        when(ssePublisher.sendInteractiveCard(anyString(), any())).thenReturn(true);
        when(ssePublisher.sendRecommendQuestions(anyString(), anyList())).thenReturn(true);
        when(ssePublisher.sendDone(anyString())).thenReturn(true);

        l1RuleMatcher = mock(L1RuleMatcher.class);
        l1ToolDispatcher = mock(L1ToolDispatcher.class);
        chatClientFactory = mock(AgentChatClientFactory.class);
        gitHubApiTool = new GitHubApiTool();
        // 使用同步直接执行器以确保单测可预测完成
        directExecutor = Runnable::run;

        pipelineService = new AgentPipelineServiceImpl(
                ssePublisher,
                l1RuleMatcher,
                l1ToolDispatcher,
                chatClientFactory,
                gitHubApiTool,
                directExecutor
        );
    }

    @Test
    @DisplayName("测试命中 L1 静态指令直出快捷响应")
    void testL1StaticTextHit() {
        when(l1RuleMatcher.match("#ping")).thenReturn(
                IntentMatchResult.hitL1("CMD_SYS_PING", "探活", "STATIC_TEXT", null, null, "🏓 PONG!", 1L)
        );

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess_101");
        request.setQuery("#ping");
        pipelineService.process(request);

        verify(ssePublisher).sendProgress(eq("sess_101"), eq("L1_HIT"), anyString());
        verify(ssePublisher, atLeastOnce()).sendMessage(eq("sess_101"), anyString());
        verify(ssePublisher).sendDone("sess_101");
        verify(chatClientFactory, never()).createClient(anyString());
    }

    @Test
    @DisplayName("测试命中 L1 命令直通工具调度")
    void testL1ToolDispatchHit() {
        when(l1RuleMatcher.match("/repo spring-projects/spring-ai")).thenReturn(
                IntentMatchResult.hitL1("CMD_QUERY_REPO", "仓库速查", "TOOL", "githubApiTool.queryRepo", "{\"repo\": \"spring-projects/spring-ai\"}", null, 2L)
        );
        when(l1ToolDispatcher.dispatch(eq("githubApiTool.queryRepo"), anyString()))
                .thenReturn("📦 仓库详情: Stars: 4820");

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess_102");
        request.setQuery("/repo spring-projects/spring-ai");
        pipelineService.process(request);

        verify(ssePublisher).sendProgress(eq("sess_102"), eq("L1_TOOL"), contains("githubApiTool.queryRepo"));
        verify(l1ToolDispatcher).dispatch(eq("githubApiTool.queryRepo"), anyString());
        verify(ssePublisher, atLeastOnce()).sendMessage(eq("sess_102"), anyString());
        verify(ssePublisher).sendDone("sess_102");
    }

    @Test
    @DisplayName("测试未命中 L1 时进入 GitHub 协同流水线并下发 GITHUB_ISSUE_SUBMIT 卡片")
    void testNonL1EntersGitHubIssuePipelineAndEmitsCard() {
        when(l1RuleMatcher.match(anyString())).thenReturn(IntentMatchResult.miss());

        String query = "我们在 spring-projects/spring-ai 仓库发现 Redis 连接池高并发泄漏，请协助建一个 Issue";
        ChatRequest request = new ChatRequest();
        request.setSessionId("sess_103");
        request.setQuery(query);

        pipelineService.process(request);

        // 1. 验证下发思考链
        verify(ssePublisher).sendThinking(eq("sess_103"), contains("MasterAgent 正在委派 GithubIssueAgent"));

        // 2. 验证仓库同步进度
        verify(ssePublisher).sendProgress(eq("sess_103"), eq("GITHUB_SYNC"), contains("spring-projects/spring-ai"));

        // 3. 验证文本流式输出
        verify(ssePublisher, atLeastOnce()).sendMessage(eq("sess_103"), anyString());

        // 4. 验证交互卡片挂载
        ArgumentCaptor<InteractiveCard> cardCaptor = ArgumentCaptor.forClass(InteractiveCard.class);
        verify(ssePublisher).sendInteractiveCard(eq("sess_103"), cardCaptor.capture());
        InteractiveCard card = cardCaptor.getValue();
        assertNotNull(card);
        assertEquals("GITHUB_ISSUE_SUBMIT", card.getCardType());
        assertTrue(card.getTitle().contains("GitHub Issue"));
        assertTrue(card.getFields().stream().anyMatch(f -> "repo".equals(f.getFieldKey()) && "spring-projects/spring-ai".equals(f.getValue())));
        assertTrue(card.getFields().stream().anyMatch(f -> "issue_type".equals(f.getFieldKey())));
        assertTrue(card.getFields().stream().anyMatch(f -> "body".equals(f.getFieldKey())));

        // 5. 验证推荐问题
        ArgumentCaptor<List<String>> questionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ssePublisher).sendRecommendQuestions(eq("sess_103"), questionsCaptor.capture());
        List<String> questions = questionsCaptor.getValue();
        assertNotNull(questions);
        assertTrue(questions.stream().anyMatch(q -> q.contains("PR")));
        assertTrue(questions.stream().anyMatch(q -> q.contains("Release")));

        // 6. 验证结束包
        verify(ssePublisher).sendDone("sess_103");
    }
}
