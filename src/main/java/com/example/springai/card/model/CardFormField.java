package com.example.springai.card.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用交互卡片表单字段模型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardFormField {

    /**
     * 字段唯一标识 (如: relief_amount, leave_days, server_spec)
     */
    private String fieldKey;

    /**
     * 界面展示标签 (如: "特批补偿金额", "申请额度")
     */
    private String label;

    /**
     * 字段展示控件类型: text, number, textarea, select
     */
    private String type;

    /**
     * 字段当前值 (大模型核算出的推荐值或预填值)
     */
    private Object value;

    /**
     * 最大数值限制 (可选，用于前端微调时的上限校验)
     */
    private Object maxLimit;

    /**
     * 最小数值限制 (可选)
     */
    private Object minLimit;

    /**
     * 是否允许人工编辑 (核心设计: 关键参数只读锁住，微调参数允许编辑)
     */
    private boolean editable = true;

    /**
     * 是否必填
     */
    private boolean required = true;

    /**
     * 输入框占位提示文本
     */
    private String placeholder;

    public CardFormField(String fieldKey, String label, String type, Object value, boolean editable, boolean required) {
        this.fieldKey = fieldKey;
        this.label = label;
        this.type = type;
        this.value = value;
        this.editable = editable;
        this.required = required;
    }
}
