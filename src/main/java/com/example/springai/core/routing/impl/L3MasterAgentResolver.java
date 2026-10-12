package com.example.springai.core.routing.impl;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.routing.IntentResolver;
import com.example.springai.core.routing.MasterAgentRouter;
import com.example.springai.core.routing.Resolution;
import com.example.springai.core.routing.dto.DispatchPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 第三道防线: L3 调度中枢与大模型意图解析器 (L3MasterAgentResolver)
 * 职责:
 * 1. 作为意图降级责任链的最终防线 (@Order(300));
 * 2. 委托 MasterAgentRouter 执行自然语言意图研判、单专家直通与复合多专家任务依赖分解;
 * 3. 将调度计划契约 (DispatchPlan) 统一包装转换为标准 Resolution.Route 决议;
 * 4. 作为终点兜底，确保永远给出确定的执行路线。
 */
@Component
@Slf4j
@Order(300)
@RequiredArgsConstructor
public class L3MasterAgentResolver implements IntentResolver {

    private final MasterAgentRouter masterAgentRouter;

    @Override
    public int getOrder() {
        return 300;
    }

    @Override
    public String level() {
        return "L3";
    }

    @Override
    public Optional<Resolution> resolve(AgentContext context) {
        if (context == null) {
            return Optional.of(Resolution.route("L3", DispatchPlan.fallback("会话上下文为空")));
        }

        String query = StringUtils.hasText(context.getEffectiveQuery())
                ? context.getEffectiveQuery()
                : context.getQuery();

        log.info("[L3MasterAgentResolver] 🧠 接管未命中 L1/L2 的复杂意图研判与规划, query={}", query);
        DispatchPlan plan = masterAgentRouter.route(query);

        return Optional.of(Resolution.route("L3", plan));
    }
}
