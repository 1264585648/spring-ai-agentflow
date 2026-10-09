package com.example.springai.card.spi;

import lombok.extern.slf4j.Slf4j;
import com.example.springai.card.dto.CardSubmitRequest;
import com.example.springai.card.dto.CardSubmitResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GitHub Issue 提报卡片提交 SPI 处理器 (GitHubIssueCardSubmitHandler)
 * 职责:
 * 1. 响应 GITHUB_ISSUE_SUBMIT 与 GITHUB_ISSUE_CREATE 卡片提交事件；
 * 2. 校验目标仓库与标题等必要字段；
 * 3. 模拟调用 GitHub REST API 完成工单提报，生成新 Issue 编号并指派维护团队；
 * 4. 优先级高于通用兜底处理器 (@Order(10))。
 */
@Component
@Order(10)
@Slf4j
public class GitHubIssueCardSubmitHandler implements CardSubmitHandler {


    public static final String CARD_TYPE_SUBMIT = "GITHUB_ISSUE_SUBMIT";
    public static final String CARD_TYPE_CREATE = "GITHUB_ISSUE_CREATE";

    @Override
    public boolean supports(String cardType) {
        return CARD_TYPE_SUBMIT.equalsIgnoreCase(cardType) || CARD_TYPE_CREATE.equalsIgnoreCase(cardType);
    }

    @Override
    public CardSubmitResult handleSubmit(CardSubmitRequest request) {
        Map<String, Object> formValues = request.getFormValues();
        log.info("[CardSPI:GitHub] 接收到 GitHub Issue 提交请求, actionId: {}, 表单数据: {}",
                request.getActionId(), formValues);

        String repo = "spring-projects/spring-ai";
        String title = null;

        if (formValues != null) {
            if (formValues.get("repo") != null && !String.valueOf(formValues.get("repo")).isBlank()) {
                repo = String.valueOf(formValues.get("repo")).trim();
            }
            if (formValues.get("title") != null) {
                title = String.valueOf(formValues.get("title")).trim();
            }
        }

        if (title == null || title.isBlank()) {
            return CardSubmitResult.fail("Issue 标题不能为空");
        }

        // 模拟生成规范 GitHub Issue 编号 (例如 #1035)
        long randomNum = 1030 + (long) (Math.random() * 8000);
        String issueRef = "#" + randomNum;

        String successMessage = String.format("GitHub Issue %s 已在仓库 %s 成功创建，并已自动打上标签与指派研发维护团队！",
                issueRef, repo);
        log.info("[CardSPI:GitHub] Issue 创建成功: 单号: {}, 仓库: {}, 标题: {}", issueRef, repo, title);

        return CardSubmitResult.ok(issueRef, successMessage);
    }
}
