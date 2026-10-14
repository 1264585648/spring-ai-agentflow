package com.example.springai.core.card;

import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.model.InteractiveCard;

import java.util.Map;
import java.util.Optional;

/**
 * 通用人机协同卡片服务端状态机注册表 (CardRegistry)
 * 职责:
 * 1. 签发登记: 智能体下发卡片时建立原始快照 (Snapshot) 并标记为 ISSUED;
 * 2. 状态申领与排他锁: 提交时校验 actionId 与 sessionId 合法性，原子跃迁为 SUBMITTING;
 * 3. 防篡改核验: 比对 submittedValues 与原始快照，确保 editable=false 字段未被恶意篡改;
 * 4. 终态流转与归档: 处理成功流转为 SUBMITTED，处理失败流转为 FAILED 或重试释放。
 */
public interface CardRegistry {

    /**
     * 登记并签发交互卡片原始快照
     *
     * @param sessionId 所属会话 ID
     * @param card      智能体装配下发的原始卡片对象
     */
    void issue(String sessionId, InteractiveCard card);

    /**
     * 申领卡片并抢占提交锁 (状态从 ISSUED 跃迁至 SUBMITTING)
     *
     * @param actionId  卡片唯一动作 Token
     * @param sessionId 提交方声明的会话 ID
     * @return 申领到的卡片快照记录
     */
    CardRecord claim(String actionId, String sessionId);

    /**
     * 执行参数防篡改与合规审计
     *
     * @param record          已申领的卡片记录
     * @param submittedValues 用户提交的表单字段名值对
     */
    void validate(CardRecord record, Map<String, Object> submittedValues);

    /**
     * 标记卡片处理成功并归档 (流转为 SUBMITTED 终态)
     *
     * @param actionId 卡片唯一动作 Token
     * @param result   业务处理器返回的成功结果
     */
    void complete(String actionId, CardSubmitResult result);

    /**
     * 标记卡片处理失败
     *
     * @param actionId   卡片唯一动作 Token
     * @param failReason 失败原因
     * @param allowRetry 是否允许专员重试 (true 则退回 ISSUED 状态，false 则标记为 FAILED)
     */
    void fail(String actionId, String failReason, boolean allowRetry);

    /**
     * 获取指定卡片记录 (只读查询)
     *
     * @param actionId 卡片唯一动作 Token
     * @return 卡片记录 Optional
     */
    Optional<CardRecord> getRecord(String actionId);

    /**
     * 基于服务端签发快照重建可信表单参数：
     * 强制恢复只读字段原始值，仅合并白名单内的可编辑字段，剥离客户端未授权注入的额外字段。
     *
     * @param record          服务端签发的卡片记录
     * @param submittedValues 客户端提交的原始字段
     * @return 重建后的安全参数映射
     */
    default Map<String, Object> reconstructSafeFormValues(CardRecord record, Map<String, Object> submittedValues) {
        return submittedValues != null ? submittedValues : Map.of();
    }
}
