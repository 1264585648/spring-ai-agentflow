package com.example.springai.core.card.action;

import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.CardRecord;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 按签发快照重建最终参数；不把客户端原始 Map 传给业务执行器。 */
public final class CardFormValues {
    private CardFormValues() { }

    public static Map<String, Object> validateAndMerge(CardRecord record, Map<String, Object> submitted) {
        if (record == null || record.getSnapshot() == null || record.getSnapshot().getFields() == null) {
            throw new CardSecurityException("缺少服务端字段快照，拒绝执行");
        }
        Map<String, Object> incoming = submitted == null ? Map.of() : new LinkedHashMap<>(submitted);
        Map<String, CardFormField> schema = new LinkedHashMap<>();
        for (CardFormField field : record.getSnapshot().getFields()) {
            if (field == null || field.getFieldKey() == null || field.getFieldKey().isBlank()
                    || schema.putIfAbsent(field.getFieldKey(), field) != null) {
                throw new CardSecurityException("服务端字段定义为空或重复，拒绝执行");
            }
        }
        for (String key : incoming.keySet()) {
            if (!schema.containsKey(key)) {
                throw new CardSecurityException("提交了未签发的表单字段");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (CardFormField field : schema.values()) {
            String key = field.getFieldKey();
            Object original = normalize(field, field.getValue());
            Object value = incoming.containsKey(key) ? normalize(field, incoming.get(key)) : original;
            if (!field.isEditable()) {
                if (!Objects.equals(original, value)) {
                    throw new CardSecurityException("安全警告：只读参数 [" + key + "] 不允许修改");
                }
                value = original; // 只读参数以服务端值为准，客户端可以省略回传。
            }
            if (field.isRequired() && (value == null || value instanceof String s && s.isBlank())) {
                throw new IllegalArgumentException("必填字段 [" + key + "] 不能为空");
            }
            if (value instanceof BigDecimal number) {
                if ((field.getMinLimit() != null && number.compareTo(decimal(field.getMinLimit())) < 0)
                        || (field.getMaxLimit() != null && number.compareTo(decimal(field.getMaxLimit())) > 0)) {
                    throw new IllegalArgumentException("数值字段 [" + key + "] 超出允许范围");
                }
            }
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object normalize(CardFormField field, Object value) {
        String type = field.getType();
        if (!"text".equals(type) && !"textarea".equals(type)
                && !"select".equals(type) && !"number".equals(type)) {
            throw new IllegalArgumentException("不支持的字段类型: " + type);
        }
        if (value == null) {
            return null;
        }
        if ("number".equals(type)) {
            if (value instanceof String s && s.isBlank()) {
                return null;
            }
            return decimal(value);
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("文本字段 [" + field.getFieldKey() + "] 只接受字符串");
        }
        return value; // 不 trim 只读标识，避免校验使用一个值而执行使用另一个值。
    }

    private static BigDecimal decimal(Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw new IllegalArgumentException("数值字段只接受有限数字或数字字符串");
        }
        try {
            return new BigDecimal(value.toString().trim()).stripTrailingZeros();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("数值字段只接受有限数字或数字字符串");
        }
    }
}
