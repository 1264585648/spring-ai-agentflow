package com.example.springai.core.card.exception;

/** 执行已经开始，无法确认是否产生副作用；同一提案禁止自动重试。 */
public class CardExecutionUnknownException extends RuntimeException {
    public CardExecutionUnknownException(Throwable cause) {
        super("动作执行结果未知，请先核查外部系统；不要重复提交或重新签发同一动作", cause);
    }
}
