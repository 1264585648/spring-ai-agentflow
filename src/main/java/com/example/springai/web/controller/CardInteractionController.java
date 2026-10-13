package com.example.springai.web.controller;

import com.example.springai.core.card.action.ActionGate;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.exception.CardExecutionUnknownException;
import com.example.springai.core.card.exception.CardNotFoundException;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.infra.sse.SseEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP 适配器：所有审批/执行都委托 ActionGate，Principal 由服务端认证上下文提供。 */
@RestController
@RequestMapping("/api/v1/card")
@RequiredArgsConstructor
@Slf4j
public class CardInteractionController {
    private final ActionGate actionGate;
    private final SseEventPublisher ssePublisher;

    @PostMapping(value = "/submit", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> submitCard(@RequestBody CardSubmitRequest request, Principal principal) {
        ActionGate.SubmissionResult completed;
        try {
            completed = actionGate.submit(request, principal);
        } catch (CardNotFoundException ex) {
            return failure(400, ex.getMessage(), "CARD_NOT_FOUND", false);
        } catch (CardSecurityException ex) {
            return failure(403, ex.getMessage(), "CARD_FORBIDDEN", false);
        } catch (CardStateConflictException ex) {
            return failure(409, ex.getMessage(), "CARD_STATE_CONFLICT", false);
        } catch (IllegalArgumentException ex) {
            return failure(400, ex.getMessage(), "INVALID_SUBMISSION", true);
        } catch (CardExecutionUnknownException ex) {
            return failure(500, ex.getMessage(), "EXECUTION_UNKNOWN", false);
        } catch (RuntimeException ex) {
            log.error("[CardController] 提交处理异常", ex);
            return failure(500, "提交未完成，请核查动作状态", "SUBMISSION_ERROR", false);
        }
        // 执行结果已归档：通知失败不改变成功状态，也不让前端因 500 重复提交。
        try {
            boolean sent = ssePublisher.sendMessage(completed.getSessionId(),
                    "\n\n> 表单已确认提交，关联单号：`" + completed.getResult().getTicketId() + "`\n");
            if (!sent) {
                log.warn("[CardController] 结果已归档但 SSE 未送达 actionId={}", completed.getActionId());
            }
        } catch (RuntimeException ex) {
            log.warn("[CardController] 结果已归档但 SSE 通知失败 actionId={}", completed.getActionId(), ex);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", completed.getResult().getMessage() == null ? "" : completed.getResult().getMessage());
        body.put("ticketId", completed.getResult().getTicketId() == null ? "" : completed.getResult().getTicketId());
        body.put("cardType", completed.getCardType());
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> failure(int status, String message, String errorType,
                                                              boolean retryable) {
        return ResponseEntity.status(status).body(Map.of("code", status, "message", message == null ? "提交被拒绝" : message,
                "errorType", errorType, "retryable", retryable));
    }
}
