package com.example.springai.scenario.troubleshoot;

import com.example.springai.core.card.model.ActionProposal;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.InteractiveCard;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 智能运维排障卡片构建工厂 (TroubleshootCardFactory)
 * 职责:
 * 1. 基于结构化 ActionProposal 装配 TROUBLESHOOT_ACTION 标准应急处置确认卡片；
 * 2. 强制要求明确的目标服务、处置动作与目标标识，拒绝缺失目标时静默回退至固定生产资源；
 * 3. 对离线演示模拟模式显式打标说明。
 */
@Component
public class TroubleshootCardFactory {

    public InteractiveCard createTroubleshootCard(ActionProposal proposal) {
        if (proposal == null || !proposal.hasExecutableTarget()) {
            throw new IllegalArgumentException("无法生成可执行排障卡片：缺少明确的目标服务 (service)、处置动作 (actionType) 或目标标识 (targetIdentifier)");
        }

        String finalService = proposal.getService().trim();
        String finalAction = proposal.getActionType().trim();
        String finalTarget = proposal.getTargetIdentifier().trim();
        String maxPoolSize = StringUtils.hasText(proposal.getSuggestedMaxPoolSize())
                ? proposal.getSuggestedMaxPoolSize().trim()
                : "80";

        InteractiveCard card = new InteractiveCard();
        card.setActionId("act_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        card.setCardType("TROUBLESHOOT_ACTION");
        card.setTitle(proposal.isSimulationMode()
                ? "智能运维应急止血与处置确认单 [演示模拟]"
                : "智能运维应急止血与处置确认单");

        String descPrefix = proposal.isSimulationMode()
                ? "【演示模拟数据】基于本地模拟端口诊断生成止血预案。"
                : "基于多智能体故障研判，已自动生成止血预案。";
        card.setDescription(descPrefix + "遵循 Human-in-the-loop 安全红线，请核验参数后一键执行：");

        card.getFields().add(new CardFormField(
                "service", "目标故障服务", "text", finalService, false, true
        ));
        card.getFields().add(new CardFormField(
                "action_type", "应急处置动作", "text", finalAction, false, true
        ));
        card.getFields().add(new CardFormField(
                "target_identifier", "慢查会话ID / 目标标识", "text", finalTarget, false, true
        ));

        CardFormField poolField = new CardFormField(
                "max_pool_size", "临时连接池上限调整", "number", maxPoolSize, true, false
        );
        poolField.setMinLimit(10);
        poolField.setMaxLimit(500);
        card.getFields().add(poolField);

        String defaultRemark = StringUtils.hasText(proposal.getEvidenceSummary())
                ? proposal.getEvidenceSummary().trim()
                : "针对 " + finalService + " 报警紧急止血，隔离慢查询 " + finalTarget + "，保障核心交易链路可用。";
        card.getFields().add(new CardFormField(
                "remark", "处置原因与影响评估", "textarea", defaultRemark, true, true
        ));

        card.setConfirmButtonText("确认执行应急处置");
        card.setCancelButtonText("取消/仅记录");
        return card;
    }

    public InteractiveCard createTroubleshootCard(String service, String actionType, String targetIdentifier) {
        if (!StringUtils.hasText(service) || !StringUtils.hasText(actionType) || !StringUtils.hasText(targetIdentifier)) {
            throw new IllegalArgumentException("无法生成可执行排障卡片：service、actionType 与 targetIdentifier 均不能为空");
        }
        ActionProposal proposal = ActionProposal.builder()
                .service(service.trim())
                .actionType(actionType.trim())
                .targetIdentifier(targetIdentifier.trim())
                .simulationMode(false)
                .build();
        return createTroubleshootCard(proposal);
    }
}
