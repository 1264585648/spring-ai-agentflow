package com.example.springai.capability.ops.card;

import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.port.OpsActionPort;
import com.example.springai.core.card.action.ActionAuthorizer;
import com.example.springai.core.card.action.ActionGate;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.CardFormField;
import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.card.state.CardState;
import com.example.springai.scenario.troubleshoot.TroubleshootCardFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpsActionCardSubmitHandlerTest {
    @Test
    void preservesSupportedTypesAndExplicitMockClassification() {
        var handler = new OpsActionCardSubmitHandler(new MockOpsActionAdapter());
        for (String type : List.of("OPS_ACTION", "ops_action", "TROUBLESHOOT_ACTION", "troubleshoot_action")) {
            assertTrue(handler.supports(type));
        }
        assertFalse(handler.supports("UNKNOWN_CARD"));
        assertTrue(handler.isSimulation());
        assertFalse(new OpsActionCardSubmitHandler(mock(OpsActionPort.class)).isSimulation());
        assertEquals(1, OpsActionCardSubmitHandler.class.getConstructors().length);
    }

    @Test
    void executesApprovedMockSubmissionWithServerActor() {
        var port = spy(new MockOpsActionAdapter());
        var registry = new InMemoryCardRegistry();
        var card = new TroubleshootCardFactory().createTroubleshootCard("order-service", "KILL_SLOW_SESSION", "trx_10423");
        registry.issue("session-A", card);
        var gate = new ActionGate(registry, List.of(new OpsActionCardSubmitHandler(port)),
                ActionAuthorizer.DENY_ALL, true, false);
        CardSubmitRequest request = request(card);
        var result = gate.submit(request, null).getResult();
        assertTrue(result.isSuccess());
        assertTrue(result.getTicketId().startsWith("OPS-"));
        ArgumentCaptor<OpsActionParam> captor = ArgumentCaptor.forClass(OpsActionParam.class);
        verify(port).executeAction(captor.capture());
        assertEquals("mock-demo", captor.getValue().getOperator());
        assertEquals("trx_10423", captor.getValue().getTargetIdentifier());
        assertTrue(captor.getValue().getReason().contains(card.getActionId()));
    }

    @Test
    void missingTargetDoesNotFallBackToSampleIdentifier() {
        var port = spy(new MockOpsActionAdapter());
        var registry = new InMemoryCardRegistry();
        var card = new InteractiveCard();
        card.setActionId("act-missing");
        card.setCardType("OPS_ACTION");
        card.setFields(List.of(new CardFormField("service", "服务", "text", "order-service", false, true),
                new CardFormField("action_type", "操作", "text", "KILL_SLOW_SESSION", false, true)));
        registry.issue("session-A", card);
        var gate = new ActionGate(registry, List.of(new OpsActionCardSubmitHandler(port)),
                ActionAuthorizer.DENY_ALL, true, false);
        assertThrows(IllegalArgumentException.class, () -> gate.submit(request(card), null));
        verifyNoInteractions(port);
        assertEquals(CardState.ISSUED, registry.getRecord("act-missing").orElseThrow().getState());
    }

    private static CardSubmitRequest request(InteractiveCard card) {
        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId(card.getActionId());
        request.setSessionId("session-A");
        request.setCardType(card.getCardType());
        request.setFormValues(Map.of());
        return request;
    }
}
