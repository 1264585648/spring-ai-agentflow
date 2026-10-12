package com.example.springai.core.routing;

import com.example.springai.core.routing.dto.DispatchPlan;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.routing.dto.PlanType;

import java.util.Collections;
import java.util.List;

/**
 * 意图解析执行决议统一契约 (Resolution)
 * 基于 Java 21 密封接口 (Sealed Interface) 与 Record 建模。
 * 规范系统面对用户输入经过识别后，在控制流层面仅有的三种标准物理动作：
 * 1. DirectReply: 纯文本直出 (只读数据答复，无写副作用)
 * 2. ToolCall: 命令工具直通 (单次确定性函数调用，零 LLM 参与)
 * 3. Route: 智能体路由编排 (交由 SubAgent 业务专家或通用 Agent 协同)
 */
public sealed interface Resolution {

    /**
     * 产出该决议的层级标识 ("L1", "L2", "L3" 等)
     */
    String level();

    /**
     * 1. 纯文本直出决议 (L1 静态指令、L2 权威 FAQ 直出)
     */
    record DirectReply(
            String level,
            String ruleCode,
            String text
    ) implements Resolution {}

    /**
     * 2. 命令工具直通决议 (L1 精准命令直接反射执行 Spring @Tool)
     */
    record ToolCall(
            String level,
            String ruleCode,
            String toolRef,
            String jsonArgs
    ) implements Resolution {}

    /**
     * 3. 智能体路由编排决议 (MasterAgent 任务研判分解、单专家直通、复合多专家协同、通用兜底)
     */
    record Route(
            String level,
            PlanType planType,
            List<DispatchStep> steps,
            String reason
    ) implements Resolution {
        public Route {
            steps = steps != null ? List.copyOf(steps) : Collections.emptyList();
        }
    }

    // =========================================================================
    // 便捷静态构建工厂方法
    // =========================================================================

    static DirectReply directReply(String level, String ruleCode, String text) {
        return new DirectReply(level, ruleCode, text);
    }

    static ToolCall toolCall(String level, String ruleCode, String toolRef, String jsonArgs) {
        return new ToolCall(level, ruleCode, toolRef, jsonArgs);
    }

    static Route route(String level, PlanType planType, List<DispatchStep> steps, String reason) {
        return new Route(level, planType, steps, reason);
    }

    static Route route(String level, DispatchPlan plan) {
        if (plan == null) {
            return new Route(level, PlanType.FALLBACK, Collections.emptyList(), "调度计划为空");
        }
        return new Route(level, plan.getPlanType(), plan.getSteps(), plan.getReason());
    }
}
