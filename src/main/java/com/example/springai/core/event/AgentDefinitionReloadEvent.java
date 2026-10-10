package com.example.springai.core.event;

import org.springframework.context.ApplicationEvent;

/**
 * 智能体元数据与人设热重载事件
 * 当管理端增改或调整智能体状态后发布该事件，驱动内存零停机刷新
 */
public class AgentDefinitionReloadEvent extends ApplicationEvent {

    private final String reason;

    public AgentDefinitionReloadEvent(Object source, String reason) {
        super(source);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
