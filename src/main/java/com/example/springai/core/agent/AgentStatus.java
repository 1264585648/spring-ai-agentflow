package com.example.springai.core.agent;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 智能体生命周期状态机枚举 (AgentStatus)
 * 严格遵循 RULE-06 守则：只允许显式迁移，禁止原地跳变。
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

    /** 允许的迁移矩阵；同状态视为幂等空操作。 */
    private static final Map<AgentStatus, Set<AgentStatus>> ALLOWED_TRANSITIONS = Map.of(
            DRAFT, EnumSet.of(ONLINE, OFFLINE),
            ONLINE, EnumSet.of(DEPRECATED),
            DEPRECATED, EnumSet.of(ONLINE, OFFLINE),
            OFFLINE, EnumSet.of(ONLINE)
    );

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

    /**
     * 校验迁移是否合法。
     *
     * @param from 当前状态；null 表示新建记录
     * @param to   目标状态
     * @return 是否允许；同状态返回 true（幂等）
     */
    public static boolean canTransition(AgentStatus from, AgentStatus to) {
        if (to == null) {
            return false;
        }
        if (from == null || from == to) {
            return true;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
