package com.example.springai.pipeline.event;

import org.springframework.context.ApplicationEvent;

/**
 * L1 规则热重载事件 (当管理端增删改规则后发布该事件，驱动内存零停机刷新)
 */
public class L1RuleReloadEvent extends ApplicationEvent {

    private final String reason;

    public L1RuleReloadEvent(Object source, String reason) {
        super(source);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
