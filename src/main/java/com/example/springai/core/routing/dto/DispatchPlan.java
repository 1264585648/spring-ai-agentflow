package com.example.springai.core.routing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MasterAgent 调度决策计划契约 DTO (DispatchPlan)
 * 严格遵循工程规范 RULE-01 (使用 Lombok @Data)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchPlan {

    /**
     * 计划类型 (SINGLE / COMPOSITE / FALLBACK)
     */
    private PlanType planType;

    /**
     * 调度研判原因与思路说明
     */
    private String reason;

    /**
     * 具体的执行步骤序列
     */
    @Builder.Default
    private List<DispatchStep> steps = new ArrayList<>();

    // =========================================================================
    // 便捷静态工厂构建方法
    // =========================================================================

    /**
     * 创建单意图直通计划
     */
    public static DispatchPlan single(String targetAgent, String targetAgentName, String taskDesc, Map<String, Object> params) {
        DispatchStep step = DispatchStep.builder()
                .stepOrder(1)
                .targetAgent(targetAgent)
                .targetAgentName(targetAgentName)
                .taskDesc(taskDesc)
                .inputParams(params)
                .build();

        return DispatchPlan.builder()
                .planType(PlanType.SINGLE)
                .reason("命中单意图专家 [" + targetAgentName + "]，直通派发执行")
                .steps(List.of(step))
                .build();
    }

    /**
     * 创建单意图直通计划 (无额外参数)
     */
    public static DispatchPlan single(String targetAgent, String targetAgentName, String taskDesc) {
        return single(targetAgent, targetAgentName, taskDesc, Collections.emptyMap());
    }

    /**
     * 创建多意图复合编排计划
     */
    public static DispatchPlan composite(String reason, List<DispatchStep> steps) {
        return DispatchPlan.builder()
                .planType(PlanType.COMPOSITE)
                .reason(reason)
                .steps(steps != null ? steps : Collections.emptyList())
                .build();
    }

    /**
     * 创建兜底/降级计划
     */
    public static DispatchPlan fallback(String reason) {
        return DispatchPlan.builder()
                .planType(PlanType.FALLBACK)
                .reason(reason)
                .steps(Collections.emptyList())
                .build();
    }
}
