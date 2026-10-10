package com.example.springai.card.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用人机协同交互卡片模型 (InteractiveCard)
 * 核心设计: 框架只负责卡片展示与数据收集，业务类型由 cardType 决定
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InteractiveCard {

    /**
     * 本次交互的唯一动作标识 (防重放攻击与幂等性校验 Token)
     */
    private String actionId;

    /**
     * 卡片业务类型标识 (如: TROUBLESHOOT_ACTION, IT_EQUIPMENT_APPLY, LEAVE_REQUEST)
     * 用于驱动后台 SPI 路由到不同的处理器
     */
    private String cardType;

    /**
     * 卡片主标题
     */
    private String title;

    /**
     * 方案依据说明 (如: "根据系统测算，该申请符合绿色通道标准")
     */
    private String description;

    /**
     * 表单字段列表
     */
    private List<CardFormField> fields = new ArrayList<>();

    /**
     * 确认按钮文本 (默认: "确认提交")
     */
    private String confirmButtonText = "确认提交";

    /**
     * 取消按钮文本 (默认: "放弃")
     */
    private String cancelButtonText = "放弃";
}
