package com.example.springai.core.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 会话历史消息模型 (ConversationMessage)
 * 职责:
 * 承载单次回话消息角色 (USER / ASSISTANT / SYSTEM)、提问/回答内容快照与元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {

    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    /**
     * 消息所属角色
     */
    private Role role;

    /**
     * 消息具体文本内容
     */
    private String content;

    /**
     * 消息产生时间戳 (毫秒)
     */
    private long timestamp;

    /**
     * 可选扩展元数据 (如回合 turnId、单号 ticketId 等)
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    public static ConversationMessage user(String content) {
        return ConversationMessage.builder()
                .role(Role.USER)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .metadata(new HashMap<>())
                .build();
    }

    public static ConversationMessage assistant(String content) {
        return ConversationMessage.builder()
                .role(Role.ASSISTANT)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .metadata(new HashMap<>())
                .build();
    }
}
