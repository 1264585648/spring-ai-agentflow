package com.example.springai.pipeline.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 单步子任务执行节点 DTO (DispatchStep)
 * 严格遵循工程规范 RULE-01 (使用 Lombok @Data)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchStep {

    /**
     * 执行步骤序号（从 1 开始递增，表示执行依赖顺序）
     */
    private int stepOrder;

    /**
     * 目标业务子智能体唯一编码 (如 LOG_DIAGNOSE_AGENT, DB_DIAGNOSE_AGENT)
     */
    private String targetAgent;

    /**
     * 目标智能体友好名称 (如 "CI/CD 流水线排障智能体")
     */
    private String targetAgentName;

    /**
     * 本步骤派发给该专家的具体任务指令描述
     */
    private String taskDesc;

    /**
     * 传递给子专家的上下文输入参数 (如 {"repo": "spring-projects/spring-ai", "pr": 512})
     */
    private Map<String, Object> inputParams;
}
