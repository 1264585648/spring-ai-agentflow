package com.example.springai.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用问答请求入参 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 用户提问内容
     */
    private String query;

    /**
     * 用户工号/ID
     */
    private String userId;

    /**
     * 业务上下文ID（可选，例如绑定的业务工单/单据/案件号）
     */
    private String caseId;
}
