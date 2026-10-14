package com.example.springai.core.card.model;

import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.state.CardState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 服务端卡片状态机快照归档记录 (CardRecord)
 * 职责:
 * 1. 持有智能体下发卡片时的只读字段原始快照 (Snapshot);
 * 2. 追踪生命周期状态机流转 (CardState);
 * 3. 记录签发时间、过期时间与最终处理工单号，为审计追溯提供完整证据链。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardRecord {

    /**
     * 卡片唯一动作标识 (全局唯一 Token)
     */
    private String actionId;

    /**
     * 绑定的会话标识 (用于防跨会话越权冒充)
     */
    private String sessionId;

    /**
     * 绑定的对话回合标识 (TurnId，用于回执精确归属原回合)
     */
    private String turnId;

    /**
     * 卡片业务类型 (如 TROUBLESHOOT_ACTION, IT_EQUIPMENT_APPLY)
     */
    private String cardType;

    /**
     * 卡片主标题
     */
    private String title;

    /**
     * 智能体下发时的原始字段快照 (深拷贝隔离，用于防篡改审计)
     */
    private InteractiveCard snapshot;

    /**
     * 当前状态机生命周期状态
     */
    @Builder.Default
    private CardState state = CardState.ISSUED;

    /**
     * 下发签发时间戳 (毫秒)
     */
    private long issueTime;

    /**
     * 超时失效时间戳 (毫秒)
     */
    private long expireAt;

    /**
     * 提交时间戳 (毫秒)
     */
    private Long submitTime;

    /**
     * 执行成功后关联的系统单号 (如工单号、流程单号 OPS-20261012-7803)
     */
    private String ticketId;

    /**
     * 失败原因或安全告警信息
     */
    private String failReason;

    /**
     * 判断当前是否已超时失效
     */
    public boolean isExpired() {
        return isExpired(System.currentTimeMillis());
    }

    /**
     * 基于可控时钟判断当前是否已超时失效
     */
    public boolean isExpired(long nowMillis) {
        return nowMillis > expireAt;
    }
}
