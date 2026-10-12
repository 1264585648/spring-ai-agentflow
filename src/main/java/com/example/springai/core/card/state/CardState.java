package com.example.springai.core.card.state;

/**
 * 通用人机协同交互卡片生命周期状态机枚举 (CardState)
 * 状态跃迁拓扑:
 * [*] ➔ ISSUED ➔ SUBMITTING ➔ SUBMITTED / FAILED / EXPIRED / CANCELLED
 */
public enum CardState {

    /**
     * 1. 已签发: 智能体下发卡片，服务端登记字段快照，等待专员处理
     */
    ISSUED,

    /**
     * 2. 提交中: 专员点击提交，CAS 抢锁成功，防并发连击与重复提交
     */
    SUBMITTING,

    /**
     * 3. 已完成: 底层 CardSubmitHandler 执行成功，归档关联工单单号 (终态)
     */
    SUBMITTED,

    /**
     * 4. 处理失败: 执行异常或校验不合规
     */
    FAILED,

    /**
     * 5. 已过期: 超过服务端 TTL (如 10 分钟) 未处理自动废弃
     */
    EXPIRED,

    /**
     * 6. 已取消: 专员主动放弃
     */
    CANCELLED
}
