package com.example.springai.core.routing.impl;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.routing.IntentResolver;
import com.example.springai.core.routing.L2VectorRegistry;
import com.example.springai.core.routing.Resolution;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.routing.dto.PlanType;
import com.example.springai.core.routing.vector.FaqItem;
import com.example.springai.core.routing.vector.VectorCandidate;
import com.example.springai.core.routing.vector.VectorEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 第二道防线: L2 向量检索意图解析器 (L2VectorResolver)
 * 职责:
 * 1. 优先级次之 (@Order(200))，承接 L1 未命中的泛化口语化诉求;
 * 2. 借助 L2VectorRegistry 纯堆内无锁点积检索，耗时 < 5ms;
 * 3. 落实双阈值漏斗模型 (Dual-Threshold Engine):
 *    - Score >= 0.88 (高置信度): 彻底短路直出权威标准答案 (Resolution.DirectReply)，零 LLM 消耗;
 *    - 0.70 <= Score < 0.88 (中置信度): 知识增强路由至业务专家协同 (Resolution.Route);
 *    - Score < 0.70 (低置信度): 平滑放行 (Optional.empty())，下沉至 L3 MasterAgent 兜底。
 */
@Component
@Slf4j
@Order(200)
public class L2VectorResolver implements IntentResolver {

    public static final double HIGH_CONFIDENCE_THRESHOLD = 0.88;
    public static final double MEDIUM_CONFIDENCE_THRESHOLD = 0.70;

    private final L2VectorRegistry vectorRegistry;
    private final VectorEmbeddingService embeddingService;

    @Autowired
    public L2VectorResolver(L2VectorRegistry vectorRegistry, VectorEmbeddingService embeddingService) {
        this.vectorRegistry = vectorRegistry;
        this.embeddingService = embeddingService;
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public String level() {
        return "L2";
    }

    @Override
    public Optional<Resolution> resolve(AgentContext context) {
        if (context == null || vectorRegistry == null || embeddingService == null) {
            return Optional.empty();
        }

        String query = StringUtils.hasText(context.getEffectiveQuery())
                ? context.getEffectiveQuery()
                : context.getQuery();

        if (!StringUtils.hasText(query)) {
            return Optional.empty();
        }

        // 如果当前本地向量知识库为空，直接放行至 L3
        if (vectorRegistry.getActiveCount() == 0) {
            log.debug("[L2VectorResolver] 本地向量库为空，平滑放行至 L3");
            return Optional.empty();
        }

        long startTime = System.currentTimeMillis();
        float[] queryVector;
        try {
            queryVector = embeddingService.embed(query);
        } catch (Exception ex) {
            log.warn("[L2VectorResolver] Embedding 计算异常，跳过 L2 检索并平滑放行至 L3: {}", ex.getMessage());
            return Optional.empty();
        }
        if (queryVector == null || queryVector.length == 0) {
            return Optional.empty();
        }

        String queryModel = embeddingService.modelName();

        // 1. 本地内存快照检索 Top-1 (初筛门槛 0.70，强制校验同模型空间)
        Optional<VectorCandidate> matchOpt = StringUtils.hasText(queryModel)
                ? vectorRegistry.searchTop1(queryVector, MEDIUM_CONFIDENCE_THRESHOLD, queryModel)
                : vectorRegistry.searchTop1(queryVector, MEDIUM_CONFIDENCE_THRESHOLD);
        if (matchOpt.isEmpty()) {
            log.debug("[L2VectorResolver] 未命中达到中置信度阈值的 FAQ，耗时: {}ms，放行至 L3",
                    System.currentTimeMillis() - startTime);
            return Optional.empty();
        }

        VectorCandidate candidate = matchOpt.get();
        FaqItem faq = candidate.item();
        if (StringUtils.hasText(queryModel) && StringUtils.hasText(faq.embeddingModel())
                && !queryModel.trim().equalsIgnoreCase(faq.embeddingModel().trim())) {
            log.warn("[L2VectorResolver] 拦截跨模型空间匹配: queryModel={}, faqModel={}, faqCode={}",
                    queryModel, faq.embeddingModel(), faq.faqCode());
            return Optional.empty();
        }
        double score = candidate.score();
        long cost = System.currentTimeMillis() - startTime;

        // 2. 高置信度 (>= 0.88): 极速短路直出标准答案，零模型调用
        if (score >= HIGH_CONFIDENCE_THRESHOLD) {
            log.info("[L2VectorResolver] 🎯 命中高置信度 FAQ 直出! code={}, score={}, cost={}ms, query={}",
                    faq.faqCode(), score, cost, query);
            vectorRegistry.recordHitCountAsync(faq.faqCode());
            return Optional.of(Resolution.directReply("L2", faq.faqCode(), faq.standardAnswer()));
        }

        // 3. 中置信度 (0.70 <= score < 0.88): 知识增强透传给业务专家
        log.info("[L2VectorResolver] 🔍 命中中置信度知识提示, code={}, score={}, cost={}ms, query={}",
                faq.faqCode(), score, cost, query);

        if (context.getAttributes() != null) {
            context.getAttributes().put("L2_HINT_ANSWER", faq.standardAnswer());
            context.getAttributes().put("L2_HINT_FAQ_CODE", faq.faqCode());
        }

        String targetAgent = StringUtils.hasText(faq.targetAgentCode())
                ? faq.targetAgentCode()
                : "GENERAL_CHAT_AGENT";

        String taskDesc = "参考已知标准知识解答: " + faq.faqTitle();
        DispatchStep step = DispatchStep.builder()
                .stepOrder(1)
                .targetAgent(targetAgent)
                .targetAgentName(targetAgent)
                .taskDesc(taskDesc)
                .inputParams(Map.of("faqCode", faq.faqCode()))
                .build();

        return Optional.of(Resolution.route("L2", PlanType.SINGLE, List.of(step), "参考已知 FAQ: " + faq.faqTitle()));
    }
}
