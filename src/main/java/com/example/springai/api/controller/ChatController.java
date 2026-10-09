package com.example.springai.api.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import com.example.springai.api.dto.ChatRequest;
import com.example.springai.execution.sse.SseEmitterManager;
import com.example.springai.pipeline.AgentPipelineService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 接入层: 智能业务答疑对话接入控制器 (ChatController)
 * 职责: 鉴权校验 ➔ 建立 SSE 长连接 ➔ 接收提问 ➔ 触发 Pipeline 流水线
 */
@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final SseEmitterManager emitterManager;
    private final AgentPipelineService pipelineService;

    /**
     * 1. 建立 SSE 长连接通道
     * 前端进入工作台或打开问答窗口时调用
     */
    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestParam("sessionId") String sessionId) {
        log.info("[ChatController] 收到 SSE 连接请求, sessionId: {}", sessionId);
        return emitterManager.createEmitter(sessionId);
    }

    /**
     * 2. 发送提问接口 (标准前后端分离模式)
     * 客户端先建立 /connect，然后通过此接口投递问题
     */
    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@RequestBody ChatRequest request) {
        log.info("[ChatController] 收到用户提问, sessionId: {}, query: {}", 
                request.getSessionId(), request.getQuery());

        if (!StringUtils.hasText(request.getSessionId()) || !StringUtils.hasText(request.getQuery())) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "sessionId 或 query 不能为空"));
        }

        // 异步交给流水线编排服务处理 (流式事件通过 SseEmitter 回传)
        pipelineService.process(request);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "请求已提交处理",
                "sessionId", request.getSessionId()
        ));
    }

    /**
     * 3. 极速单路体验接口 (方便直接在浏览器或 Postman 中通过 GET /stream 快速调试)
     */
    @GetMapping(value = "/stream-ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAsk(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("query") String query,
            @RequestParam(value = "caseId", required = false) String caseId) {

        log.info("[ChatController] 收到单路体验请求, sessionId: {}, query: {}", sessionId, query);
        SseEmitter emitter = emitterManager.createEmitter(sessionId);

        ChatRequest request = new ChatRequest();
        request.setSessionId(sessionId);
        request.setQuery(query);
        request.setCaseId(caseId);

        pipelineService.process(request);

        return emitter;
    }
}
