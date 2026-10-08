package com.example.springai.common.result;

import com.example.springai.common.constant.SseEventType;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * SSE 统一数据包包装类
 */
@Data
@AllArgsConstructor
public class SsePacket<T> {

    private String event;
    private T data;
    private long timestamp;

    public SsePacket() {
        this.timestamp = System.currentTimeMillis();
    }

    public SsePacket(SseEventType eventType, T data) {
        this.event = eventType.getValue();
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> SsePacket<T> of(SseEventType eventType, T data) {
        return new SsePacket<>(eventType, data);
    }
}
