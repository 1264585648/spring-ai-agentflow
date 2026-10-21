package com.example.springai.core.card;

import com.example.springai.core.card.action.ApprovedCardSubmission;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.model.CardRecord;

import java.util.Optional;

/** 通用卡片执行 SPI。所有 HTTP 提交须先经过 ActionGate，不接收客户端原始请求。 */
public interface CardSubmitHandler {
    boolean supports(String cardType);

    /** 默认视为真实写能力。只有确实不访问真实写系统的实现才能声明模拟。 */
    default boolean isSimulation() {
        return false;
    }

    /** 当前执行器/工具实现的语义版本号；等待审批期间若版本变化，ActionGate 拒绝隐式切换实现。 */
    default String toolVersion() {
        return "v1";
    }

    /** 无副作用参数预检；抛出 IllegalArgumentException 时保留提案供修正。 */
    default void validate(ApprovedCardSubmission submission) { }

    /**
     * 执行一次经批准的提交。actionId / idempotencyKey 可作为下游业务幂等键。
     * 异常或失败响应不保证外部未执行，ActionGate 将保守地禁止再次提交。
     */
    CardSubmitResult handleSubmit(ApprovedCardSubmission submission);

    /**
     * 当执行超时或外部写成功但落库前崩溃（处于 SUBMITTING 或 EXECUTION_UNKNOWN）时，
     * 通过幂等键向外部系统查询真实执行结果以完成对账恢复，绝不重新触发外部写操作。
     */
    default Optional<CardSubmitResult> reconcile(CardRecord record) {
        return Optional.empty();
    }
}
