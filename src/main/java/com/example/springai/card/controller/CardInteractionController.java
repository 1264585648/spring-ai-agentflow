package com.example.springai.card.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import com.example.springai.card.dto.CardSubmitRequest;
import com.example.springai.card.dto.CardSubmitResult;
import com.example.springai.card.spi.CardSubmitHandler;
import com.example.springai.execution.sse.SseEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Iterator;
import java.util.LinkedHashMap;
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
@Slf4j
@RequiredArgsConstructor
public class CardInteractionController {

    private final List<CardSubmitHandler> handlers;
    private final SseEventPublisher ssePublisher;

    private static final long IDEMPOTENCY_TTL_MS = 10 * 60 * 1000L;

    /**
     * actionId -> 过期时间戳。进程内防重，到期后允许再次提交。
     */
    private final Map<String, Long> idempotencyCache = new ConcurrentHashMap<>();

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submitCard(@RequestBody CardSubmitRequest request) {
        log.info("[CardController] 收到卡片确认提交请求, actionId: {}, cardType: {}",
                request.getActionId(), request.getCardType());

        if (!StringUtils.hasText(request.getActionId())) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "actionId 不能为空"));
        }

        if (!tryAcquire(request.getActionId())) {
            log.warn("[CardController] 检测到重复提交，已被幂等拦截, actionId: {}", request.getActionId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", 409, "message", "该卡片已提交处理，请勿重复操作"));
        }

        CardSubmitHandler matchedHandler = handlers.stream()
                .filter(h -> h.supports(request.getCardType()))
                .findFirst()
                .orElse(null);

        if (matchedHandler == null) {
            log.error("[CardController] 未找到支持该卡片类型的处理器: {}", request.getCardType());
            idempotencyCache.remove(request.getActionId());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "不支持的卡片类型: " + request.getCardType()));
        }

        CardSubmitResult result;
        try {
            result = matchedHandler.handleSubmit(request);
        } catch (Exception ex) {
            idempotencyCache.remove(request.getActionId());
            log.error("[CardController] 卡片提交处理异常, actionId: {}", request.getActionId(), ex);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "卡片提交失败"));
        }

        if (result == null || !result.isSuccess()) {
            idempotencyCache.remove(request.getActionId());
            String message = result != null && result.getMessage() != null ? result.getMessage() : "卡片提交失败";
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", message));
        }

        // 3. 若 SSE 会话保持中，主动向流追加一条确认提示
        if (request.getSessionId() != null) {
            ssePublisher.sendMessage(request.getSessionId(),
                    "\n\n> ✅ **表单已确认提交！**\n> 关联流程单号：`" + result.getTicketId() + "`\n");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", result.getMessage() != null ? result.getMessage() : "");
        body.put("ticketId", result.getTicketId() != null ? result.getTicketId() : "");
        body.put("cardType", request.getCardType() != null ? request.getCardType() : "");
        return ResponseEntity.ok(body);
    }

    private boolean tryAcquire(String actionId) {
        purgeExpired();
        long expireAt = System.currentTimeMillis() + IDEMPOTENCY_TTL_MS;
        while (true) {
            Long existing = idempotencyCache.putIfAbsent(actionId, expireAt);
            if (existing == null) {
                return true;
            }
            if (existing <= System.currentTimeMillis()
                    && idempotencyCache.replace(actionId, existing, expireAt)) {
                return true;
            }
            if (existing > System.currentTimeMillis()) {
                return false;
            }
        }
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = idempotencyCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() != null && entry.getValue() <= now) {
                iterator.remove();
            }
        }
    }
}
