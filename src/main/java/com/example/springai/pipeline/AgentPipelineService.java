package com.example.springai.pipeline;

import com.example.springai.api.dto.ChatRequest;

/**
 * 智能体流水线主服务接口
 * 负责串联: 上下文准备 ➔ 意图分级识别 ➔ 调度决策 ➔ 智能体执行 ➔ 异步后处理
 */
public interface AgentPipelineService {

    /**
     * 异步驱动智能体流水线
     *
     * @param request 问答入参
     */
    void process(ChatRequest request);
}
