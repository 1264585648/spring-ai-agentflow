package com.example.springai.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * L1 规则定义实体 (对应 sys_rule_definition 表)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sys_rule_definition")
public class RuleDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, unique = true, length = 64)
    private String ruleCode;

    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    @Column(name = "match_type", nullable = false, length = 32)
    private String matchType;

    @Column(name = "pattern_expr", nullable = false, length = 255)
    private String patternExpr;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_ref", nullable = false, length = 255)
    private String targetRef;

    @Column(name = "param_template", length = 1024)
    private String paramTemplate;

    @Column(name = "priority", nullable = false)
    private Integer priority = 100;

    @Column(name = "is_enabled", nullable = false)
    private Integer isEnabled = 1;

    @Column(name = "hit_count", nullable = false)
    private Long hitCount = 0L;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onPrePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void onPreUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
