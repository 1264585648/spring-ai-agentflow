package com.example.springai.web.controller;

import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.action.ActionAuthorizer;
import com.example.springai.core.card.action.ActionGate;
import com.example.springai.core.card.action.ApprovedCardSubmission;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.state.CardState;
import com.example.springai.infra.sse.SseEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CardInteractionControllerTest {
    private final InMemoryCardRegistry registry = new InMemoryCardRegistry();
    private final SseEventPublisher publisher = mock(SseEventPublisher.class);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> actor = new AtomicReference<>();

    private CardSubmitRequest issue() {
        InteractiveCard card = new InteractiveCard();
        card.setActionId("act-1");
        card.setCardType("TEST_ACTION");
        card.setFields(List.of(new CardFormField("target", "目标", "text", "resource-A", false, true)));
        registry.issue("session-A", card);
        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("act-1");
        request.setSessionId("session-A");
        request.setCardType("TEST_ACTION");
        request.setFormValues(Map.of());
        return request;
    }

    private CardSubmitHandler handler(boolean simulation, boolean fails) {
        return new CardSubmitHandler() {
            public boolean supports(String type) { return "TEST_ACTION".equals(type); }
            public boolean isSimulation() { return simulation; }
            public CardSubmitResult handleSubmit(ApprovedCardSubmission submission) {
                calls.incrementAndGet();
                actor.set(submission.getActorId());
                if (fails) { throw new IllegalStateException("remote response lost"); }
                return CardSubmitResult.ok("T-1", "ok");
            }
        };
    }

    private CardInteractionController controller(boolean fails) {
        return new CardInteractionController(new ActionGate(registry, List.of(handler(true, fails)),
                ActionAuthorizer.DENY_ALL, true, false), publisher);
    }

    @Test
    void executionFailureReturnsNonRetryableUnknownAndNextAttemptConflicts() {
        CardSubmitRequest request = issue();
        var controller = controller(true);
        var response = controller.submitCard(request, null);
        assertEquals(500, response.getStatusCode().value());
        assertEquals("EXECUTION_UNKNOWN", response.getBody().get("errorType"));
        assertEquals(false, response.getBody().get("retryable"));
        assertEquals(CardState.EXECUTION_UNKNOWN, registry.getRecord("act-1").orElseThrow().getState());
        assertEquals(409, controller.submitCard(request, null).getStatusCode().value());
        assertEquals(1, calls.get());
        verifyNoInteractions(publisher);
    }

    @Test
    void notificationFailureDoesNotTurnCompletedActionIntoHttpFailure() {
        CardSubmitRequest request = issue();
        when(publisher.sendMessage(anyString(), anyString())).thenThrow(new IllegalStateException("SSE closed"));
        var controller = controller(false);
        var response = controller.submitCard(request, null);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("T-1", response.getBody().get("ticketId"));
        assertEquals("TEST_ACTION", response.getBody().get("cardType"));
        assertEquals(CardState.SUBMITTED, registry.getRecord("act-1").orElseThrow().getState());
        assertEquals(409, controller.submitCard(request, null).getStatusCode().value());
        assertEquals(1, calls.get());
    }

    @Test
    void forgedTypeAndSessionAreForbiddenAndUnissuedCardIs400() {
        CardSubmitRequest request = issue();
        var controller = controller(false);
        request.setCardType("OTHER");
        assertEquals(403, controller.submitCard(request, null).getStatusCode().value());
        request.setCardType("TEST_ACTION");
        request.setSessionId("session-B");
        assertEquals(403, controller.submitCard(request, null).getStatusCode().value());
        request.setSessionId(null);
        assertEquals(400, controller.submitCard(request, null).getStatusCode().value());
        request.setSessionId("session-A");
        request.setActionId("unissued");
        assertEquals(400, controller.submitCard(request, null).getStatusCode().value());
        assertEquals(0, calls.get());
    }

    @Test
    void readOnlyTamperingIs403AndNoExecutionOccurs() {
        CardSubmitRequest request = issue();
        request.setFormValues(Map.of("target", "resource-B"));
        var response = controller(false).submitCard(request, null);
        assertEquals(403, response.getStatusCode().value());
        assertFalse(String.valueOf(response.getBody()).contains("resource-A"));
        assertEquals(0, calls.get());
    }

    @Test
    void mvcUsesPrincipalNotHeadersOrSessionAsIdentity() throws Exception {
        issue();
        ActionAuthorizer policy = (id, proposal, values) -> assertEquals("alice", id);
        var controller = new CardInteractionController(new ActionGate(registry,
                List.of(handler(false, false)), policy, false, true), publisher);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String json = """
                {"actionId":"act-1","sessionId":"session-A","cardType":"TEST_ACTION","formValues":{}}
                """;
        mvc.perform(post("/api/v1/card/submit").contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", "alice").content(json)).andExpect(status().isForbidden());
        assertEquals(0, calls.get());
        mvc.perform(post("/api/v1/card/submit").contentType(MediaType.APPLICATION_JSON)
                .principal(() -> "alice").header("X-User-Id", "mallory").content(json)).andExpect(status().isOk());
        assertEquals("alice", actor.get());
        assertEquals(1, calls.get());
    }
}
