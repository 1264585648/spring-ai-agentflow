package com.example.springai.web.controller;

import com.example.springai.core.card.action.ActionGate;
import com.example.springai.core.card.dto.CardCancelRequest;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.exception.*;
import com.example.springai.infra.sse.SseEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP 适配器，不持有原始执行器；身份只来自服务端 Principal。 */
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
        try { completed = actionGate.submit(request, principal); }
        catch (CardNotFoundException ex) { return failure(400, ex.getMessage(), "CARD_NOT_FOUND", false); }
        catch (CardSecurityException ex) { return failure(403, ex.getMessage(), "CARD_FORBIDDEN", false); }
        catch (CardStateConflictException ex) { return failure(409, ex.getMessage(), "CARD_STATE_CONFLICT", false); }
        catch (IllegalArgumentException ex) { return failure(400, ex.getMessage(), "INVALID_SUBMISSION", true); }
        catch (CardExecutionUnknownException ex) { return failure(500, ex.getMessage(), "EXECUTION_UNKNOWN", false); }
        catch (RuntimeException ex) {
            log.error("[CardController] 提交处理异常", ex);
            return failure(500, "提交未完成，请核查动作状态", "SUBMISSION_ERROR", false);
        }
        // 确定成功已归档，尽力通知失败不能改变 HTTP 成功结果；回执仍归属于原卡片/回合。
        try {
            if (ssePublisher != null) ssePublisher.sendCardReceipt(completed.getSessionId(), completed.getTurnId(),
                    completed.getActionId(), completed.getResult().getTicketId(),
                    "\n\n> 表单已确认提交，关联单号：`" + completed.getResult().getTicketId() + "`\n");
        } catch (RuntimeException ex) { log.warn("[CardController] 已归档，但回执通知未送达 actionId={}", completed.getActionId(), ex); }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", completed.getResult().getMessage() == null ? "" : completed.getResult().getMessage());
        body.put("ticketId", completed.getResult().getTicketId());
        body.put("cardType", completed.getCardType());
        body.put("actionId", completed.getActionId());
        if (completed.getTurnId() != null) body.put("turnId", completed.getTurnId());
        return ResponseEntity.ok(body);
    }

    @PostMapping(value = "/cancel", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> cancelCard(@RequestBody CardCancelRequest request, Principal principal) {
        ActionGate.CancellationResult cancelled;
        try {
            cancelled = actionGate.cancel(request, principal);
        } catch (CardNotFoundException ex) {
            return failure(400, ex.getMessage(), "CARD_NOT_FOUND", false);
        } catch (CardSecurityException ex) {
            return failure(403, ex.getMessage(), "CARD_FORBIDDEN", false);
        } catch (CardStateConflictException ex) {
            return failure(409, ex.getMessage(), "CARD_STATE_CONFLICT", false);
        } catch (IllegalArgumentException ex) {
            return failure(400, ex.getMessage(), "INVALID_REQUEST", false);
        } catch (RuntimeException ex) {
            log.error("[CardController] 放弃处理异常", ex);
            return failure(500, "放弃操作未完成，请重试", "CANCEL_ERROR", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", "提案已放弃");
        body.put("actionId", cancelled.getActionId());
        body.put("cardType", cancelled.getCardType());
        if (cancelled.getTurnId() != null) {
            body.put("turnId", cancelled.getTurnId());
        }
        body.put("state", cancelled.getState());
        return ResponseEntity.ok(body);
    }

    /**
     * 未知执行结果对账恢复接口（只查询外部幂等回执并收敛账本与挂起回合，绝不重新发起外部写操作）。
     */
    @PostMapping(value = "/reconcile", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> reconcileCard(@RequestBody Map<String, String> request, Principal principal) {
        String actionId = request == null ? null : request.get("actionId");
        String sessionId = request == null ? null : request.get("sessionId");
        ActionGate.SubmissionResult reconciled;
        try {
            reconciled = actionGate.reconcile(actionId, sessionId);
        } catch (CardNotFoundException ex) {
            return failure(400, ex.getMessage(), "CARD_NOT_FOUND", false);
        } catch (CardSecurityException ex) {
            return failure(403, ex.getMessage(), "CARD_FORBIDDEN", false);
        } catch (CardStateConflictException ex) {
            return failure(409, ex.getMessage(), "CARD_STATE_CONFLICT", false);
        } catch (IllegalArgumentException ex) {
            return failure(400, ex.getMessage(), "INVALID_REQUEST", false);
        } catch (CardExecutionUnknownException ex) {
            return failure(500, ex.getMessage(), "EXECUTION_UNKNOWN", false);
        } catch (RuntimeException ex) {
            log.error("[CardController] 对账处理异常", ex);
            return failure(500, "对账未完成，请保持人工核查", "RECONCILE_ERROR", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", reconciled.getResult().getMessage() == null ? "" : reconciled.getResult().getMessage());
        body.put("ticketId", reconciled.getResult().getTicketId());
        body.put("cardType", reconciled.getCardType());
        body.put("actionId", reconciled.getActionId());
        if (reconciled.getTurnId() != null) body.put("turnId", reconciled.getTurnId());
        body.put("state", "SUBMITTED");
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> failure(int status, String message, String type, boolean retryable) {
        return ResponseEntity.status(status).body(Map.of("code", status, "message", message == null ? "提交被拒绝" : message,
                "errorType", type, "retryable", retryable));
    }
}
