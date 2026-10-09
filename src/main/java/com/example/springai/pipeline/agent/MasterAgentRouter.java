package com.example.springai.pipeline.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.springai.pipeline.agent.dto.DispatchPlan;
import com.example.springai.pipeline.agent.dto.DispatchStep;
import com.example.springai.pipeline.agent.dto.PlanType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MasterAgent 调度决策器 (MasterAgentRouter)
 * 职责:
 * 1. 研判用户输入的意图特征与任务复杂度；
 * 2. 识别单意图直通 vs 复合多意图依赖编排；
 * 3. 联动 AgentPromptRegistry 校验目标专家在线状态（防悬挂调度）；
 * 4. 产出强类型的调度计划契约 (DispatchPlan)。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MasterAgentRouter {

    private static final Pattern REPO_PATTERN = Pattern.compile("([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+)");
    private static final Pattern PR_NUM_PATTERN = Pattern.compile("(?:#|pr\\s*|PR\\s*)(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISSUE_NUM_PATTERN = Pattern.compile("(?:issue\\s*|Issue\\s*)(\\d+)", Pattern.CASE_INSENSITIVE);

    private final AgentPromptRegistry promptRegistry;

    /**
     * 根据用户诉求输入规划调度决策计划
     *
     * @param query 开发者输入的提问/诉求
     * @return 结构化的调度计划 (DispatchPlan)
     */
    public DispatchPlan route(String query) {
        if (!StringUtils.hasText(query)) {
            return DispatchPlan.fallback("用户输入内容为空，无法生成调度计划");
        }

        String text = query.trim();
        Map<String, Object> contextParams = extractContextParams(text);

        log.debug("[MasterRouter] 正在分析调度意图, query: {}, 上下文: {}", text, contextParams);

        // 1. 复合多意图判定：同时涉及 CI/Actions 构建排障 与 PR 代码审查/合并
        if (isCiRelated(text) && isPrRelated(text)) {
            return planCiAndPrComposite(text, contextParams);
        }

        // 2. 单意图直通判定 - Issue 缺陷提报与治理
        if (isIssueRelated(text)) {
            return planSingleExpert(
                    AgentType.GITHUB_ISSUE_AGENT.getCode(),
                    AgentType.GITHUB_ISSUE_AGENT.getName(),
                    "检索关联历史 Issue 并装配标准化缺陷提单卡片",
                    contextParams
            );
        }

        // 3. 单意图直通判定 - PR 代码审查与 Diff 比对
        if (isPrRelated(text)) {
            return planSingleExpert(
                    AgentType.GITHUB_PR_AGENT.getCode(),
                    AgentType.GITHUB_PR_AGENT.getName(),
                    "拉取 PR Diff 代码变更列表，执行安全规范与合并风险审查",
                    contextParams
            );
        }

        // 4. 单意图直通判定 - Release 发版与 Changelog
        if (isReleaseRelated(text)) {
            return planSingleExpert(
                    AgentType.GITHUB_RELEASE_AGENT.getCode(),
                    AgentType.GITHUB_RELEASE_AGENT.getName(),
                    "抓取版本 Tag 提交记录，自动提炼 Markdown Changelog 并装配发版卡片",
                    contextParams
            );
        }

        // 5. 单意图直通判定 - GitHub Actions / CI 流水线排障
        if (isCiRelated(text)) {
            return planSingleExpert(
                    AgentType.GITHUB_WORKFLOW_AGENT.getCode(),
                    AgentType.GITHUB_WORKFLOW_AGENT.getName(),
                    "检索 GitHub Actions 工作流运行记录，定位单元测试与构建失败根因",
                    contextParams
            );
        }

        // 6. 无明确业务意图匹配：优雅降级为 FALLBACK
        log.info("[MasterRouter] 未匹配到特定的专业业务专家, 降级处理, query: {}", text);
        return DispatchPlan.fallback("当前诉求未命中已挂载的特定业务专家，建议使用通用对话或输入 /help 查看指令");
    }

    /**
     * 规划 CI 排障 + PR 审查复合协同计划
     */
    private DispatchPlan planCiAndPrComposite(String query, Map<String, Object> contextParams) {
        String workflowCode = AgentType.GITHUB_WORKFLOW_AGENT.getCode();
        String prCode = AgentType.GITHUB_PR_AGENT.getCode();

        // 校验两个专家是否均在线
        if (!isAgentOnline(workflowCode)) {
            return DispatchPlan.fallback("复合任务规划受阻：流水线排障专家 [" + workflowCode + "] 当前处于下线状态");
        }
        if (!isAgentOnline(prCode)) {
            return DispatchPlan.fallback("复合任务规划受阻：代码审查专家 [" + prCode + "] 当前处于下线状态");
        }

        List<DispatchStep> steps = new ArrayList<>();

        // Step 1: 先调 Actions 专家提取报错日志
        steps.add(DispatchStep.builder()
                .stepOrder(1)
                .targetAgent(workflowCode)
                .targetAgentName(AgentType.GITHUB_WORKFLOW_AGENT.getName())
                .taskDesc("提取 GitHub Actions 最近一次构建失败日志与异常测试堆栈")
                .inputParams(contextParams)
                .build());

        // Step 2: 再调 PR 专家结合日志比对 Diff
        steps.add(DispatchStep.builder()
                .stepOrder(2)
                .targetAgent(prCode)
                .targetAgentName(AgentType.GITHUB_PR_AGENT.getName())
                .taskDesc("结合 Step 1 提取的 CI 报错堆栈，审查 PR 代码 Diff 变更并评估修复方案")
                .inputParams(contextParams)
                .build());

        String reason = "检测到复合研发诉求（CI 构建失败 + PR 代码变更）：已编排两步依赖协同，先排查流水线报错，再比对代码审查。";
        log.info("[MasterRouter] ⚡ 成功生成复合多意图调度计划: 步骤数={}", steps.size());
        return DispatchPlan.composite(reason, steps);
    }

    /**
     * 规划单意图直通计划（带专家在线校验）
     */
    private DispatchPlan planSingleExpert(String agentCode, String agentName, String taskDesc, Map<String, Object> params) {
        if (!isAgentOnline(agentCode)) {
            log.warn("[MasterRouter] ⚠️ 目标业务专家 [{}] 当前未在线或已被禁用，阻断直通调度", agentCode);
            return DispatchPlan.fallback("目标业务专家 [" + agentName + " (" + agentCode + ")] 当前处于下线状态，无法响应此诉求");
        }

        log.info("[MasterRouter] ⚡ 生成单意图直通调度计划: target={}", agentCode);
        return DispatchPlan.single(agentCode, agentName, taskDesc, params);
    }

    /**
     * 校验业务专家当前是否在注册中心处于 ONLINE 且启用状态
     */
    public boolean isAgentOnline(String agentCode) {
        if (agentCode == null || promptRegistry == null) {
            return false;
        }
        return promptRegistry.getOnlineBusinessAgents().stream()
                .anyMatch(a -> a.getAgentCode().equalsIgnoreCase(agentCode.trim()));
    }

    /**
     * 从查询文本中提取仓库名与单号等上下文元数据
     */
    private Map<String, Object> extractContextParams(String query) {
        Map<String, Object> params = new LinkedHashMap<>();

        Matcher repoMatcher = REPO_PATTERN.matcher(query);
        if (repoMatcher.find()) {
            params.put("repo", repoMatcher.group(1));
        }

        Matcher prMatcher = PR_NUM_PATTERN.matcher(query);
        if (prMatcher.find()) {
            params.put("pr", Integer.parseInt(prMatcher.group(1)));
        }

        Matcher issueMatcher = ISSUE_NUM_PATTERN.matcher(query);
        if (issueMatcher.find()) {
            params.put("issue", Integer.parseInt(issueMatcher.group(1)));
        }

        return Collections.unmodifiableMap(params);
    }

    private boolean isIssueRelated(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("issue") || lower.contains("bug") || lower.contains("缺陷")
                || lower.contains("提单") || lower.contains("工单") || lower.contains("故障");
    }

    private boolean isPrRelated(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("pr") || lower.contains("pull request") || lower.contains("review")
                || lower.contains("审查") || lower.contains("diff") || lower.contains("合并");
    }

    private boolean isReleaseRelated(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("release") || lower.contains("changelog") || lower.contains("发版")
                || lower.contains("发布版本") || lower.contains("tag") || lower.contains("版本日志");
    }

    private boolean isCiRelated(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("ci") || lower.contains("actions") || lower.contains("流水线")
                || lower.contains("构建") || lower.contains("workflow") || lower.contains("测试失败");
    }
}
