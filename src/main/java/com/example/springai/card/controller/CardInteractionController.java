package com.example.springai.card.controller;

import com.example.springai.card.dto.CardSubmitRequest;
import com.example.springai.card.dto.CardSubmitResult;
import com.example.springai.card.spi.CardSubmitHandler;
import com.example.springai.execution.sse.SseEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用交互卡片控制器
 * 职责: 幂等性防重 ➔ 路由到对应的 CardSubmitHandler SPI 实现 ➔ 异步追加会话状态
 */
@RestController
@RequestMapping("/api/v1/card")
@CrossOrigin(origins = "*")
public class CardInteractionController {

    private static final Logger log = LoggerFactory.getLogger(CardInteractionController.class);

    private final List<CardSubmitHandler> handlers;
    private final SseEventPublisher ssePublisher;

    // 内存幂等防重缓存 (生产建议使用 Redis 并设置过期时间)
    private final Map<String, Boolean> idempotencyCache = new ConcurrentHashMap<>();

    public CardInteractionController(List<CardSubmitHandler> handlers, SseEventPublisher ssePublisher) {
        this.handlers = handlers;
        this.ssePublisher = ssePublisher;
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submitCard(@RequestBody CardSubmitRequest request) {
        log.info("[CardController] 收到卡片确认提交请求, actionId: {}, cardType: {}",
                request.getActionId(), request.getCardType());

        if (request.getActionId() == null || request.getActionId().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "actionId 不能为空"));
        }

        // 1. 幂等性校验，防止重复点击提单
        if (idempotencyCache.putIfAbsent(request.getActionId(), Boolean.TRUE) != null) {
            log.warn("[CardController] 检测到重复提交，已被幂等拦截, actionId: {}", request.getActionId());
            return ResponseEntity.badRequest().body(Map.of("code", 409, "message", "该卡片已提交处理，请勿重复操作"));
        }

        // 2. 路由到对应的 SPI 处理器
        CardSubmitHandler matchedHandler = handlers.stream()
                .filter(h -> h.supports(request.getCardType()))
                .findFirst()
                .orElse(null);

        if (matchedHandler == null) {
            log.error("[CardController] 未找到支持该卡片类型的处理器: {}", request.getCardType());
            idempotencyCache.remove(request.getActionId()); // 失败允许重试
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "不支持的卡片类型: " + request.getCardType()));
        }

        CardSubmitResult result = matchedHandler.handleSubmit(request);

        if (!result.isSuccess()) {
            idempotencyCache.remove(request.getActionId());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", result.getMessage()));
        }

        // 3. 若 SSE 会话保持中，主动向流追加一条确认提示
        if (request.getSessionId() != null) {
            ssePublisher.sendMessage(request.getSessionId(),
                    "\n\n> ✅ **表单已确认提交！**\n> 关联流程单号：`" + result.getTicketId() + "`\n");
        }

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", result.getMessage(),
                "ticketId", result.getTicketId(),
                "cardType", request.getCardType()
        ));
    }
}
