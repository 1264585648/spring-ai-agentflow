package com.example.springai.pipeline.impl;

import com.example.springai.api.dto.ChatRequest;
import com.example.springai.card.model.CardFormField;
import com.example.springai.card.model.InteractiveCard;
import com.example.springai.execution.sse.SseEventPublisher;
import com.example.springai.pipeline.AgentPipelineService;
import com.example.springai.pipeline.intent.IntentMatchResult;
import com.example.springai.pipeline.intent.L1RuleMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 智能体流水线通用实现示例 (AgentPipelineServiceImpl)
 * 职责: 演示三级意图降级(L1)、思考链、进度推送、打字机流式输出、通用交互卡片 (InteractiveCard) 挂载与推荐问题
 */
@Service
public class AgentPipelineServiceImpl implements AgentPipelineService {

    private static final Logger log = LoggerFactory.getLogger(AgentPipelineServiceImpl.class);

    private final SseEventPublisher ssePublisher;
    private final L1RuleMatcher l1RuleMatcher;
    private final java.util.concurrent.Executor pipelineExecutor;

    public AgentPipelineServiceImpl(
            SseEventPublisher ssePublisher,
            L1RuleMatcher l1RuleMatcher,
            @org.springframework.beans.factory.annotation.Qualifier("agentPipelineExecutor") java.util.concurrent.Executor pipelineExecutor) {
        this.ssePublisher = ssePublisher;
        this.l1RuleMatcher = l1RuleMatcher;
        this.pipelineExecutor = pipelineExecutor;
    }

    @Override
    public void process(ChatRequest request) {
        String sessionId = request.getSessionId();
        String query = request.getQuery();
        log.info("[Pipeline] 启动处理会话: {}, 问题: {}", sessionId, query);

        // 异步执行流水线处理: 显式绑定至专用隔离线程池，杜绝抢占全局 ForkJoinPool
        CompletableFuture.runAsync(() -> {
            try {
                // 🚀 【L1 规则匹配前置拦截】: <30ms 极速检查高频指令或固定问答
                IntentMatchResult l1Result = l1RuleMatcher.match(query);
                if (l1Result.isMatched() && l1Result.getDirectReply() != null) {
                    log.info("[Pipeline] 命中 L1 规则直出，会话: {}, 意图: {}", sessionId, l1Result.getTargetIntent());
                    ssePublisher.sendProgress(sessionId, "L1_HIT", "已命中 L1 高频规则库，极速响应");

                    for (char c : l1Result.getDirectReply().toCharArray()) {
                        ssePublisher.sendMessage(sessionId, String.valueOf(c));
                        Thread.sleep(10);
                    }
                    ssePublisher.sendDone(sessionId);
                    return; // 直接返回，彻底避免进入大模型推理！
                }

                // 若未命中 L1，平滑放行至大模型分析与业务流程
                // 1. 发送思考事件 (thinking) - 演示推理链
                ssePublisher.sendThinking(sessionId, "未命中 L1 高频规则，正在深入分析语义并评估业务方案...");
                Thread.sleep(500);

                // 2. 发送业务进度 (progress) - 演示工具调用通知
                ssePublisher.sendProgress(sessionId, "POLICY_CHECK", "已核准企业预算政策：符合高优先级资源审批绿色通道标准");
                Thread.sleep(400);

                // 3. 模拟打字机流式输出回答 (message)
                String responseText = "您好！已为您完成申请政策核验。\n"
                        + "根据当前企业资源调度规范，您的申请已自动匹配最佳审批链路，并预填了基础参数。\n"
                        + "请在下方卡片中核验预填方案，您可微调规格或说明，确认无误后点击一键提交：";

                for (char c : responseText.toCharArray()) {
                    ssePublisher.sendMessage(sessionId, String.valueOf(c));
                    Thread.sleep(25);
                }

                // 4. 发送通用交互确认卡片 (interactive_card) - 核心 Human-in-the-loop 演示
                InteractiveCard card = buildDemoCard(request);
                ssePublisher.sendInteractiveCard(sessionId, card);
                Thread.sleep(300);

                // 5. 异步后处理：发送推荐追问 (recommend_questions)
                ssePublisher.sendRecommendQuestions(sessionId, List.of(
                        "审批通过后大约多长时间生效？",
                        "如何查看我的历史申请记录？",
                        "如何修改已提交的表单信息？"
                ));

                // 6. 发送结束事件 (done)
                ssePublisher.sendDone(sessionId);
                log.info("[Pipeline] 流水线执行完成, sessionId: {}", sessionId);

            } catch (Exception ex) {
                log.error("[Pipeline] 流水线执行异常, sessionId: {}", sessionId, ex);
                ssePublisher.sendError(sessionId, "流水线执行异常: " + ex.getMessage());
            }
        }, pipelineExecutor);
    }

    private InteractiveCard buildDemoCard(ChatRequest request) {
        InteractiveCard card = new InteractiveCard();
        card.setActionId("act_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        card.setCardType("COMMON_RESOURCE_APPLY");
        card.setTitle("业务方案确认与流转申请单");
        card.setDescription("系统已完成前置政策核验，以下为大模型推荐配置。关键标识已锁定，微调参数支持人工编辑。");

        // 只读字段: 系统锁定
        card.getFields().add(new CardFormField(
                "request_id", "申请流水号", "text", "REQ_" + System.currentTimeMillis() % 100000, false, true
        ));
        card.getFields().add(new CardFormField(
                "priority_level", "流转优先级", "text", "高优先级 (绿色通道)", false, true
        ));

        // 可编辑字段: 人工微调
        CardFormField quotaField = new CardFormField(
                "apply_quota", "申请额度 / 数量", "number", 500, true, true
        );
        quotaField.setMaxLimit(1000);
        card.getFields().add(quotaField);

        card.getFields().add(new CardFormField(
                "remark", "申请说明与依据", "textarea", "基于系统推荐方案快速发起，符合标准管理要求。", true, true
        ));

        card.setConfirmButtonText("确认并提交审批");
        card.setCancelButtonText("放弃");
        return card;
    }
}
