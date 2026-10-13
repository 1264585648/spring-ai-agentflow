package com.example.springai.core.card.action;

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ActionGate 校验、授权后生成的执行入参，不是 HTTP DTO。
 * 无公开构造器、Builder 或 setter；字段值只允许不可变的 JSON 标量。
 * 这是受信任 Java 插件的 API 边界，不是抵御任意插件代码的沙箱。
 */
@Data
public final class ApprovedCardSubmission {
    private final String actionId;
    private final String sessionId;
    private final String cardType;
    private final String actorId;
    private final Map<String, Object> formValues;

    ApprovedCardSubmission(String actionId, String sessionId, String cardType,
                           String actorId, Map<String, Object> formValues) {
        this.actionId = actionId;
        this.sessionId = sessionId;
        this.cardType = cardType;
        this.actorId = actorId;
        this.formValues = Collections.unmodifiableMap(new LinkedHashMap<>(formValues));
    }
}
