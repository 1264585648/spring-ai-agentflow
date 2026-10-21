package com.example.springai.core.card.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用交互卡片放弃/取消请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardCancelRequest {

    /**
     * 卡片下发的唯一 actionId
     */
    private String actionId;

    /**
     * 当前会话 ID
     */
    private String sessionId;

    /**
     * 放弃原因（可选）
     */
    private String reason;
}
