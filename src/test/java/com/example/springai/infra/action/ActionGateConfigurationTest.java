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
    private static AnnotationConfigApplicationContext context(OpsActionPort port,Map<String,Object> properties) {
        var ctx=new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",properties));
        // 显式零参数 Supplier，消除 registerBean 的构造器引用重载歧义。
        ctx.registerBean(CardRegistry.class,() -> new InMemoryCardRegistry());
        ctx.registerBean(OpsActionPort.class,() -> port);
        ctx.register(OpsActionCardSubmitHandler.class,ActionGateConfiguration.class); ctx.refresh(); return ctx;
    }
    private static CardSubmitRequest issue(AnnotationConfigApplicationContext ctx) {
        var card=new TroubleshootCardFactory().createTroubleshootCard("order-service","KILL_SLOW_SESSION","trx-1");
        ctx.getBean(CardRegistry.class).issue("s",card);
        return new CardSubmitRequest(card.getActionId(),"s",card.getCardType(),Map.of());
    }
    @Test void defaultsDenyRealWrites() {
        var port=mock(OpsActionPort.class);
        try(var ctx=context(port,Map.of())) {
            assertThrows(CardSecurityException.class,()->ctx.getBean(ActionGate.class).submit(issue(ctx),()->"alice"));
            verify(port,never()).executeAction(any());
        }
    }
    @Test void flagAloneDoesNotAuthorize() {
        var port=mock(OpsActionPort.class);
        try(var ctx=context(port,Map.of("agentflow.actions.real-writes-enabled","true"))) {
            assertThrows(CardSecurityException.class,()->ctx.getBean(ActionGate.class).submit(issue(ctx),()->"alice"));
            verify(port,never()).executeAction(any());
        }
    }
    @Test void mockModeUsesActualInjectedMock() {
        var port=spy(new MockOpsActionAdapter());
        try(var ctx=context(port,Map.of("agentflow.actions.allow-mock-submissions","true"))) {
            assertTrue(ctx.getBean(ActionGate.class).submit(issue(ctx),null).getResult().isSuccess());
            verify(port).executeAction(any());
        }
    }
}
