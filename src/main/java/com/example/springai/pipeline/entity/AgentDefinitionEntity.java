package com.example.springai.pipeline.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体元数据与生命周期实体 (对应 sys_agent_definition 表)
 * 遵循工程规范 RULE-01 (@Data) 与 RULE-06 (智能体生命周期治理)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sys_agent_definition")
public class AgentDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_code", nullable = false, unique = true, length = 64)
    private String agentCode;

    @Column(name = "agent_name", nullable = false, length = 128)
    private String agentName;

    @Column(name = "agent_type", nullable = false, length = 32)
    @Builder.Default
    private String agentType = "BUSINESS_SUB"; // PIPELINE_CORE / BUSINESS_SUB

    @Column(name = "layer", nullable = false, length = 32)
    @Builder.Default
    private String layer = "BUSINESS"; // ANALYSIS / ORCHESTRATION / BUSINESS / DATA_FLYWHEEL

    @Column(name = "system_prompt", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String systemPrompt;

    @Column(name = "dispatch_desc", nullable = false, length = 512)
    private String dispatchDesc;

    @Column(name = "model_name", length = 64)
    private String modelName;

    @Column(name = "temperature", nullable = false)
    @Builder.Default
    private Double temperature = 0.30;

    @Column(name = "attached_tools", length = 1024)
    private String attachedTools;

    @Column(name = "is_system_core", nullable = false)
    @Builder.Default
    private Integer isSystemCore = 0;

    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private String status = "ONLINE"; // DRAFT / ONLINE / DEPRECATED / OFFLINE

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Integer isEnabled = 1;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

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
