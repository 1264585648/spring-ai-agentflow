package com.example.springai.core.event;

import com.example.springai.core.card.model.InteractiveCard;

import java.util.List;

/**
 * 智能体事件下行出口抽象 (AgentEventSink)
 * 职责:
 * 1. 屏蔽底层通信协议细节 (SSE, WebSocket, MQ)；
 * 2. 供内核与各 SubAgent 场景插件流式回传思考过程、执行进度、内容增量、交互卡片等；
 * 3. 方法返回值指示通道连接是否依然健康 (false 表示客户端已断开，应及时终止本回合)。
 */
public interface AgentEventSink {

    /**
     * 发送思考链内容
     */
    boolean thinking(String content);

    /**
     * 发送执行进度
     */
    boolean progress(String stage, String description);

    /**
     * 发送流式打字机消息增量
     */
    boolean message(String chunk);

    /**
     * 发送交互卡片 (Human-in-the-loop)
     */
    boolean card(InteractiveCard card);

    /**
     * 发送智能推荐追问
     */
    boolean recommend(List<String> questions);

    /**
     * 结束当前对话回合
     */
    boolean done();

    /**
     * 发送异常错误提示
     */
    boolean error(String message);
}
