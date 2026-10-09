package com.example.springai.pipeline.repository;

import com.example.springai.pipeline.entity.AgentDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 智能体定义 JPA 数据访问层 (AgentDefinitionRepository)
 */
@Repository
public interface AgentDefinitionRepository extends JpaRepository<AgentDefinitionEntity, Long> {

    /**
     * 根据智能体唯一编码查询
     */
    Optional<AgentDefinitionEntity> findByAgentCode(String agentCode);

    /**
     * 判断智能体编码是否已存在
     */
    boolean existsByAgentCode(String agentCode);

    /**
     * 按分层、生命周期状态与启停状态查询
     */
    List<AgentDefinitionEntity> findByLayerAndStatusAndIsEnabled(String layer, String status, Integer isEnabled);

    /**
     * 查询所有智能体（系统核心优先，ID正序）
     */
    List<AgentDefinitionEntity> findAllByOrderByIsSystemCoreDescIdAsc();
}
