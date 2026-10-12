package com.example.springai.core.card.exception;

/**
 * 卡片状态冲突异常 (如重复提交、已处理终态、非 ISSUED 状态)
 */
public class CardStateConflictException extends RuntimeException {
    public CardStateConflictException(String message) {
        super(message);
    }
}
