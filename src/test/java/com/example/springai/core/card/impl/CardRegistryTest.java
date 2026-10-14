package com.example.springai.core.card.impl;

import com.example.springai.core.card.dto.CardSubmitResult;
import com.example.springai.core.card.exception.*;
import com.example.springai.core.card.model.*;
import com.example.springai.core.card.state.CardState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CardRegistryTest {
    static final class TestClock extends Clock {
        final AtomicLong time = new AtomicLong(1000);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis()); }
        public long millis() { return time.get(); }
    }
    private static InteractiveCard card(String id) {
        var result = new InteractiveCard(); result.setActionId(id); result.setCardType("TEST_ACTION"); result.setTurnId("turn-A");
        var pool = new CardFormField("pool_size","容量","number",100,true,false);
        pool.setMinLimit(10); pool.setMaxLimit(200);
        result.setFields(List.of(new CardFormField("service","服务","text","order-service",false,true),pool));
        return result;
    }
    @Test void ownershipAndStateTransitionsAreEnforced() {
        var registry = new InMemoryCardRegistry(); registry.issue("session-A",card("a"));
        assertThrows(CardSecurityException.class,() -> registry.claim("a","session-B"));
        assertThrows(IllegalArgumentException.class,() -> registry.claim("a",""));
        assertEquals(CardState.SUBMITTING,registry.claim("a","session-A").getState());
        assertThrows(CardStateConflictException.class,() -> registry.claim("a","session-A"));
        registry.complete("a",CardSubmitResult.ok("ticket-1","ok"));
        assertThrows(CardStateConflictException.class,() -> registry.claim("a","session-A"));
        assertThrows(CardStateConflictException.class,() -> registry.fail("a","late callback",true));
    }
    @Test void issuedAndReturnedSnapshotsCannotMutateStoredProposal() {
        var registry = new InMemoryCardRegistry(); var original = card("a"); registry.issue("session-A",original);
        original.getFields().get(0).setValue("other-service");
        var copy = registry.getRecord("a").orElseThrow(); copy.setState(CardState.SUBMITTED);
        copy.getSnapshot().getFields().get(0).setValue("another-service");
        var actual = registry.getRecord("a").orElseThrow();
        assertEquals("order-service",actual.getSnapshot().getFields().get(0).getValue()); assertEquals(CardState.ISSUED,actual.getState());
        assertThrows(CardStateConflictException.class,() -> registry.issue("session-A",card("a")));
    }
    @Test void rejectsTamperingUnknownFieldsNonFiniteNumbersAndStructures() {
        var registry = new InMemoryCardRegistry(); registry.issue("session-A",card("a")); var record = registry.getRecord("a").orElseThrow();
        assertThrows(CardSecurityException.class,() -> registry.validate(record,Map.of("service","wrong")));
        assertThrows(CardSecurityException.class,() -> registry.validate(record,Map.of("operator","admin")));
        for (Object value : List.of("NaN","Infinity","-Infinity",201,9,List.of(100),Map.of("value",100))) {
            assertThrows(IllegalArgumentException.class,() -> registry.validate(record,Map.of("pool_size",value)));
        }
        var values = registry.reconstructSafeFormValues(record,Map.of("pool_size","120"));
        assertEquals("order-service",values.get("service")); assertEquals(0,new java.math.BigDecimal("120").compareTo((java.math.BigDecimal)values.get("pool_size")));
        assertThrows(UnsupportedOperationException.class,() -> values.put("operator","other"));
        var explicitNull = new HashMap<String,Object>(); explicitNull.put("pool_size",null);
        assertNull(registry.reconstructSafeFormValues(record,explicitNull).get("pool_size"));
    }
    @Test void capacityRejectsNewIssuanceAndUnknownOutcomeSurvivesTtl() {
        var clock = new TestClock(); var registry = new InMemoryCardRegistry(clock,1,100,100);
        registry.issue("session-A",card("a"));
        assertThrows(IllegalStateException.class,() -> registry.issue("session-A",card("b")));
        registry.claim("a","session-A"); clock.time.addAndGet(500); registry.evictExpired(); assertEquals(1,registry.size());
        registry.markExecutionUnknown("a","response lost"); clock.time.addAndGet(500); registry.evictExpired();
        assertEquals(CardState.EXECUTION_UNKNOWN,registry.getRecord("a").orElseThrow().getState());
        assertThrows(CardStateConflictException.class,() -> registry.claim("a","session-A"));
        assertThrows(IllegalStateException.class,() -> registry.issue("session-A",card("b")));
    }
    @Test void expiredAndReissuedProposalCannotReuseOldApproval() {
        var clock = new TestClock(); var registry = new InMemoryCardRegistry(clock,2,100,100);
        registry.issue("session-A",card("a")); var approvedVersion = registry.getRecord("a").orElseThrow();
        clock.time.addAndGet(101); registry.evictExpired(); registry.issue("session-A",card("a"));
        assertThrows(CardStateConflictException.class,() -> registry.claim(approvedVersion));
        assertEquals(CardState.ISSUED,registry.getRecord("a").orElseThrow().getState());
    }
    @Test void validationFailureCanRetryButTerminalRetentionIsProtected() {
        var clock = new TestClock(); var registry = new InMemoryCardRegistry(clock,1,1000,1000);
        registry.issue("session-A",card("a")); registry.claim("a","session-A"); registry.fail("a","preflight",true);
        registry.claim("a","session-A"); registry.complete("a",CardSubmitResult.ok("T","ok"));
        clock.time.addAndGet(500); registry.evictExpired(); assertEquals(1,registry.size());
        clock.time.addAndGet(501); registry.evictExpired(); assertEquals(0,registry.size());
    }
    @Test void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        var registry = new InMemoryCardRegistry(); registry.issue("session-A",card("a"));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i=0;i<12;i++) calls.add(() -> { try { registry.claim("a","session-A"); return true; } catch(CardStateConflictException ex) { return false; } });
            int winners = 0; for (Future<Boolean> result : executor.invokeAll(calls)) if(result.get()) winners++;
            assertEquals(1,winners);
        } finally { executor.shutdownNow(); }
    }
}
