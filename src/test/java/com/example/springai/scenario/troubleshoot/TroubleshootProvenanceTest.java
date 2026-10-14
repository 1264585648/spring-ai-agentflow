package com.example.springai.scenario.troubleshoot;

import com.example.springai.capability.database.mock.MockDbAdapter;
import com.example.springai.capability.database.port.DatabaseDiagnosePort;
import com.example.springai.capability.log.mock.MockLogAdapter;
import com.example.springai.capability.log.port.LogQueryPort;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.action.*;
import com.example.springai.core.card.dto.*;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.ActionProposal;
import com.example.springai.core.card.state.CardState;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TroubleshootProvenanceTest {
    private ActionProposal proposal(boolean simulation) {
        return ActionProposal.builder().service("order-service").actionType("KILL_SLOW_SESSION")
                .targetIdentifier("trx-1").evidenceSummary("已核验的诊断证据").suggestedMaxPoolSize("120")
                .simulationMode(simulation).build();
    }
    @Test void onlineModelCannotRemoveSimulationFromActualMockEvidence() {
        var factory=new TroubleshootCardFactory(new MockLogAdapter(),new MockDbAdapter());
        var card=factory.createTroubleshootCard(proposal(false));
        assertTrue(card.isSimulation()); assertTrue(card.getTitle().contains("模拟"));
        assertTrue(card.getConfirmButtonText().contains("模拟"));
    }
    @Test void mixingRealAndMockEvidenceRemainsSimulation() {
        var factory=new TroubleshootCardFactory(mock(LogQueryPort.class),new MockDbAdapter());
        assertTrue(factory.createTroubleshootCard(proposal(false)).isSimulation());
    }
    @Test void explicitlySimulatedProposalStaysSimulationWithRealPorts() {
        var factory=new TroubleshootCardFactory(mock(LogQueryPort.class),mock(DatabaseDiagnosePort.class));
        assertTrue(factory.createTroubleshootCard(proposal(true)).isSimulation());
        assertFalse(factory.createTroubleshootCard(proposal(false)).isSimulation());
    }
    @Test void manualFactoryWithoutEvidenceProviderDefaultsToSimulation() {
        assertTrue(new TroubleshootCardFactory().createTroubleshootCard(proposal(false)).isSimulation());
    }
    @Test void simulationCannotReachRealExecutorEvenWithPrincipalAndAuthorization() {
        var card=new TroubleshootCardFactory(new MockLogAdapter(),new MockDbAdapter()).createTroubleshootCard(proposal(false));
        var registry=new InMemoryCardRegistry(); registry.issue("session-A",card);
        var executions=new AtomicInteger();
        CardSubmitHandler executor=new CardSubmitHandler() {
            public boolean supports(String type) { return "TROUBLESHOOT_ACTION".equals(type); }
            public CardSubmitResult handleSubmit(ApprovedCardSubmission approved) {
                executions.incrementAndGet(); return CardSubmitResult.ok("real-ticket","ok");
            }
        };
        var gate=new ActionGate(registry,List.of(executor),(actor,p,values)->{},false,true);
        var request=new CardSubmitRequest(card.getActionId(),"session-A",card.getCardType(),Map.of());
        assertThrows(CardSecurityException.class,()->gate.submit(request,()->"alice"));
        assertEquals(0,executions.get());
        assertEquals(CardState.ISSUED,registry.getRecord(card.getActionId()).orElseThrow().getState());
    }
}
