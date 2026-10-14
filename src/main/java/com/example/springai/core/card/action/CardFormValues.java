package com.example.springai.core.card.action;

import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.CardRecord;
import java.math.BigDecimal;
import java.util.*;

/** 校验与执行共用同一份规范化值。未知字段和可变嵌套结构一律拒绝。 */
public final class CardFormValues {
    private CardFormValues() { }
    public static Map<String, Object> validateAndMerge(CardRecord record, Map<String, Object> submitted) {
        if (record == null || record.getSnapshot() == null || record.getSnapshot().getFields() == null) {
            throw new CardSecurityException("缺少服务端字段快照");
        }
        Map<String, Object> incoming = submitted == null ? Map.of() : new LinkedHashMap<>(submitted);
        Map<String, CardFormField> schema = new LinkedHashMap<>();
        for (CardFormField field : record.getSnapshot().getFields()) {
            if (field == null || field.getFieldKey() == null || field.getFieldKey().isBlank()
                    || schema.putIfAbsent(field.getFieldKey(), field) != null) {
                throw new CardSecurityException("字段定义为空或重复");
            }
        }
        if (!schema.keySet().containsAll(incoming.keySet())) throw new CardSecurityException("提交了未签发的表单字段");
        Map<String, Object> result = new LinkedHashMap<>();
        for (CardFormField field : schema.values()) {
            String key = field.getFieldKey();
            Object original = normalize(field, field.getValue());
            Object value = incoming.containsKey(key) ? normalize(field, incoming.get(key)) : original;
            if (!field.isEditable()) {
                if (!Objects.equals(original, value)) throw new CardSecurityException("只读参数 [" + key + "] 不允许修改");
                value = original;
            }
            if (field.isRequired() && (value == null || value instanceof String s && s.isBlank())) {
                throw new IllegalArgumentException("必填字段 [" + key + "] 不能为空");
            }
            if (value instanceof BigDecimal n && ((field.getMinLimit() != null && n.compareTo(decimal(field.getMinLimit())) < 0)
                    || (field.getMaxLimit() != null && n.compareTo(decimal(field.getMaxLimit())) > 0))) {
                throw new IllegalArgumentException("数值字段 [" + key + "] 超出允许范围");
            }
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }
    private static Object normalize(CardFormField field, Object value) {
        String type = field.getType();
        if (!Set.of("text", "textarea", "select", "number").contains(type == null ? "" : type)) {
            throw new IllegalArgumentException("不支持的字段类型");
        }
        if (value == null) return null;
        if ("number".equals(type)) return value instanceof String s && s.isBlank() ? null : decimal(value);
        if (!(value instanceof String s) || s.length() > 8192) throw new IllegalArgumentException("文本必须是长度不超过 8192 的字符串");
        return value;
    }
    private static BigDecimal decimal(Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) throw new IllegalArgumentException("只接受有限数字或数字字符串");
        String text = value.toString().trim();
        if (text.length() > 128) throw new IllegalArgumentException("数字过长");
        try {
            BigDecimal result = new BigDecimal(text).stripTrailingZeros();
            if (result.precision() > 128 || Math.abs((long) result.scale()) > 128) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("只接受有限数字或数字字符串"); }
    }
}
