package com.example.springai.core.card.impl;

import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.CardNotFoundException;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.exception.CardStateConflictException;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.CardRecord;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.state.CardState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CardRegistryTest {
    private final Clock clock = mock(Clock.class);
    private final InMemoryCardRegistry registry = new InMemoryCardRegistry(clock);

    private InteractiveCard issue() {
        when(clock.millis()).thenReturn(1000L);
        InteractiveCard card = new InteractiveCard();
        card.setActionId("act-1");
        card.setCardType("TEST_ACTION");
        card.setFields(List.of(new CardFormField("target", "目标", "text", "resource-A", false, true),
                new CardFormField("reason", "原因", "text", "初始原因", true, false)));
        registry.issue("session-A", card);
        return card;
    }

    @Test
    void lifecycleAndDuplicateClaims() {
        issue();
        assertEquals(CardState.SUBMITTING, registry.claim("act-1", "session-A").getState());
        assertThrows(CardStateConflictException.class, () -> registry.claim("act-1", "session-A"));
        registry.complete("act-1", CardSubmitResult.ok("T-1", "ok"));
        assertEquals(CardState.SUBMITTED, registry.getRecord("act-1").orElseThrow().getState());
        assertEquals("T-1", registry.getRecord("act-1").orElseThrow().getTicketId());
        assertThrows(CardStateConflictException.class, () -> registry.claim("act-1", "session-A"));
    }

    @Test
    void unissuedAndWrongOrMissingSessionsAreRejected() {
        issue();
        assertThrows(CardNotFoundException.class, () -> registry.claim("unknown", "session-A"));
        assertThrows(CardSecurityException.class, () -> registry.claim("act-1", "session-B"));
        assertThrows(IllegalArgumentException.class, () -> registry.claim("act-1", null));
        assertThrows(IllegalArgumentException.class, () -> registry.claim("act-1", " "));
        assertEquals(CardState.ISSUED, registry.getRecord("act-1").orElseThrow().getState());
    }

    @Test
    void returnedSnapshotsAndOriginalCardCannotMutateStoredProposal() {
        InteractiveCard original = issue();
        original.getFields().get(0).setValue("resource-B");
        CardRecord returned = registry.getRecord("act-1").orElseThrow();
        returned.setSessionId("other");
        returned.setState(CardState.SUBMITTED);
        returned.getSnapshot().getFields().get(0).setValue("resource-C");
        CardRecord claimed = registry.claim("act-1", "session-A");
        assertEquals("resource-A", claimed.getSnapshot().getFields().get(0).getValue());
        claimed.setState(CardState.ISSUED);
        assertThrows(CardStateConflictException.class, () -> registry.claim("act-1", "session-A"));
    }

    @Test
    void duplicateIssuanceNeverResetsAnAction() {
        InteractiveCard card = issue();
        registry.claim("act-1", "session-A");
        registry.markExecutionUnknown("act-1", "response lost");
        assertThrows(CardStateConflictException.class, () -> registry.issue("session-A", card));
        assertThrows(CardStateConflictException.class, () -> registry.fail("act-1", "unsafe retry", true));
        assertEquals(CardState.EXECUTION_UNKNOWN, registry.getRecord("act-1").orElseThrow().getState());
    }

    @Test
    void readOnlyTamperingFailsAndEditableChangesPass() {
        issue();
        CardRecord record = registry.getRecord("act-1").orElseThrow();
        var ex = assertThrows(CardSecurityException.class,
                () -> registry.validate(record, Map.of("target", "resource-B")));
        assertTrue(ex.getMessage().contains("只读参数"));
        assertFalse(ex.getMessage().contains("resource-A")); // 不向错误响应泄露原始值。
        assertDoesNotThrow(() -> registry.validate(record, Map.of("reason", "新原因")));
    }

    @Test
    void expiredIssuedCardCannotStart() {
        issue();
        when(clock.millis()).thenReturn(901000L);
        assertThrows(CardNotFoundException.class, () -> registry.claim("act-1", "session-A"));
        assertEquals(CardState.EXPIRED, registry.getRecord("act-1").orElseThrow().getState());
    }

    @Test
    void expiryDuringExecutionDoesNotOverwriteSubmittingOrCompletedState() {
        issue();
        registry.claim("act-1", "session-A");
        when(clock.millis()).thenReturn(901000L);
        assertThrows(CardStateConflictException.class, () -> registry.claim("act-1", "session-A"));
        assertEquals(CardState.SUBMITTING, registry.getRecord("act-1").orElseThrow().getState());
        registry.complete("act-1", CardSubmitResult.ok("T-1", "ok"));
        assertThrows(CardStateConflictException.class, () -> registry.claim("act-1", "session-A"));
        assertEquals(CardState.SUBMITTED, registry.getRecord("act-1").orElseThrow().getState());
    }

    @Test
    void onlyProvenPreExecutionFailureCanReleaseClaim() {
        issue();
        assertThrows(CardStateConflictException.class,
                () -> registry.complete("act-1", CardSubmitResult.ok("T-1", "ok")));
        registry.claim("act-1", "session-A");
        registry.fail("act-1", "执行前校验失败，尚未调用执行器", true);
        assertEquals(CardState.ISSUED, registry.getRecord("act-1").orElseThrow().getState());
        assertDoesNotThrow(() -> registry.claim("act-1", "session-A"));
    }
}
