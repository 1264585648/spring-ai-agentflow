package com.example.springai.web.controller;

import com.example.springai.core.event.SseEventType;
import com.example.springai.core.event.SsePacket;
import com.example.springai.core.pipeline.AgentPipelineService;
import com.example.springai.infra.sse.SseEmitterManager;
import com.example.springai.web.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/** HTTP/SSE 接入；排队拒绝返回 503，不再将拒绝伪装为已接收。 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {
    private final SseEmitterManager emitterManager;
    private final AgentPipelineService pipelineService;
    @GetMapping(value="/connect",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestParam("sessionId") String sessionId) { return emitterManager.createEmitter(sessionId); }
    @PostMapping("/ask")
    public ResponseEntity<Map<String,Object>> ask(@RequestBody ChatRequest request) {
        if(request==null || blank(request.getSessionId()) || blank(request.getQuery())) return failure(400,"sessionId 或 query 不能为空");
        if(!emitterManager.isConnected(request.getSessionId())) return failure(409,"SSE_NOT_READY: 请先建立实时通道");
        if(blank(request.getTurnId())) request.setTurnId(UUID.randomUUID().toString());
        try { pipelineService.process(request); }
        catch(RejectedExecutionException ex) { return failure(503,"QUEUE_SATURATED: 系统繁忙，请稍后重试"); }
        catch(IllegalArgumentException ex) { return failure(400,ex.getMessage()); }
        return ResponseEntity.ok(Map.of("code",200,"message","请求已提交处理","sessionId",request.getSessionId(),"turnId",request.getTurnId()));
    }
    @PostMapping("/cancel")
    public ResponseEntity<Map<String,Object>> cancel(@RequestParam("sessionId") String sessionId) {
        return ResponseEntity.ok(Map.of("code",200,"cancelled",pipelineService.cancelActiveTurn(sessionId)));
    }
    @GetMapping(value="/stream-ask",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAsk(@RequestParam("sessionId") String sessionId,@RequestParam("query") String query,
            @RequestParam(value="caseId",required=false) String caseId,@RequestParam(value="turnId",required=false) String turnId) {
        var emitter=emitterManager.createEmitter(sessionId); var request=new ChatRequest();
        request.setSessionId(sessionId); request.setQuery(query); request.setCaseId(caseId);
        request.setTurnId(blank(turnId)?UUID.randomUUID().toString():turnId);
        try { pipelineService.process(request); }
        catch(RuntimeException ex) {
            emitterManager.send(sessionId,SsePacket.of(SseEventType.ERROR,Map.of("turnId",request.getTurnId(),"message","请求未被接收")));
            emitterManager.complete(sessionId);
        }
        return emitter;
    }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static ResponseEntity<Map<String,Object>> failure(int code,String message) { return ResponseEntity.status(code).body(Map.of("code",code,"message",message)); }
}
