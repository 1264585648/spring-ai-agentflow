package com.example.springai.card.spi;

import com.example.springai.card.dto.CardSubmitRequest;
import com.example.springai.card.dto.CardSubmitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitHubIssueCardSubmitHandlerTest {

    private final GitHubIssueCardSubmitHandler handler = new GitHubIssueCardSubmitHandler();

    @Test
    @DisplayName("测试 supports 支持 GITHUB_ISSUE_SUBMIT 与 GITHUB_ISSUE_CREATE")
    void testSupports() {
        assertTrue(handler.supports("GITHUB_ISSUE_SUBMIT"));
        assertTrue(handler.supports("GITHUB_ISSUE_CREATE"));
        assertTrue(handler.supports("github_issue_submit"));
        assertFalse(handler.supports("UNKNOWN_CARD"));
        assertFalse(handler.supports(null));
    }

    @Test
    @DisplayName("测试正常提交 GitHub Issue 成功生成 # 格式工单号")
    void testHandleSubmitSuccess() {
        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("act_test_123");
        request.setCardType("GITHUB_ISSUE_SUBMIT");
        request.setFormValues(Map.of(
                "repo", "spring-projects/spring-ai",
                "title", "[Bug]: Redis 连接池高并发下偶发泄漏问题",
                "labels", "bug, high-priority",
                "body", "压测 2000 QPS 持续 10 分钟后连接泄漏"
        ));

        CardSubmitResult result = handler.handleSubmit(request);

        assertNotNull(result);
        assertTrue(result.isSuccess(), "提交应成功");
        assertNotNull(result.getTicketId());
        assertTrue(result.getTicketId().startsWith("#"), "Issue 编号应以 # 开头");
        assertTrue(result.getMessage().contains("spring-projects/spring-ai"));
        assertTrue(result.getMessage().contains("成功创建"));
    }

    @Test
    @DisplayName("测试当标题为空时提交失败并拦截")
    void testHandleSubmitValidationFailure() {
        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("act_test_456");
        request.setCardType("GITHUB_ISSUE_SUBMIT");
        request.setFormValues(Map.of(
                "repo", "spring-projects/spring-ai",
                "title", "   "
        ));

        CardSubmitResult result = handler.handleSubmit(request);

        assertNotNull(result);
        assertFalse(result.isSuccess(), "空标题应提交失败");
        assertTrue(result.getMessage().contains("标题不能为空"));
    }
}
