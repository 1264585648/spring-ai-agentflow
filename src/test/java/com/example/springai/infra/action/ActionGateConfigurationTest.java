package com.example.springai.infra.action;

import com.example.springai.capability.ops.card.OpsActionCardSubmitHandler;
import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.port.OpsActionPort;
import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.action.ActionGate;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.exception.CardSecurityException;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.scenario.troubleshoot.TroubleshootCardFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionGateConfigurationTest {
    @Test
    void defaultConfigurationDoesNotEnableRealWritesOrReplaceInjectedPortWithMock() {
        OpsActionPort port = mock(OpsActionPort.class);
        try (var context = context(port, Map.of())) {
            assertFalse(context.getBean(OpsActionCardSubmitHandler.class).isSimulation());
            assertThrows(CardSecurityException.class,
                    () -> context.getBean(ActionGate.class).submit(issue(context), () -> "alice"));
            verifyNoInteractions(port);
        }
    }

    @Test
    void realWriteFlagAloneIsInsufficientWithoutAuthorizer() {
        OpsActionPort port = mock(OpsActionPort.class);
        try (var context = context(port, Map.of("agentflow.actions.real-writes-enabled", "true"))) {
            assertThrows(CardSecurityException.class,
                    () -> context.getBean(ActionGate.class).submit(issue(context), () -> "alice"));
            verifyNoInteractions(port);
        }
    }

    @Test
    void explicitMockModeRetainsContainerWiredDemo() {
        var port = spy(new MockOpsActionAdapter());
        try (var context = context(port, Map.of("agentflow.actions.allow-mock-submissions", "true"))) {
            assertTrue(context.getBean(OpsActionCardSubmitHandler.class).isSimulation());
            var result = context.getBean(ActionGate.class).submit(issue(context), null).getResult();
            assertTrue(result.isSuccess());
            verify(port).executeAction(any());
        }
    }

    private static AnnotationConfigApplicationContext context(OpsActionPort port, Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(CardRegistry.class, InMemoryCardRegistry::new);
        context.registerBean(OpsActionPort.class, () -> port);
        context.register(OpsActionCardSubmitHandler.class, ActionGateConfiguration.class);
        context.refresh();
        return context;
    }

    private static CardSubmitRequest issue(AnnotationConfigApplicationContext context) {
        var card = new TroubleshootCardFactory().createTroubleshootCard("order-service", "KILL_SLOW_SESSION", "trx_1");
        context.getBean(CardRegistry.class).issue("session-A", card);
        CardSubmitRequest request = new CardSubmitRequest();
        request.setActionId(card.getActionId());
        request.setSessionId("session-A");
        request.setCardType(card.getCardType());
        request.setFormValues(Map.of());
        return request;
    }
}
