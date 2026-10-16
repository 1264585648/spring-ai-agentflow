package com.example.springai.core.pipeline;

import com.example.springai.web.dto.ChatRequest;

/** 接收回合请求；输入 DTO 与传输适配的解耦属于后续架构阶段。 */
public interface AgentPipelineService {
    void process(ChatRequest request);
    /** Internal session-disconnect cancellation. */
    default boolean cancelActiveTurn(String sessionId) { return false; }
    /** Browser cancellation is conditional on the original turn identity. */
    default boolean cancelActiveTurn(String sessionId, String turnId) { return false; }
}
