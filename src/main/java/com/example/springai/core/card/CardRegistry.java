package com.example.springai.core.card;

import com.example.springai.core.card.action.CardFormValues;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.model.InteractiveCard;
import java.util.Map;
import java.util.Optional;

/** 提案存储契约。查询返回脱离存储的副本，执行前原子校验签发版本。 */
public interface CardRegistry {
    void issue(String sessionId, InteractiveCard card);
    CardRecord claim(String actionId, String sessionId);
    /** 必须原子检查签发版本与状态，防止校验后被淘汰、重签而执行另一份提案。 */
    CardRecord claim(CardRecord expected);
    void validate(CardRecord record, Map<String, Object> submittedValues);
    default Map<String, Object> reconstructSafeFormValues(CardRecord record, Map<String, Object> values) {
        return CardFormValues.validateAndMerge(record, values);
    }
    void complete(String actionId, CardSubmitResult result);
    /** 仅用于能够证明尚未进入执行器的失败。执行后异常必须调用 markExecutionUnknown。 */
    void fail(String actionId, String reason, boolean allowRetry);
    void markExecutionUnknown(String actionId, String reason);
    Optional<CardRecord> getRecord(String actionId);
}
