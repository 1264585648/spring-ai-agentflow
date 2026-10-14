package com.example.springai.core.card.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

/** 卡片展示契约。simulation 标记证据来自模拟，不能用于批准真实写操作。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InteractiveCard {
    private String actionId;
    private String cardType;
    private String title;
    private String description;
    private List<CardFormField> fields = new ArrayList<>();
    private String confirmButtonText = "确认提交";
    private String cancelButtonText = "放弃";
    private String turnId;
    private boolean simulation;
    public InteractiveCard(String actionId, String cardType, String title, String description,
                           List<CardFormField> fields, String confirmButtonText, String cancelButtonText) {
        this(actionId, cardType, title, description, fields, confirmButtonText, cancelButtonText, null);
    }
    public InteractiveCard(String actionId, String cardType, String title, String description,
                           List<CardFormField> fields, String confirmButtonText, String cancelButtonText,
                           String turnId) {
        this(actionId, cardType, title, description, fields == null ? new ArrayList<>() : fields,
                confirmButtonText, cancelButtonText, turnId, false);
    }
}
