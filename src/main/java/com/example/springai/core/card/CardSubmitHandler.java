package com.example.springai.core.card;

import com.example.springai.core.card.action.ApprovedCardSubmission;
import com.example.springai.core.card.dto.CardSubmitResult;

/** 通用卡片执行 SPI。所有 HTTP 提交须先经过 ActionGate，不接收客户端原始请求。 */
public interface CardSubmitHandler {
    boolean supports(String cardType);

    /** 默认视为真实写能力。只有确实不访问真实写系统的实现才能声明模拟。 */
    default boolean isSimulation() {
        return false;
    }

    /** 无副作用参数预检；抛出 IllegalArgumentException 时保留提案供修正。 */
    default void validate(ApprovedCardSubmission submission) { }

    /**
     * 执行一次经批准的提交。actionId 可作为下游业务幂等键。
     * 异常或失败响应不保证外部未执行，ActionGate 将保守地禁止再次提交。
     * 只有能证明外部副作用尚未发生时，才可抛出 {@link com.example.springai.core.card.exception.CardNotExecutedException}，
     * 此时提案会被释放回 ISSUED 允许修正后重试。
     */
    CardSubmitResult handleSubmit(ApprovedCardSubmission submission);
}
