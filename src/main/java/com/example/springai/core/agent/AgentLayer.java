package com.example.springai.core.agent;

/**
 * 多智能体架构分层枚举 (AgentLayer)
 */
public enum AgentLayer {

    /**
     * 分析层：输入标准化、多轮指代消除、前置凭证安全脱敏
     */
    ANALYSIS,

    /**
     * 协调调度层：复合多意图依赖拆解、任务规划与各专家结论汇聚 (MasterAgent)
     */
    ORCHESTRATION,

    /**
     * 业务执行层：专业领域 ReAct 专家 (Issue 治理、PR 审查、Release 发版、Actions 排障)
     */
    BUSINESS,

    /**
     * 数据闭环层：历史缺陷聚类、常见报错离线总结反哺知识库
     */
    DATA_FLYWHEEL;

    public static boolean isValid(String layer) {
        if (layer == null || layer.trim().isEmpty()) {
            return false;
        }
        for (AgentLayer item : values()) {
            if (item.name().equalsIgnoreCase(layer.trim())) {
                return true;
            }
        }
        return false;
    }
}
