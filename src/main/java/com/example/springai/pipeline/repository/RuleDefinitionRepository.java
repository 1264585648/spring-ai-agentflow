package com.example.springai.pipeline.repository;

import com.example.springai.pipeline.entity.RuleDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * L1 规则定义数据仓储接口
 */
@Repository
public interface RuleDefinitionRepository extends JpaRepository<RuleDefinitionEntity, Long> {

    /**
     * 按启用状态查询规则列表，并按优先级升序排序 (优先级数值越小越靠前)
     *
     * @param isEnabled 启用状态: 1-启用, 0-禁用
     * @return 规则列表
     */
    List<RuleDefinitionEntity> findByIsEnabledOrderByPriorityAsc(Integer isEnabled);

    /**
     * 按业务编码查询规则
     *
     * @param ruleCode 规则业务唯一标识
     * @return 规则实体
     */
    Optional<RuleDefinitionEntity> findByRuleCode(String ruleCode);

    /**
     * 异步原子累加命中次数
     *
     * @param ruleCode 规则编码
     */
    @Modifying
    @Transactional
    @Query("UPDATE RuleDefinitionEntity r SET r.hitCount = r.hitCount + 1 WHERE r.ruleCode = :ruleCode")
    void incrementHitCount(@Param("ruleCode") String ruleCode);
}
