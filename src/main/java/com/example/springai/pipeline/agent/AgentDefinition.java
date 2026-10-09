package com.example.springai.pipeline.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 智能体元数据与提示词模型定义 (AgentDefinition)
 * 遵循工程规范 RULE-01: 使用 Lombok @Data，支持多智能体人设、温度与提示词隔离
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 智能体唯一编码 (如 QUERY_REWRITER, GITHUB_ISSUE_AGENT, MASTER_AGENT)
     */
    private String agentCode;

    /**
     * 智能体友好名称
     */
    private String agentName;

    /**
     * 智能体类型：PIPELINE_CORE(骨架核心), BUSINESS_SUB(业务专家)
     */
    @Builder.Default
    private String agentType = "BUSINESS_SUB";

    /**
     * 架构分层：ANALYSIS(分析), ORCHESTRATION(协调), BUSINESS(业务), DATA_FLYWHEEL(数据闭环)
     */
    @Builder.Default
    private String layer = "BUSINESS";

    /**
     * 专属系统提示词 (System Prompt)
     */
    private String systemPrompt;

    /**
     * 调度语义描述 (供 MasterAgent 路由判定使用)
     */
    private String dispatchDesc;

    /**
     * 指定模型底座名称 (如 deepseek-v3, gpt-4o, qwen-max 等)
     */
    private String modelName;

    /**
     * 模型采样温度 (0.0 ~ 1.0)
     */
    @Builder.Default
    private Double temperature = 0.3;

    /**
     * 挂载工具列表 (JSON 字符串，如 ["userAccountTool.queryBalance"])
     */
    private String attachedTools;

    /**
     * 是否系统核心保留 (1-是, 0-否)
     */
    @Builder.Default
    private Integer isSystemCore = 0;

    /**
     * 生命周期状态：DRAFT, ONLINE, DEPRECATED, OFFLINE
     */
    @Builder.Default
    private String status = "ONLINE";

    /**
     * 启停状态: 1-启用, 0-禁用
     */
    @Builder.Default
    private Integer isEnabled = 1;

    /**
     * 版本号
     */
    @Builder.Default
    private Integer version = 1;

    /**
     * 智能体职责说明与备注
     */
    private String description;
}
