package com.example.springai.core.context;

import com.example.springai.core.card.model.ActionProposal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多智能体协同步骤结构化执行结果契约 (StepResult)
 * 职责:
 * 1. 记录复合编排计划 (COMPOSITE) 中每个步骤的执行状态、结构化诊断指标、证据列表与错误分类；
 * 2. 支撑后续步骤显式引用前序步骤的结构化结论（如 DB 专家结合 Step 1 日志异常堆栈深入排查）；
 * 3. 承载当前步骤产出的可选操作草案 (ActionProposal)，供复合流程末端统一汇总生成确认卡片。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepResult {

    public enum Status {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    /**
     * 步骤序号 (从 1 开始)
     */
    private int stepOrder;

    /**
     * 执行该步骤的专家编码 (如 LOG_DIAGNOSE_AGENT, DB_DIAGNOSE_AGENT)
     */
    private String agentCode;

    /**
     * 步骤执行状态
     */
    @Builder.Default
    private Status status = Status.SUCCESS;

    /**
     * 步骤诊断结论摘要
     */
    private String summary;

    /**
     * 结构化诊断数据 (如 topExceptions, deepestStackTrace, poolUsageRatio, slowQuerySessionId)
     */
    @Builder.Default
    private Map<String, Object> structuredData = new LinkedHashMap<>();

    /**
     * 关键诊断证据条目
     */
    @Builder.Default
    private List<String> evidence = new ArrayList<>();

    /**
     * 错误分类 (如 MODEL_ERROR, PORT_ERROR, TARGET_MISSING, CANCELLED)
     */
    private String errorCategory;

    /**
     * 错误详情描述
     */
    private String errorMessage;

    /**
     * 本步骤提出的可选应急处置草案
     */
    private ActionProposal actionProposal;

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
