package com.example.springai.core.card.exception;

/**
 * 卡片未找到或已彻底过期异常
 */
public class CardNotFoundException extends RuntimeException {
    public CardNotFoundException(String message) {
        super(message);
    }
}
