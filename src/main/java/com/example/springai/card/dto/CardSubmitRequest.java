package com.example.springai.card.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 通用交互卡片确认提交入参 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardSubmitRequest {

    /**
     * 卡片下发的唯一 actionId（防重复提交）
     */
    private String actionId;

    /**
     * 当前会话 ID
     */
    private String sessionId;

    /**
     * 卡片业务类型标识
     */
    private String cardType;

    /**
     * 用户确认或微调后的字段名值对
     */
    private Map<String, Object> formValues;
}
