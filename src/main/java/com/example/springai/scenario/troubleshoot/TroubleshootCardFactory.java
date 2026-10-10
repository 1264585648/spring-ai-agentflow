package com.example.springai.scenario.troubleshoot;

import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.InteractiveCard;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 智能运维排障卡片构建工厂 (TroubleshootCardFactory)
 * 职责:
 * 负责装配 TROUBLESHOOT_ACTION 标准应急处置确认卡片。
 */
@Component
public class TroubleshootCardFactory {

    public InteractiveCard createTroubleshootCard(String service, String actionType, String targetIdentifier) {
        String finalService = service != null && !service.isBlank() ? service : "order-service";
        String finalAction = actionType != null && !actionType.isBlank() ? actionType : "Kill阻塞慢查询并临时扩容连接池";
        String finalTarget = targetIdentifier != null && !targetIdentifier.isBlank() ? targetIdentifier : "trx_10423";

        InteractiveCard card = new InteractiveCard();
        card.setActionId("act_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        card.setCardType("TROUBLESHOOT_ACTION");
        card.setTitle("智能运维应急止血与处置确认单");
        card.setDescription("基于多智能体故障研判，已自动生成止血预案。遵循 Human-in-the-loop 安全红线，请核验参数后一键执行：");

        card.getFields().add(new CardFormField(
                "service", "目标故障服务", "text", finalService, false, true
        ));
        card.getFields().add(new CardFormField(
                "action_type", "应急处置动作", "text", finalAction, false, true
        ));
        card.getFields().add(new CardFormField(
                "target_identifier", "慢查会话ID / 目标标识", "text", finalTarget, false, true
        ));
        card.getFields().add(new CardFormField(
                "max_pool_size", "临时连接池上限调整", "text", "80", true, false
        ));

        String defaultRemark = "针对 " + finalService + " 504 报警紧急止血，隔离慢查询 " + finalTarget + "，保障核心交易链路可用。";
        card.getFields().add(new CardFormField(
                "remark", "处置原因与影响评估", "textarea", defaultRemark, true, true
        ));

        card.setConfirmButtonText("确认执行应急处置");
        card.setCancelButtonText("取消/仅记录");
        return card;
    }
}
