package com.example.springai.web.dto;

import com.example.springai.infra.persistence.entity.FaqDefinitionEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * L2 FAQ 知识响应 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FaqResponse {

    private Long id;
    private String faqCode;
    private String faqTitle;
    private String similarQueries;
    private String standardAnswer;
    private String targetAgentCode;
    private String embeddingModel;
    private Integer dimension;
    private Integer priority;
    private Integer isEnabled;
    private Long hitCount;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String clusterSync;

    public static FaqResponse fromEntity(FaqDefinitionEntity entity) {
        if (entity == null) return null;
        FaqResponse resp = new FaqResponse();
        resp.setId(entity.getId());
        resp.setFaqCode(entity.getFaqCode());
        resp.setFaqTitle(entity.getFaqTitle());
        resp.setSimilarQueries(entity.getSimilarQueries());
        resp.setStandardAnswer(entity.getStandardAnswer());
        resp.setTargetAgentCode(entity.getTargetAgentCode());
        resp.setEmbeddingModel(entity.getEmbeddingModel());
        resp.setDimension(entity.getDimension());
        resp.setPriority(entity.getPriority());
        resp.setIsEnabled(entity.getIsEnabled());
        resp.setHitCount(entity.getHitCount());
        resp.setDescription(entity.getDescription());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setUpdatedAt(entity.getUpdatedAt());
        return resp;
    }
}
