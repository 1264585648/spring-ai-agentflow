package com.example.springai.core.event;

import lombok.Data;

/** 传输适配器通知运行时取消断连会话，不携带具体传输对象。 */
@Data
public final class SessionDisconnectedEvent {
    private final String sessionId;
}
