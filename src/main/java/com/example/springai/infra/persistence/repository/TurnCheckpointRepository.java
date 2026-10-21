package com.example.springai.infra.persistence.repository;

import com.example.springai.infra.persistence.entity.TurnCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 回合挂起检查点仓储接口 (对应 sys_turn_checkpoint 表)
 */
@Repository
public interface TurnCheckpointRepository extends JpaRepository<TurnCheckpointEntity, Long> {

    Optional<TurnCheckpointEntity> findByTurnId(String turnId);

    Optional<TurnCheckpointEntity> findByPendingActionId(String pendingActionId);
}
