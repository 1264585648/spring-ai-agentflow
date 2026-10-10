package com.example.springai.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 智能体更新请求 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentUpdateRequest {

    /**
     * 智能体友好中文名称
     */
    private String agentName;

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
    private Double temperature;

    /**
     * 挂载工具列表 (JSON 格式)
     */
    private String attachedTools;

    /**
     * 业务说明与备注
     */
    private String description;
}
