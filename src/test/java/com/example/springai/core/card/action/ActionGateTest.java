package com.example.springai.core.card.action;

import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.CardExecutionUnknownException;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.state.CardState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ActionGateTest {
    private final InMemoryCardRegistry registry = new InMemoryCardRegistry();
    private final AtomicInteger executions = new AtomicInteger();
    private final AtomicReference<ApprovedCardSubmission> received = new AtomicReference<>();

    private CardSubmitHandler handler(boolean simulation) {
        return new CardSubmitHandler() {
            public boolean supports(String type) { return "TEST_ACTION".equals(type); }
            public boolean isSimulation() { return simulation; }
            public CardSubmitResult handleSubmit(ApprovedCardSubmission submission) {
                executions.incrementAndGet();
                received.set(submission);
                return CardSubmitResult.ok("T-1", "ok");
            }
        };
    }

    private CardSubmitRequest issue() {
        InteractiveCard card = new InteractiveCard();
        card.setActionId("act-1");
        card.setCardType("TEST_ACTION");
        CardFormField amount = new CardFormField("amount", "数值", "number", 10, true, true);
        amount.setMinLimit(1);
        amount.setMaxLimit(20);
        card.setFields(List.of(new CardFormField("target", "目标", "text", "resource-A", false, true),
                amount, new CardFormField("reason", "原因", "textarea", "初始原因", true, true)));
        registry.issue("session-A", card);
        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId("act-1");
        request.setSessionId("session-A");
        request.setCardType("TEST_ACTION");
        request.setFormValues(Map.of("reason", "批准原因", "amount", "12.50"));
        return request;
    }

    private ActionGate mockGate() {
        return new ActionGate(registry, List.of(handler(true)), ActionAuthorizer.DENY_ALL, true, false);
    }

    @Test
    void reconstructsImmutableValuesAndAllowsOnlyOneExecution() {
        CardSubmitRequest request = issue();
        var completed = mockGate().submit(request, null);
        assertEquals("T-1", completed.getResult().getTicketId());
        assertEquals("resource-A", received.get().getFormValues().get("target"));
        assertEquals(new BigDecimal("12.5"), received.get().getFormValues().get("amount"));
        assertEquals("mock-demo", received.get().getActorId());
        assertThrows(UnsupportedOperationException.class,
                () -> received.get().getFormValues().put("target", "resource-B"));
        assertThrows(CardStateConflictException.class, () -> mockGate().submit(request, null));
        assertEquals(1, executions.get());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "session-B"})
    void missingOrWrongSessionDoesNotConsumeProposal(String session) {
        CardSubmitRequest request = issue();
        request.setSessionId(session);
        assertThrows(RuntimeException.class, () -> mockGate().submit(request, null));
        assertEquals(0, executions.get());
        assertEquals(CardState.ISSUED, registry.getRecord("act-1").orElseThrow().getState());
    }

    @Test
    void forgedTypeCannotSelectAnotherHandlerOrConsumeProposal() {
        CardSubmitRequest request = issue();
        request.setCardType("OTHER_ACTION");
        assertThrows(CardSecurityException.class, () -> mockGate().submit(request, null));
        assertEquals(0, executions.get());
        assertEquals(CardState.ISSUED, registry.getRecord("act-1").orElseThrow().getState());
    }

    @Test
    void tamperingUnknownOperatorAndNestedValuesAreRejected() {
        CardSubmitRequest request = issue();
        request.setFormValues(Map.of("target", "resource-B"));
        assertThrows(CardSecurityException.class, () -> mockGate().submit(request, null));
        request.setFormValues(Map.of("operator", "administrator"));
        assertThrows(CardSecurityException.class, () -> mockGate().submit(request, null));
        request.setFormValues(Map.of("reason", Map.of("injected", true)));
        assertThrows(IllegalArgumentException.class, () -> mockGate().submit(request, null));
        assertEquals(0, executions.get());
        assertEquals(CardState.ISSUED, registry.getRecord("act-1").orElseThrow().getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "21", "NaN", "Infinity", "not-a-number", ""})
    void invalidNumbersCanBeCorrectedBeforeExecution(String amount) {
        CardSubmitRequest request = issue();
        request.setFormValues(Map.of("amount", amount));
        assertThrows(IllegalArgumentException.class, () -> mockGate().submit(request, null));
        assertEquals(CardState.ISSUED, registry.getRecord("act-1").orElseThrow().getState());
        request.setFormValues(Map.of("amount", 15));
        assertTrue(mockGate().submit(request, null).getResult().isSuccess());
        assertEquals(1, executions.get());
    }

    @Test
    void requiredEditableValueCannotBeCleared() {
        CardSubmitRequest request = issue();
        request.setFormValues(Map.of("reason", "   "));
        assertThrows(IllegalArgumentException.class, () -> mockGate().submit(request, null));
        assertEquals(0, executions.get());
    }

    @Test
    void mockPermissionNeverEnablesRealHandler() {
        CardSubmitRequest request = issue();
        ActionGate gate = new ActionGate(registry, List.of(handler(false)), (actor, p, v) -> { }, true, false);
        assertThrows(CardSecurityException.class, () -> gate.submit(request, () -> "alice"));
        assertEquals(0, executions.get());
    }

    @Test
    void realWriteRequiresPrincipalAndExplicitAuthorizer() {
        CardSubmitRequest request = issue();
        ActionGate noIdentity = new ActionGate(registry, List.of(handler(false)), (actor, p, v) -> { }, false, true);
        assertThrows(CardSecurityException.class, () -> noIdentity.submit(request, null));
        ActionGate noPolicy = new ActionGate(registry, List.of(handler(false)), ActionAuthorizer.DENY_ALL, false, true);
        assertThrows(CardSecurityException.class, () -> noPolicy.submit(request, () -> "alice"));
        assertEquals(0, executions.get());
    }

    @Test
    void authorizationSeesCanonicalTargetAndFinalValues() {
        CardSubmitRequest request = issue();
        ActionAuthorizer policy = (actor, p, values) -> {
            if (!"alice".equals(actor) || !"session-A".equals(p.getSessionId())) {
                throw new CardSecurityException("无权操作");
            }
            assertEquals("resource-A", values.get("target"));
            assertEquals(new BigDecimal("12.5"), values.get("amount"));
        };
        ActionGate gate = new ActionGate(registry, List.of(handler(false)), policy, false, true);
        assertThrows(CardSecurityException.class, () -> gate.submit(request, () -> "mallory"));
        gate.submit(request, () -> "alice");
        assertEquals("alice", received.get().getActorId());
        assertEquals(1, executions.get());
    }

    @Test
    void executionExceptionIsUnknownAndCannotBeRetried() {
        CardSubmitRequest request = issue();
        CardSubmitHandler uncertain = new CardSubmitHandler() {
            public boolean supports(String type) { return true; }
            public boolean isSimulation() { return true; }
            public CardSubmitResult handleSubmit(ApprovedCardSubmission submission) {
                executions.incrementAndGet(); // 模拟远端已写入、但回执丢失。
                throw new IllegalStateException("response lost");
            }
        };
        ActionGate gate = new ActionGate(registry, List.of(uncertain), ActionAuthorizer.DENY_ALL, true, false);
        assertThrows(CardExecutionUnknownException.class, () -> gate.submit(request, null));
        assertEquals(CardState.EXECUTION_UNKNOWN, registry.getRecord("act-1").orElseThrow().getState());
        assertThrows(CardStateConflictException.class, () -> gate.submit(request, null));
        assertEquals(1, executions.get());
    }

    @Test
    void failureResponseIsNotProofOfNoSideEffects() {
        CardSubmitRequest request = issue();
        CardSubmitHandler failed = new CardSubmitHandler() {
            public boolean supports(String type) { return true; }
            public boolean isSimulation() { return true; }
            public CardSubmitResult handleSubmit(ApprovedCardSubmission submission) {
                return CardSubmitResult.fail("remote failed after partial execution");
            }
        };
        ActionGate gate = new ActionGate(registry, List.of(failed), ActionAuthorizer.DENY_ALL, true, false);
        assertThrows(CardExecutionUnknownException.class, () -> gate.submit(request, null));
        assertEquals(CardState.EXECUTION_UNKNOWN, registry.getRecord("act-1").orElseThrow().getState());
    }

    @Test
    void concurrentSubmissionsExecuteOnlyOnce() throws Exception {
        CardSubmitRequest request = issue();
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CardSubmitHandler slow = new CardSubmitHandler() {
            public boolean supports(String type) { return true; }
            public boolean isSimulation() { return true; }
            public CardSubmitResult handleSubmit(ApprovedCardSubmission submission) {
                executions.incrementAndGet();
                executing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return CardSubmitResult.ok("T-1", "ok");
            }
        };
        ActionGate gate = new ActionGate(registry, List.of(slow), ActionAuthorizer.DENY_ALL, true, false);
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        try {
            var first = executor.submit(() -> gate.submit(request, null));
            assertTrue(executing.await(3, TimeUnit.SECONDS));
            assertThrows(CardStateConflictException.class, () -> gate.submit(request, null));
            release.countDown();
            assertTrue(first.get(3, TimeUnit.SECONDS).getResult().isSuccess());
            assertEquals(1, executions.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
