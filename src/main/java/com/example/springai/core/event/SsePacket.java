package com.example.springai.core.event;

import lombok.Data;
import java.util.UUID;

/** 时间戳仅用于观测；eventId 在数据包创建时生成，重发同一个包保留同一 ID。 */
@Data
public class SsePacket<T> {
    private String event;
    private T data;
    private long timestamp;
    private final String eventId = UUID.randomUUID().toString();
    public SsePacket() { timestamp = System.currentTimeMillis(); }
    public SsePacket(String event, T data, long timestamp) {
        this.event = event; this.data = data; this.timestamp = timestamp;
    }
    public SsePacket(SseEventType eventType, T data) {
        this(eventType.getValue(), data, System.currentTimeMillis());
    }
    public static <T> SsePacket<T> of(SseEventType type, T data) { return new SsePacket<>(type, data); }
}
