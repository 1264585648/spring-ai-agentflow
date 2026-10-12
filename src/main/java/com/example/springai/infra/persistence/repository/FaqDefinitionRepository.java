package com.example.springai.infra.persistence.repository;

import com.example.springai.infra.persistence.entity.FaqDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * L2 FAQ 向量知识仓储接口
 */
@Repository
public interface FaqDefinitionRepository extends JpaRepository<FaqDefinitionEntity, Long> {

    /**
     * 按启用状态查询生效的 FAQ 列表，按优先级升序排序
     */
    List<FaqDefinitionEntity> findByIsEnabledOrderByPriorityAsc(Integer isEnabled);

    /**
     * 按业务编码查询
     */
    Optional<FaqDefinitionEntity> findByFaqCode(String faqCode);

    /**
     * 异步原子累加命中次数
     */
    @Modifying
    @Transactional
    @Query("UPDATE FaqDefinitionEntity f SET f.hitCount = f.hitCount + 1 WHERE f.faqCode = :faqCode")
    void incrementHitCount(@Param("faqCode") String faqCode);
}
