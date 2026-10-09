package com.example.springai.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 智能体新增请求 DTO
 * 遵循工程规范 RULE-01 与 RULE-06
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentCreateRequest {

    /**
     * 智能体唯一编码 (如 INVOICE_AUDIT, 大写字母与下划线)
     */
    private String agentCode;

    /**
     * 智能体友好中文名称 (如 增值税发票审核专家)
     */
    private String agentName;

    /**
     * 所属分层 (默认 BUSINESS: 业务执行层)
     */
    private String layer = "BUSINESS";

    /**
     * 专属系统提示词 (System Prompt)
     */
    private String systemPrompt;

    /**
     * 调度意图描述 (供 MasterAgent 路由意图研判与子任务分发)
     */
    private String dispatchDesc;

    /**
     * 模型底座名称 (为空使用全局默认)
     */
    private String modelName;

    /**
     * 采样温度 (0.00 ~ 1.00)
     */
    private Double temperature = 0.30;

    /**
     * 挂载工具列表 (JSON 格式，如 ["userAccountTool.queryBalance"])
     */
    private String attachedTools;

    /**
     * 业务说明与备注
     */
    private String description;
}
