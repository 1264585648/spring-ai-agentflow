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
}
