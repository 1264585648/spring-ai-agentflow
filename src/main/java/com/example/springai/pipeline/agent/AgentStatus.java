package com.example.springai.pipeline.agent;

/**
 * 智能体生命周期状态机枚举 (AgentStatus)
 * 严格遵循 RULE-06 守则
 */
public enum AgentStatus {

    /**
     * 草稿调试中 (仅沙箱可见)
     */
    DRAFT,

    /**
     * 在线运行中 (正常对外分流并参与 MasterAgent 调度)
     */
    ONLINE,

    /**
     * 弃用排空中 (新入口阻断，在途长任务与待确认卡片允许继续执行完毕)
     */
    DEPRECATED,

    /**
     * 彻底下线 (完全停用)
     */
    OFFLINE;

    public static boolean isValid(String status) {
        if (status == null || status.trim().isEmpty()) {
            return false;
        }
        for (AgentStatus item : values()) {
            if (item.name().equalsIgnoreCase(status.trim())) {
                return true;
            }
        }
        return false;
    }
}
