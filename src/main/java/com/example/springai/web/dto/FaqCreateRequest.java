package com.example.springai.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建/更新 L2 FAQ 知识请求 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FaqCreateRequest {

    private String faqCode;
    private String faqTitle;
    private String similarQueries;
    private String standardAnswer;
    private String targetAgentCode;
    private Integer priority;
    private Integer isEnabled;
    private String description;
}
