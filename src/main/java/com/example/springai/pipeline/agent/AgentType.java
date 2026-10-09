package com.example.springai.pipeline.agent;

import lombok.Getter;

/**
 * 企业多智能体矩阵角色枚举定义 (AgentType)
 * 严格对应 04-企业多智能体职责矩阵与分层设计 (基于 GitHub API 研发协同与开源运维体系)
 * 统一收敛智能体编码、友好名称、默认推荐采样温度与职责描述
 */
@Getter
public enum AgentType {

    /**
     * 会话分析与查询重写智能体 (前置轻量 AgentBase - 管道核心)
     */
    QUERY_REWRITER(
            "QUERY_REWRITER",
            "会话分析与查询重写智能体",
            0.1,
            "负责多轮研发协同会话指代消除、补齐 owner/repo 仓库名与 Issue/PR 编号，输出规范查询"
    ),

    /**
     * GitHub 协同主协调调度智能体 (协调层 ReActAgent - 管道核心)
     */
    MASTER_AGENT(
            "MASTER_AGENT",
            "GitHub 协同主协调调度智能体",
            0.2,
            "负责 GitHub 研发任务依赖拆解、子专家协同调度与多源分析结果聚合"
    ),

    /**
     * GitHub Issue 治理与表单装配智能体 (业务层 ReActAgent)
     */
    GITHUB_ISSUE_AGENT(
            "GITHUB_ISSUE_AGENT",
            "Issue 治理与表单装配智能体",
            0.2,
            "负责 GitHub Issue 检索关联、Bug 分类标签判定、重复问题排查与提单卡片装配"
    ),

    /**
     * GitHub Pull Request 代码审查智能体 (业务层 ReActAgent)
     */
    GITHUB_PR_AGENT(
            "GITHUB_PR_AGENT",
            "Pull Request 代码审查智能体",
            0.2,
            "负责 GitHub Pull Request 代码差异比对、安全与规范审查、合并冲突与风险评估"
    ),

    /**
     * GitHub Release 版本发布与 Changelog 智能体 (业务层 ReActAgent)
     */
    GITHUB_RELEASE_AGENT(
            "GITHUB_RELEASE_AGENT",
            "Release 版本发布与 Changelog 智能体",
            0.3,
            "负责版本发布、Git Tag 比对、自动提取 Changelog 与发版确认卡片装配"
    ),

    /**
     * GitHub CI/CD 流水线与排障智能体 (业务层 ReActAgent)
     */
    GITHUB_WORKFLOW_AGENT(
            "GITHUB_WORKFLOW_AGENT",
            "CI/CD 流水线与排障智能体",
            0.1,
            "排查 GitHub Actions 工作流构建失败、解析测试报错日志并给出修复步骤"
    );

    /**
     * 智能体唯一标识编码
     */
    private final String code;

    /**
     * 智能体友好中文名称
     */
    private final String name;

    /**
     * 推荐默认采样温度
     */
    private final Double defaultTemperature;

    /**
     * 智能体核心职责说明
     */
    private final String description;

    AgentType(String code, String name, Double defaultTemperature, String description) {
        this.code = code;
        this.name = name;
        this.defaultTemperature = defaultTemperature;
        this.description = description;
    }

    /**
     * 根据编码安全解析枚举
     *
     * @param code 智能体编码 (不区分大小写)
     * @return 对应的 AgentType 枚举，未找到则返回 null
     */
    public static AgentType fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        for (AgentType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim()) || type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        return null;
    }
}
