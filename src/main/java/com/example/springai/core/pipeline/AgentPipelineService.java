package com.example.springai.core.pipeline;

import com.example.springai.web.dto.ChatRequest;

/** 接收回合请求；输入 DTO 与传输适配的解耦属于后续架构阶段。 */
public interface AgentPipelineService {
    void process(ChatRequest request);
    default boolean cancelActiveTurn(String sessionId) { return false; }
}
