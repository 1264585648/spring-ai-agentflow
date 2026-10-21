package com.example.springai.web.controller;

import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.action.*;
import com.example.springai.core.card.dto.*;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.*;
import com.example.springai.core.card.state.CardState;
import com.example.springai.infra.sse.SseEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CardInteractionControllerTest {
    private final InMemoryCardRegistry registry = new InMemoryCardRegistry();
    private final SseEventPublisher publisher = mock(SseEventPublisher.class);
    private final AtomicInteger calls = new AtomicInteger();
    private CardSubmitRequest issue() {
        var card = new InteractiveCard(); card.setActionId("a"); card.setTurnId("old-turn"); card.setCardType("TEST");
        card.setFields(List.of(new CardFormField("target","目标","text","resource-A",false,true)));
        registry.issue("s",card); return new CardSubmitRequest("a","s","TEST",Map.of());
    }
    private CardSubmitHandler handler(boolean mock, boolean fail) {
        return new CardSubmitHandler() {
            public boolean supports(String type) { return "TEST".equals(type); }
            public boolean isSimulation() { return mock; }
            public CardSubmitResult handleSubmit(ApprovedCardSubmission approved) {
                calls.incrementAndGet(); if (fail) throw new IllegalStateException("write succeeded, response lost");
                return CardSubmitResult.ok("T-1",approved.getActorId());
            }
        };
    }
    private CardInteractionController controller(boolean fail) {
        return new CardInteractionController(new ActionGate(registry,List.of(handler(true,fail)),ActionAuthorizer.DENY_ALL,true,false),publisher);
    }
    @Test void unknownResultIsNotRetryableAndNeverReexecutes() {
        var request=issue(); var controller=controller(true); var failed=controller.submitCard(request,null);
        assertEquals(500,failed.getStatusCode().value()); assertEquals(false,failed.getBody().get("retryable"));
        assertEquals("EXECUTION_UNKNOWN",failed.getBody().get("errorType"));
        assertEquals(CardState.EXECUTION_UNKNOWN,registry.getRecord("a").orElseThrow().getState());
        assertEquals(409,controller.submitCard(request,null).getStatusCode().value()); assertEquals(1,calls.get());
    }
    @Test void receiptFailureDoesNotTurnConfirmedSuccessInto500() {
        var request=issue();
        when(publisher.sendCardReceipt(anyString(),anyString(),anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("SSE closed"));
        var result=controller(false).submitCard(request,null);
        assertEquals(200,result.getStatusCode().value()); assertEquals("old-turn",result.getBody().get("turnId"));
        assertEquals(CardState.SUBMITTED,registry.getRecord("a").orElseThrow().getState());
        verify(publisher).sendCardReceipt(eq("s"),eq("old-turn"),eq("a"),eq("T-1"),anyString());
    }
    @Test void missingSessionForgedTypeAndReadOnlyTamperingDoNotExecute() {
        var request=issue(); var controller=controller(false);
        request.setSessionId(null); assertEquals(400,controller.submitCard(request,null).getStatusCode().value());
        request.setSessionId("other"); assertEquals(403,controller.submitCard(request,null).getStatusCode().value());
        request.setSessionId("s"); request.setCardType("OTHER"); assertEquals(403,controller.submitCard(request,null).getStatusCode().value());
        request.setCardType("TEST"); request.setFormValues(Map.of("target","resource-B"));
        assertEquals(403,controller.submitCard(request,null).getStatusCode().value());
        request.setFormValues(Map.of("operator","admin")); assertEquals(403,controller.submitCard(request,null).getStatusCode().value());
        assertEquals(0,calls.get()); assertEquals(CardState.ISSUED,registry.getRecord("a").orElseThrow().getState());
    }
    @Test void mvcPrincipalCannotBeReplacedByUserHeader() throws Exception {
        issue(); var gate=new ActionGate(registry,List.of(handler(false,false)),(actor,p,v)->assertEquals("alice",actor),false,true);
        var mvc=MockMvcBuilders.standaloneSetup(new CardInteractionController(gate,publisher)).build();
        String json="{\"actionId\":\"a\",\"sessionId\":\"s\",\"cardType\":\"TEST\",\"formValues\":{}}";
        mvc.perform(post("/api/v1/card/submit").contentType(MediaType.APPLICATION_JSON).header("X-User-Id","alice").content(json)).andExpect(status().isForbidden());
        assertEquals(0,calls.get());
        mvc.perform(post("/api/v1/card/submit").contentType(MediaType.APPLICATION_JSON).principal(()->"alice").header("X-User-Id","mallory").content(json)).andExpect(status().isOk());
        assertEquals(1,calls.get());
    }
    @Test void cancelCardReturnsOkAndTransitionsToCancelled() {
        issue(); var controller=controller(false);
        var cancelReq=new CardCancelRequest("a","s","放弃处置");
        var res=controller.cancelCard(cancelReq,null);
        assertEquals(200,res.getStatusCode().value());
        assertEquals("CANCELLED",res.getBody().get("state"));
        assertEquals("a",res.getBody().get("actionId"));
        assertEquals("old-turn",res.getBody().get("turnId"));
        assertEquals(CardState.CANCELLED,registry.getRecord("a").orElseThrow().getState());
        assertEquals(409,controller.cancelCard(cancelReq,null).getStatusCode().value());
        assertEquals(409,controller.submitCard(new CardSubmitRequest("a","s","TEST",Map.of()),null).getStatusCode().value());
    }
    @Test void cancelCardWithWrongSessionReturns403() {
        issue(); var controller=controller(false);
        var cancelReq=new CardCancelRequest("a","wrong-session","放弃");
        var res=controller.cancelCard(cancelReq,null);
        assertEquals(403,res.getStatusCode().value());
        assertEquals(CardState.ISSUED,registry.getRecord("a").orElseThrow().getState());
    }
    @Test void reconcileCardRecoversUnknownStateWithoutReExecuting() {
        var request = issue();
        CardSubmitHandler reconcilable = new CardSubmitHandler() {
            public boolean supports(String type) { return "TEST".equals(type); }
            public boolean isSimulation() { return true; }
            public CardSubmitResult handleSubmit(ApprovedCardSubmission approved) {
                calls.incrementAndGet();
                throw new IllegalStateException("remote succeeded, response timed out");
            }
            public Optional<CardSubmitResult> reconcile(CardRecord record) {
                return Optional.of(CardSubmitResult.ok("T-RECONCILED-1", "对账恢复成功"));
            }
        };
        var controller = new CardInteractionController(
                new ActionGate(registry, List.of(reconcilable), ActionAuthorizer.DENY_ALL, true, false), publisher);
        var failed = controller.submitCard(request, null);
        assertEquals(500, failed.getStatusCode().value());
        assertEquals(CardState.EXECUTION_UNKNOWN, registry.getRecord("a").orElseThrow().getState());

        var reconciled = controller.reconcileCard(Map.of("actionId", "a", "sessionId", "s"), null);
        assertEquals(200, reconciled.getStatusCode().value());
        assertEquals("T-RECONCILED-1", reconciled.getBody().get("ticketId"));
        assertEquals("SUBMITTED", reconciled.getBody().get("state"));
        assertEquals(CardState.SUBMITTED, registry.getRecord("a").orElseThrow().getState());
        assertEquals(1, calls.get(), "对账恢复严禁重复触发 handleSubmit");
    }
}
