package com.example.springai.core.event;

import org.springframework.context.ApplicationEvent;

/**
 * L2 向量知识库热重载事件
 * 当管理端新增/更新 FAQ、或接收到 Apollo 集群广播通知时发布该事件，驱动本地无锁快照热重载。
 */
public class L2VectorReloadEvent extends ApplicationEvent {

    private final String reason;

    public L2VectorReloadEvent(Object source, String reason) {
        super(source);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
