package com.example.springai.core.card.port;

import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.state.CardState;

import java.util.Optional;

/**
 * 动作账本持久化端口 SPI（遵循六边形架构与 RULE-11：core 定义契约，infra.persistence 提供实现）。
 */
public interface ActionLedgerPort {
    /**
     * 插入新签发的动作提案记录；若 actionId 已存在则返回 false。
     */
    boolean insert(CardRecord record);

    /**
     * 按 actionId 查询账本记录副本。
     */
    Optional<CardRecord> findByActionId(String actionId);

    /**
     * 基于 (actionId, expectedIssuanceId, expectedState) 执行 CAS 原子状态迁移。
     * 只有数据库中当前记录严格匹配预期签发版本与预期状态时才更新并返回 true。
     */
    boolean casTransition(String actionId, String expectedIssuanceId, CardState expectedState, CardRecord updated);

    /**
     * 在已持有执行权或对账恢复时更新记录状态。
     */
    boolean updateState(String actionId, CardState expectedCurrentState, CardRecord updated);
}
