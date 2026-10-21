package com.example.springai.infra.persistence.repository;

import com.example.springai.infra.persistence.entity.ActionLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 动作提案与执行账本仓储接口 (对应 sys_action_ledger 表)
 */
@Repository
public interface ActionLedgerRepository extends JpaRepository<ActionLedgerEntity, Long> {

    Optional<ActionLedgerEntity> findByActionId(String actionId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ActionLedgerEntity e SET " +
            "e.approvalState = :approvalState, " +
            "e.executionState = :executionState, " +
            "e.cardState = :newCardState, " +
            "e.actorId = :actorId, " +
            "e.submittedValuesJson = :submittedValuesJson, " +
            "e.ticketId = :ticketId, " +
            "e.failReason = :failReason, " +
            "e.submitTime = :submitTime " +
            "WHERE e.actionId = :actionId " +
            "AND e.issuanceId = :expectedIssuanceId " +
            "AND e.cardState = :expectedCardState " +
            "AND (e.approvalState = 'PENDING' OR :newCardState <> 'ISSUED')")
    int casTransition(@Param("actionId") String actionId,
                      @Param("expectedIssuanceId") String expectedIssuanceId,
                      @Param("expectedCardState") String expectedCardState,
                      @Param("approvalState") String approvalState,
                      @Param("executionState") String executionState,
                      @Param("newCardState") String newCardState,
                      @Param("actorId") String actorId,
                      @Param("submittedValuesJson") String submittedValuesJson,
                      @Param("ticketId") String ticketId,
                      @Param("failReason") String failReason,
                      @Param("submitTime") Long submitTime);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ActionLedgerEntity e SET " +
            "e.approvalState = :approvalState, " +
            "e.executionState = :executionState, " +
            "e.cardState = :newCardState, " +
            "e.ticketId = :ticketId, " +
            "e.failReason = :failReason, " +
            "e.submitTime = :submitTime " +
            "WHERE e.actionId = :actionId " +
            "AND e.cardState = :expectedCurrentState")
    int updateState(@Param("actionId") String actionId,
                    @Param("expectedCurrentState") String expectedCurrentState,
                    @Param("approvalState") String approvalState,
                    @Param("executionState") String executionState,
                    @Param("newCardState") String newCardState,
                    @Param("ticketId") String ticketId,
                    @Param("failReason") String failReason,
                    @Param("submitTime") Long submitTime);
}
