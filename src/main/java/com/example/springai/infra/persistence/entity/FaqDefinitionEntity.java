package com.example.springai.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * L2 FAQ 与排障 SOP 向量知识定义实体 (对应 sys_faq_definition 表)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sys_faq_definition")
public class FaqDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "faq_code", nullable = false, unique = true, length = 64)
    private String faqCode;

    @Column(name = "faq_title", nullable = false, length = 255)
    private String faqTitle;

    @Column(name = "similar_queries", columnDefinition = "TEXT")
    private String similarQueries;

    @Column(name = "standard_answer", nullable = false, columnDefinition = "TEXT")
    private String standardAnswer;

    @Column(name = "target_agent_code", length = 64)
    private String targetAgentCode;

    @Lob
    @Column(name = "embedding_vector", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] embeddingVector;

    @Column(name = "embedding_model", nullable = false, length = 64)
    private String embeddingModel = "text-embedding-3-small";

    @Column(name = "dimension", nullable = false)
    private Integer dimension = 1536;

    @Column(name = "priority", nullable = false)
    private Integer priority = 100;

    @Column(name = "is_enabled", nullable = false)
    private Integer isEnabled = 1;

    @Column(name = "hit_count", nullable = false)
    private Long hitCount = 0L;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (priority == null) {
            priority = 100;
        }
        if (isEnabled == null) {
            isEnabled = 1;
        }
        if (hitCount == null) {
            hitCount = 0L;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
