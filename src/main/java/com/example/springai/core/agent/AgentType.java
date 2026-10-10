package com.example.springai.core.agent;

import lombok.Getter;

/**
 * 企业多智能体矩阵角色枚举定义 (AgentType)
 * 严格对应企业多智能体职责矩阵与分层设计 (基于智能运维与排障体系)
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
            "负责多轮协同会话指代消除、提取关键服务与单号，输出规范查询"
    ),

    /**
     * 主协调调度智能体 (协调层 ReActAgent - 管道核心)
     */
    MASTER_AGENT(
            "MASTER_AGENT",
            "主协调调度智能体",
            0.2,
            "负责复杂任务与故障依赖拆解、子专家协同调度与多源分析结果聚合"
    ),

    /**
     * 通用对话与协同智能体 (通用兜底)
     */
    GENERAL_AGENT(
            "GENERAL_AGENT",
            "通用对话与协同智能体",
            0.7,
            "负责通用对话交互、综合问答与兜底业务处理"
    ),

    /**
     * 日志异常分析智能体 (业务层 ReActAgent)
     */
    LOG_DIAGNOSE_AGENT(
            "LOG_DIAGNOSE_AGENT",
            "日志异常分析智能体",
            0.1,
            "负责服务日志检索、异常堆栈解析、Trace 分布式链路排查与已知故障库比对"
    ),

    /**
     * 数据库诊断智能体 (业务层 ReActAgent)
     */
    DB_DIAGNOSE_AGENT(
            "DB_DIAGNOSE_AGENT",
            "数据库诊断智能体",
            0.1,
            "负责数据库慢SQL检索、死锁与长事务分析、连接池水位诊断与性能调优建议"
    ),

    /**
     * 应急止血与运维协同智能体 (业务层 ReActAgent)
     */
    SRE_COPILOT_AGENT(
            "SRE_COPILOT_AGENT",
            "应急止血与运维协同智能体",
            0.2,
            "负责故障综合研判、制定应急止血处置方案、装配确认卡片并引导工程师核验执行"
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
