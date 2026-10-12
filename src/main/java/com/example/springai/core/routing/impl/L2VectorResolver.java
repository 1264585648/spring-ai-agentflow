package com.example.springai.core.routing.impl;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.routing.IntentResolver;
import com.example.springai.core.routing.Resolution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 第二道防线: L2 向量检索意图解析器插槽 (L2VectorResolver)
 * 职责:
 * 1. 优先级次之 (@Order(200))，承接 L1 未命中的泛化语义诉求;
 * 2. 属于 M5 扩展里程碑规划能力 (Spring AI VectorStore + 专有 Embedding);
 * 3. 现阶段作为纯净标准可插拔扩展插槽 (Stub)，统一输出打桩日志并平滑放行至 L3;
 * 4. 后续接入向量库时，只需填充本类内部逻辑，整个流水线与外部契约 0 改动。
 */
@Component
@Slf4j
@Order(200)
public class L2VectorResolver implements IntentResolver {

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
        // 当前阶段向量知识库与 SOP 语料库暂未挂载，输出调试日志并放行
        log.debug("[L2VectorResolver] 向量检索知识库暂未挂载 (规划于 M5 阶段落地)，平滑放行至下一级");
        return Optional.empty();
    }
}
