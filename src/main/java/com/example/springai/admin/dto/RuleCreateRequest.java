package com.example.springai.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规则新增/修改请求 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleCreateRequest {

    /**
     * 规则唯一业务编码 (如 CMD_USER_INFO)
     */
    private String ruleCode;

    /**
     * 规则展示名称 (如 用户信息极速查询)
     */
    private String ruleName;

    /**
     * 匹配类型: PREFIX, EXACT, REGEX
     */
    private String matchType = "REGEX";

    /**
     * 匹配表达式模式串
     */
    private String patternExpr;

    /**
     * 目标类型: TOOL, STATIC_TEXT, INTERACTIVE_CARD, WORKFLOW
     */
    private String targetType = "TOOL";

    /**
     * 目标引用 (如 userAccountTool.queryBalance)
     */
    private String targetRef;

    /**
     * 参数插桩模板 (如 {"userId": "$1"})
     */
    private String paramTemplate;

    /**
     * 优先级 (越小越先匹配)
     */
    private Integer priority = 100;

    /**
     * 是否启用: 1-启用, 0-禁用
     */
    private Integer isEnabled = 1;

    /**
     * 业务说明
     */
    private String description;
}
