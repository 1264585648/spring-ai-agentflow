package com.example.springai.scenario.troubleshoot;

import com.example.springai.core.card.action.CardFormValues;
import com.example.springai.core.card.model.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** 展示参数与可执行参数使用同一 schema，并保留证据来源标记。 */
@Component
public class TroubleshootCardFactory {
    public InteractiveCard createTroubleshootCard(ActionProposal proposal) {
        if(proposal==null || !proposal.hasExecutableTarget()) throw new IllegalArgumentException("service、actionType、targetIdentifier 必须明确");
        var card=new InteractiveCard(); card.setActionId("act_"+UUID.randomUUID()); card.setCardType("TROUBLESHOOT_ACTION");
        card.setSimulation(proposal.isSimulationMode());
        card.setTitle("智能运维处置确认单"+(card.isSimulation()?" [演示模拟]":""));
        card.setDescription((card.isSimulation()?"【演示模拟数据】":"")+"以下只是待核验提案，提交前请核对目标与影响范围。");
        card.getFields().add(new CardFormField("service","目标服务","text",proposal.getService().trim(),false,true));
        card.getFields().add(new CardFormField("action_type","处置动作","text",proposal.getActionType().trim(),false,true));
        card.getFields().add(new CardFormField("target_identifier","目标标识","text",proposal.getTargetIdentifier().trim(),false,true));
        String suggested=proposal.getSuggestedMaxPoolSize();
        int size;
        try { size=suggested==null || suggested.isBlank()?80:Integer.parseInt(suggested.trim()); }
        catch(NumberFormatException ex) { throw new IllegalArgumentException("建议连接池上限必须为整数",ex); }
        if(size<10 || size>500) throw new IllegalArgumentException("建议连接池上限必须在 10~500 之间");
        var pool=new CardFormField("max_pool_size","临时连接池上限","number",Integer.valueOf(size),true,false);
        pool.setMinLimit(10); pool.setMaxLimit(500); card.getFields().add(pool);
        String remark=proposal.getEvidenceSummary();
        card.getFields().add(new CardFormField("remark","处置原因与影响评估","textarea",
                remark==null || remark.isBlank()?"请核验目标 "+proposal.getService()+" / "+proposal.getTargetIdentifier():remark,true,true));
        card.setConfirmButtonText(card.isSimulation()?"确认模拟处置":"确认执行处置"); card.setCancelButtonText("放弃");
        CardFormValues.validateAndMerge(CardRecord.builder().snapshot(card).build(),Map.of());
        return card;
    }
    public InteractiveCard createTroubleshootCard(String service,String actionType,String targetIdentifier) {
        return createTroubleshootCard(ActionProposal.builder().service(service).actionType(actionType).targetIdentifier(targetIdentifier).build());
    }
}
