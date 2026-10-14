package com.example.springai.core.context;

import com.example.springai.core.event.AgentEventSink;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 智能体单回合执行上下文 (AgentContext)
 * 职责:
 * 贯穿整条流水线与各 SubAgent 场景插件，承载会话元数据、用户问题与专属事件出口。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {

    /**
     * 会话唯一标识
     */
    private String sessionId;

    /**
     * 当前对话回合唯一标识 (TurnId)
     */
    private String turnId;

    /**
     * 用户标识 (可选)
     */
    private String userId;

    /**
     * 用户输入的原始问题
     */
    private String query;

    /**
     * 经预处理或意图重写后的有效提问文本
     */
    private String effectiveQuery;

    /**
     * 上下文透传属性字典 (如环境参数、服务标识、traceId 等)
     */
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    /**
     * 当前会话专属的事件输出出口
     */
    private AgentEventSink sink;

    /**
     * 当前会话最近的历史问答记录快照
     */
    @Builder.Default
    private java.util.List<com.example.springai.core.memory.ConversationMessage> history = new java.util.ArrayList<>();

    /**
     * 复合编排计划中已完成步骤的结构化结果列表
     */
    @Builder.Default
    private java.util.List<StepResult> stepResults = new java.util.ArrayList<>();

    /**
     * 当前调度计划总步骤数 (默认 1)
     */
    @Builder.Default
    private int totalSteps = 1;

    /**
     * 回合取消信号 (客户端断连、主动取消或前置步骤致命失败时置为 true)
     */
    @Builder.Default
    private volatile boolean cancelled = false;

    /**
     * 当前回合执行截止时间戳 (毫秒，0 表示未限制)
     */
    @Builder.Default
    private long deadlineMillis = 0L;

    public AgentContext(
            String sessionId,
            String turnId,
            String userId,
            String query,
            String effectiveQuery,
            Map<String, Object> attributes,
            AgentEventSink sink,
            java.util.List<com.example.springai.core.memory.ConversationMessage> history) {
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.userId = userId;
        this.query = query;
        this.effectiveQuery = effectiveQuery;
        this.attributes = attributes != null ? attributes : new HashMap<>();
        this.sink = sink;
        this.history = history != null ? history : new java.util.ArrayList<>();
        this.stepResults = new java.util.ArrayList<>();
        this.totalSteps = 1;
        this.cancelled = false;
        this.deadlineMillis = 0L;
    }

    /**
     * 存取属性便捷方法
     */
    public void setAttribute(String key, Object value) {
        if (this.attributes == null) {
            this.attributes = new HashMap<>();
        }
        this.attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return this.attributes != null ? this.attributes.get(key) : null;
    }

    public void addStepResult(StepResult result) {
        if (result == null) {
            return;
        }
        if (this.stepResults == null) {
            this.stepResults = new java.util.ArrayList<>();
        }
        this.stepResults.add(result);
    }

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isExpired(long nowMillis) {
        return deadlineMillis > 0 && nowMillis > deadlineMillis;
    }

    public boolean isCancelledOrExpired(long nowMillis) {
        return cancelled || isExpired(nowMillis);
    }

    public boolean hasFailedStep() {
        if (this.stepResults == null || this.stepResults.isEmpty()) {
            return false;
        }
        return this.stepResults.stream()
                .anyMatch(r -> r != null && r.getStatus() == StepResult.Status.FAILED);
    }
}
