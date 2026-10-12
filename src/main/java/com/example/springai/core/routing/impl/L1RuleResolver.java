package com.example.springai.core.routing.impl;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.routing.IntentMatchResult;
import com.example.springai.core.routing.IntentResolver;
import com.example.springai.core.routing.L1RuleMatcher;
import com.example.springai.core.routing.Resolution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 第一道防线: L1 规则意图解析器 (L1RuleResolver)
 * 职责:
 * 1. 优先级最高 (@Order(100))，首当其冲拦截高频固定指令;
 * 2. 委托底层 L1RuleMatcher 执行内存快照无锁正则/前缀匹配，全流程 < 1ms;
 * 3. 命中 STATIC_TEXT 转换为 Resolution.DirectReply;
 * 4. 命中 TOOL 转换为 Resolution.ToolCall;
 * 5. 未命中则放行 (Optional.empty())，下沉至 L2 向量层。
 */
@Component
@Slf4j
@Order(100)
@RequiredArgsConstructor
public class L1RuleResolver implements IntentResolver {

    private final L1RuleMatcher l1RuleMatcher;

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public String level() {
        return "L1";
    }

    @Override
    public Optional<Resolution> resolve(AgentContext context) {
        if (context == null) {
            return Optional.empty();
        }

        String query = StringUtils.hasText(context.getEffectiveQuery())
                ? context.getEffectiveQuery()
                : context.getQuery();

        if (!StringUtils.hasText(query)) {
            return Optional.empty();
        }

        IntentMatchResult result = l1RuleMatcher.match(query);
        if (result == null || !result.isMatched()) {
            return Optional.empty();
        }

        String targetType = result.getTargetType();
        if ("STATIC_TEXT".equalsIgnoreCase(targetType)) {
            log.info("[L1RuleResolver] ⚡ 命中 L1 静态指令直出, ruleCode={}, query={}", result.getRuleCode(), query);
            return Optional.of(Resolution.directReply("L1", result.getRuleCode(), result.getDirectReply()));
        } else if ("TOOL".equalsIgnoreCase(targetType)) {
            log.info("[L1RuleResolver] ⚡ 命中 L1 命令直通工具调度, ruleCode={}, targetRef={}", result.getRuleCode(), result.getTargetRef());
            return Optional.of(Resolution.toolCall("L1", result.getRuleCode(), result.getTargetRef(), result.getExtractedParams()));
        } else {
            log.warn("[L1RuleResolver] 未知的 L1 targetType: {}, 忽略并放行", targetType);
            return Optional.empty();
        }
    }
}
