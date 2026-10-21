package com.example.springai.core.tool;

/**
 * 统一调用入口的调用来源分类（12 号架构设计：模型调用、Workflow 直调、L1 直通、卡片提交、对账恢复全部收敛于同一入口）。
 */
public enum InvocationSource {
    /** L1 规则引擎直通调用只读工具 */
    L1_DIRECT,
    /** 大模型 Function Calling 自主调用智能体白名单内的只读工具 */
    AGENT_MODEL_TOOL,
    /** 确定性 Workflow 节点直调工具 */
    WORKFLOW_DIRECT,
    /** 经 ActionGate 审批通过后的写操作/高危动作执行 */
    ACTION_GATE_WRITE,
    /** 经 ActionGate 触发的崩溃后幂等对账查询 */
    ACTION_GATE_RECONCILE
}
