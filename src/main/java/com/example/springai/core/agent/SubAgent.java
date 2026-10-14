package com.example.springai.core.agent;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.context.StepResult;
import com.example.springai.core.routing.dto.DispatchStep;

import java.util.List;

/**
 * 专业智能体行为插槽 SPI (SubAgent)
 * 职责:
 * 1. 负责具体业务领域/场景的推理、工具调用、大模型交互与卡片下发；
 * 2. 与系统微内核 core.pipeline 解耦，作为插件注册到 Spring 容器中；
 * 3. name() 与 sys_agent_definition.agent_code 对应。
 */
public interface SubAgent {

    /**
     * 智能体主要标识编码 (对应 sys_agent_definition.agent_code)
     */
    String name();

    /**
     * 判断当前 SubAgent 是否支持处理指定的智能体编码
     *
     * @param agentCode 目标智能体编码
     * @return true 表示支持处理
     */
    default boolean supports(String agentCode) {
        return name() != null && name().equalsIgnoreCase(agentCode);
    }

    /**
     * 执行智能体推理与业务行为
     *
     * @param context 会话上下文与事件出口
     * @param step 调度步骤详情与输入参数
     */
    void execute(AgentContext context, DispatchStep step);

    /**
     * 执行单步推理并返回结构化步骤结果 (供多步复合编排传递上下文与失败控制)
     */
    default StepResult executeStep(AgentContext context, DispatchStep step) {
        execute(context, step);
        int order = step != null ? step.getStepOrder() : 1;
        String code = step != null && step.getTargetAgent() != null ? step.getTargetAgent() : name();
        if (context != null && context.getStepResults() != null) {
            List<StepResult> results = context.getStepResults();
            for (int i = results.size() - 1; i >= 0; i--) {
                StepResult r = results.get(i);
                if (r != null && r.getStepOrder() == order) {
                    return r;
                }
            }
        }
        StepResult fallbackResult = StepResult.builder()
                .stepOrder(order)
                .agentCode(code)
                .status(context != null && context.isCancelled() ? StepResult.Status.FAILED : StepResult.Status.SUCCESS)
                .summary("步骤执行完成")
                .build();
        if (context != null) {
            context.addStepResult(fallbackResult);
        }
        return fallbackResult;
    }
}
