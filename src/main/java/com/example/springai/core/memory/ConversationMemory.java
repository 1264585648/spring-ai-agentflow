package com.example.springai.core.memory;

import java.util.List;

/**
 * 会话短期记忆服务契约 (ConversationMemory)
 * 职责:
 * 1. 负责管理每个 sessionId 下多轮交互消息存储与滑动窗口淘汰;
 * 2. 供 QueryRewriter、SubAgent 及大模型上下文构建使用;
 * 3. 支持内存版开箱即用与未来分布式 Redis 扩展。
 */
public interface ConversationMemory {

    /**
     * 追加一条会话消息 (USER 或 ASSISTANT)
     *
     * @param sessionId 会话ID
     * @param message 消息对象
     */
    void append(String sessionId, ConversationMessage message);

    /**
     * 便捷追加一组问答交互 (USER + ASSISTANT)
     *
     * @param sessionId 会话ID
     * @param userQuery 用户提问
     * @param assistantResponse 助手回复
     */
    default void appendExchange(String sessionId, String userQuery, String assistantResponse) {
        if (userQuery != null && !userQuery.isBlank()) {
            append(sessionId, ConversationMessage.user(userQuery));
        }
        if (assistantResponse != null && !assistantResponse.isBlank()) {
            append(sessionId, ConversationMessage.assistant(assistantResponse));
        }
    }

    /**
     * 获取最近的 N 条消息 (按时间产生顺序正序排列)
     *
     * @param sessionId 会话ID
     * @param lastN 最大返回消息条数 (必须 > 0)
     * @return 消息列表快照 (按时间升序)
     */
    List<ConversationMessage> getRecentMessages(String sessionId, int lastN);

    /**
     * 获取最近的 N 轮问答消息 (1 轮 = 1 条 USER + 1 条 ASSISTANT，即 2 * lastNRounds 条消息)
     *
     * @param sessionId 会话ID
     * @param lastNRounds 最大返回问答轮数 (必须 > 0)
     * @return 消息列表快照 (按时间升序)
     */
    default List<ConversationMessage> getRecentRounds(String sessionId, int lastNRounds) {
        if (lastNRounds <= 0) {
            return java.util.Collections.emptyList();
        }
        return getRecentMessages(sessionId, lastNRounds * 2);
    }

    /**
     * 获取指定会话所有历史消息快照
     *
     * @param sessionId 会话ID
     * @return 全部消息快照列表
     */
    List<ConversationMessage> getAllMessages(String sessionId);

    /**
     * 清空指定会话的记忆
     *
     * @param sessionId 会话ID
     */
    void clear(String sessionId);

    /**
     * 获取当前会话存储的消息总数
     *
     * @param sessionId 会话ID
     * @return 消息总数
     */
    int size(String sessionId);
}
