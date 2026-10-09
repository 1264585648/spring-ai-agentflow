package com.example.springai.common.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应结果模型 (ApiResponse)
 * 严格遵循工程规范 RULE-01 (使用 Lombok @Data)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /**
     * 业务状态码 (200 成功，其余为具体业务错误码)
     */
    private int code;

    /**
     * 响应提示信息
     */
    private String message;

    /**
     * 响应数据载荷
     */
    private T data;

    /**
     * 时间戳 (毫秒)
     */
    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message(message)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
