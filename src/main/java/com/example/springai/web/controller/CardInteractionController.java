package com.example.springai.web.controller;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.CardNotFoundException;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.infra.sse.SseEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用交互卡片控制器 (CardInteractionController)
 * 职责:
 * 1. 状态机申领与防重防并发 (CardRegistry.claim, ISSUED ➔ SUBMITTING);
 * 2. 只读字段防篡改与合规审计 (CardRegistry.validate);
 * 3. 路由到对应的 CardSubmitHandler SPI 执行业务;
 * 4. 执行成功流转为终态并归档单号 (CardRegistry.complete);
 * 5. 向关联的 SSE 连接异步追加确认提示信息。
 */
@RestController
@RequestMapping("/api/v1/card")
@CrossOrigin(origins = "*")
@Slf4j
public class CardInteractionController {

    private final List<CardSubmitHandler> handlers;
    private final SseEventPublisher ssePublisher;
    private final CardRegistry cardRegistry;

    @Autowired
    public CardInteractionController(
            List<CardSubmitHandler> handlers,
            SseEventPublisher ssePublisher,
            CardRegistry cardRegistry) {
        this.handlers = handlers != null ? handlers : List.of();
        this.ssePublisher = ssePublisher;
        this.cardRegistry = cardRegistry != null ? cardRegistry : new InMemoryCardRegistry();
    }

    public CardInteractionController(
            List<CardSubmitHandler> handlers,
            SseEventPublisher ssePublisher) {
        this(handlers, ssePublisher, new InMemoryCardRegistry());
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submitCard(@RequestBody CardSubmitRequest request) {
        log.info("[CardController] 收到卡片确认提交请求, actionId: {}, sessionId: {}, cardType: {}",
                request.getActionId(), request.getSessionId(), request.getCardType());

        if (!StringUtils.hasText(request.getActionId())) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "actionId 不能为空"));
        }

        // 1. 状态机申领与会话归属校验 (ISSUED ➔ SUBMITTING)
        CardRecord record;
        try {
            record = cardRegistry.claim(request.getActionId(), request.getSessionId());
        } catch (CardNotFoundException ex) {
            log.warn("[CardController] 卡片未找到或已过期: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", 400, "message", ex.getMessage()));
        } catch (CardSecurityException ex) {
            log.error("[CardController] 🚨 越权操作被拦截: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", 403, "message", ex.getMessage()));
        } catch (CardStateConflictException ex) {
            log.warn("[CardController] 状态冲突被拦截: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", 409, "message", ex.getMessage()));
        }

        // 2. 只读字段防篡改与合规审计
        try {
            cardRegistry.validate(record, request.getFormValues());
        } catch (CardSecurityException ex) {
            log.error("[CardController] 🚨 关键只读参数被恶意篡改！actionId: {}, 原因: {}", request.getActionId(), ex.getMessage());
            cardRegistry.fail(request.getActionId(), ex.getMessage(), true);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", 400, "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            log.warn("[CardController] 参数校验失败: {}", ex.getMessage());
            cardRegistry.fail(request.getActionId(), ex.getMessage(), true);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", 400, "message", ex.getMessage()));
        }

        // 3. 匹配对应的 SPI 处理器
        CardSubmitHandler matchedHandler = handlers.stream()
                .filter(h -> h.supports(request.getCardType()))
                .findFirst()
                .orElse(null);

        if (matchedHandler == null) {
            log.error("[CardController] 未找到支持该卡片类型的处理器: {}", request.getCardType());
            cardRegistry.fail(request.getActionId(), "不支持的卡片类型: " + request.getCardType(), true);
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "不支持的卡片类型: " + request.getCardType()));
        }

        // 4. 执行业务逻辑
        CardSubmitResult result;
        try {
            result = matchedHandler.handleSubmit(request);
        } catch (Exception ex) {
            log.error("[CardController] 卡片提交处理异常, actionId: {}", request.getActionId(), ex);
            cardRegistry.fail(request.getActionId(), "卡片提交异常: " + ex.getMessage(), true);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "卡片提交失败"));
        }

        if (result == null || !result.isSuccess()) {
            String message = result != null && result.getMessage() != null ? result.getMessage() : "卡片提交失败";
            cardRegistry.fail(request.getActionId(), message, true);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", message));
        }

        // 5. 成功流转为终态 SUBMITTED 并归档单号
        cardRegistry.complete(request.getActionId(), result);

        // 6. 若关联的 SSE 会话保持中，主动向流追加一条确认提示
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
}
