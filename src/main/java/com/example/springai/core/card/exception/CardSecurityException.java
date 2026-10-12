package com.example.springai.core.card.exception;

/**
 * 卡片安全审计异常 (跨会话越权、只读字段篡改等恶意攻击)
 */
public class CardSecurityException extends RuntimeException {
    public CardSecurityException(String message) {
        super(message);
    }
}
