package com.example.springai.core.event;

/**
 * SSE 事件流协议类型枚举
 * 严格对应系统架构图中定义的前后端下行事件体系
 */
public enum SseEventType {

    /**
     * 思考过程 (对应架构图中 SubAgent 思考链输出，前端通常折叠显示)
     */
    THINKING("thinking"),

    /**
     * 正式文本流块 (对应打字机输出)
     */
    MESSAGE("message"),

    /**
     * 进度通知 (如: 正在查询服务详情、正在调用方案试算等)
     */
    PROGRESS("progress"),

    /**
     * 通用人机协同方案交互确认卡片 (Human-in-the-loop 核心交互事件)
     */
    INTERACTIVE_CARD("interactive_card"),

    /**
     * 下一步推荐提问 (异步后处理推荐问题)
     */
    RECOMMEND_QUESTIONS("recommend_questions"),

    /**
     * 会话摘要标题 (异步后处理标题生成)
     */
    CONVERSATION_TITLE("conversation_title"),

    /**
     * 流结束事件 (通知前端正常结束当前交互流)
     */
    DONE("done"),

    /**
     * 异常错误事件
     */
    ERROR("error");

    private final String value;

    SseEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
