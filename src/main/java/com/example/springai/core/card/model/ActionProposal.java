package com.example.springai.core.card.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化运维操作草案 (ActionProposal)
 * 职责:
 * 1. 将专家诊断证据与拟执行的应急处置动作解耦建模；
 * 2. 强制要求具备明确的目标服务 (service)、处置动作 (actionType) 与目标标识 (targetIdentifier) 方可生成可执行卡片；
 * 3. 显式区分真实诊断提案与离线演示模拟提案 (simulationMode)，杜绝静默填补固定生产目标。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionProposal {

    /**
     * 目标故障服务名 (必填，禁止静默回退至默认生产服务)
     */
    private String service;

    /**
     * 拟执行的应急处置动作
     */
    private String actionType;

    /**
     * 目标资源/慢查会话/事务标识 (必填，禁止静默回退至固定标识)
     */
    private String targetIdentifier;

    /**
     * 风险等级 (如 HIGH, MEDIUM, LOW)
     */
    @Builder.Default
    private String riskLevel = "HIGH";

    /**
     * 支撑该操作草案的诊断证据摘要
     */
    private String evidenceSummary;

    /**
     * 临时连接池上限建议值 (可选)
     */
    @Builder.Default
    private String suggestedMaxPoolSize = "80";

    /**
     * 是否为离线演示模拟生成 (true 表示未连接真实大模型底座，由模拟端口生成的演练草案)
     */
    @Builder.Default
    private boolean simulationMode = false;

    /**
     * 附加扩展参数
     */
    @Builder.Default
    private Map<String, Object> extraParameters = new LinkedHashMap<>();

    /**
     * 判断当前草案是否具备完整的可执行目标要素
     */
    public boolean hasExecutableTarget() {
        return StringUtils.hasText(service)
                && StringUtils.hasText(actionType)
                && StringUtils.hasText(targetIdentifier);
    }
}
