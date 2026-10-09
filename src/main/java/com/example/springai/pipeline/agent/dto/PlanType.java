package com.example.springai.pipeline.agent.dto;

/**
 * MasterAgent 调度决策计划类型枚举 (PlanType)
 */
public enum PlanType {

    /**
     * 单意图直通：直接路由至单一垂类业务子专家，跳过复杂协同编排
     */
    SINGLE,

    /**
     * 复合多意图：拆解为有序的多步子任务链，跨专家依次协同执行
     */
    COMPOSITE,

    /**
     * 兜底降级：意图未知、目标专家已下线或无匹配能力
     */
    FALLBACK
}
