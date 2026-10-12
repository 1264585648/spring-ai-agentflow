package com.example.springai.core.routing;

import com.example.springai.core.context.AgentContext;
import org.springframework.core.Ordered;

import java.util.Optional;

/**
 * 意图解析器统一 SPI (IntentResolver)
 * 职责:
 * 按 getOrder() 升序形成责任链，逐层执行意图研判与降级。
 * 若命中则返回 Optional.of(Resolution) 终止后续链路；未命中返回 Optional.empty() 放行至下一级。
 */
public interface IntentResolver extends Ordered {

    /**
     * 标识当前解析器所属层级 ("L1", "L2", "L3" 等)
     */
    String level();

    /**
     * 执行意图解析
     *
     * @param context 会话上下文
     * @return 解析决议结果; Optional.empty() 表示本层未命中，由责任链顺延降级至下一层
     */
    Optional<Resolution> resolve(AgentContext context);
}
