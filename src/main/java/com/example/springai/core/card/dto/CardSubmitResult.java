package com.example.springai.core.card.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 通用交互卡片提交处理结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardSubmitResult {

    private boolean success;
    private String ticketId;
    private String message;
    private Map<String, Object> extra;

    public CardSubmitResult(boolean success, String ticketId, String message) {
        this.success = success;
        this.ticketId = ticketId;
        this.message = message;
    }

    public static CardSubmitResult ok(String ticketId, String message) {
        return new CardSubmitResult(true, ticketId, message);
    }

    public static CardSubmitResult fail(String message) {
        return new CardSubmitResult(false, null, message);
    }
}
