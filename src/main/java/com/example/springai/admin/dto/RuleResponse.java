package com.example.springai.admin.dto;

import com.example.springai.pipeline.entity.RuleDefinitionEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 规则信息响应 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleResponse {

    private Long id;
    private String ruleCode;
    private String ruleName;
    private String matchType;
    private String patternExpr;
    private String targetType;
    private String targetRef;
    private String paramTemplate;
    private Integer priority;
    private Integer isEnabled;
    private Long hitCount;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 集群版本号广播结果：OK、SKIPPED、FAILED。仅管理写接口填充。
     */
    private String clusterSync;

    public static RuleResponse fromEntity(RuleDefinitionEntity entity) {
        if (entity == null) return null;
        RuleResponse resp = new RuleResponse();
        resp.setId(entity.getId());
        resp.setRuleCode(entity.getRuleCode());
        resp.setRuleName(entity.getRuleName());
        resp.setMatchType(entity.getMatchType());
        resp.setPatternExpr(entity.getPatternExpr());
        resp.setTargetType(entity.getTargetType());
        resp.setTargetRef(entity.getTargetRef());
        resp.setParamTemplate(entity.getParamTemplate());
        resp.setPriority(entity.getPriority());
        resp.setIsEnabled(entity.getIsEnabled());
        resp.setHitCount(entity.getHitCount());
        resp.setDescription(entity.getDescription());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setUpdatedAt(entity.getUpdatedAt());
        return resp;
    }
}
