package com.example.springai.core.card.exception;

/**
 * 处理器能够证明「外部副作用尚未发生」时抛出。
 * ActionGate 会把提案从 SUBMITTING 释放回 ISSUED（或过期态），允许修正后重试；
 * 无法证明时不要使用本异常，否则会把「结果未知」误判为「未执行」。
 */
public class CardNotExecutedException extends RuntimeException {

    public CardNotExecutedException(String message) {
        super(message);
    }

    public CardNotExecutedException(String message, Throwable cause) {
        super(message, cause);
    }
}
