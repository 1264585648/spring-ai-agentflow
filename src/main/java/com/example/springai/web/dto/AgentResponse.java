package com.example.springai.web.dto;

import com.example.springai.infra.persistence.entity.AgentDefinitionEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体元数据响应 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    private Long id;
    private String agentCode;
    private String agentName;
    private String agentType;
    private String layer;
    private String systemPrompt;
    private String dispatchDesc;
    private String modelName;
    private Double temperature;
    private String attachedTools;
    private Integer isSystemCore;
    private String status;
    private Integer isEnabled;
    private Integer version;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 集群版本广播状态 (OK / SKIPPED / FAILED)
     */
    private String clusterSync;

    public static AgentResponse fromEntity(AgentDefinitionEntity entity) {
        if (entity == null) return null;
        AgentResponse resp = new AgentResponse();
        resp.setId(entity.getId());
        resp.setAgentCode(entity.getAgentCode());
        resp.setAgentName(entity.getAgentName());
        resp.setAgentType(entity.getAgentType());
        resp.setLayer(entity.getLayer());
        resp.setSystemPrompt(entity.getSystemPrompt());
        resp.setDispatchDesc(entity.getDispatchDesc());
        resp.setModelName(entity.getModelName());
        resp.setTemperature(entity.getTemperature());
        resp.setAttachedTools(entity.getAttachedTools());
        resp.setIsSystemCore(entity.getIsSystemCore());
        resp.setStatus(entity.getStatus());
        resp.setIsEnabled(entity.getIsEnabled());
        resp.setVersion(entity.getVersion());
        resp.setDescription(entity.getDescription());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setUpdatedAt(entity.getUpdatedAt());
        return resp;
    }
}
