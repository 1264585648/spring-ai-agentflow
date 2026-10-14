package com.example.springai.capability.ops.card;

import com.example.springai.capability.ops.mock.MockOpsActionAdapter;
import com.example.springai.capability.ops.model.*;
import com.example.springai.capability.ops.port.OpsActionPort;
import com.example.springai.core.card.action.*;
import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.exception.*;
import com.example.springai.core.card.impl.InMemoryCardRegistry;
import com.example.springai.core.card.model.*;
import com.example.springai.core.card.state.CardState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpsActionCardSubmitHandlerTest {
    private final InMemoryCardRegistry registry = new InMemoryCardRegistry();
    private CardSubmitRequest issue() {
        var card = new InteractiveCard(); card.setActionId("act-1"); card.setTurnId("turn-1"); card.setCardType("OPS_ACTION");
        var pool = new CardFormField("max_pool_size", "容量", "number", 80, true, true); pool.setMinLimit(10); pool.setMaxLimit(500);
        card.setFields(List.of(new CardFormField("service","服务","text","order-service",false,true),
                new CardFormField("action_type","动作","text","KILL_SLOW_SESSION_AND_EXPAND_POOL",false,true),
                new CardFormField("target_identifier","目标","text","trx-1",false,true), pool,
                new CardFormField("remark","原因","textarea","默认原因",true,true)));
        registry.issue("s", card);
        return new CardSubmitRequest("act-1","s","OPS_ACTION",Map.of("max_pool_size","120","remark","核查后批准扩容"));
    }
    @Test void allApprovedParametersReachPort() {
        var port = spy(new MockOpsActionAdapter());
        var gate = new ActionGate(registry,List.of(new OpsActionCardSubmitHandler(port)),ActionAuthorizer.DENY_ALL,true,false);
        var request = issue(); var result = gate.submit(request,null);
        var captor = ArgumentCaptor.forClass(OpsActionParam.class); verify(port).executeAction(captor.capture());
        var p = captor.getValue(); assertEquals(120,p.getMaxPoolSize()); assertEquals("trx-1",p.getTargetIdentifier());
        assertEquals("mock-demo",p.getOperator()); assertEquals("act-1",p.getIdempotencyKey());
        assertTrue(p.getReason().contains("核查后批准扩容")); assertEquals("turn-1",result.getTurnId());
        assertThrows(CardStateConflictException.class,() -> gate.submit(request,null));
        verify(port,times(1)).executeAction(any());
    }
    @Test void fractionalCapacityRejectedBeforeExecution() {
        var port = spy(new MockOpsActionAdapter()); var request = issue(); request.setFormValues(Map.of("max_pool_size","120.5"));
        var gate = new ActionGate(registry,List.of(new OpsActionCardSubmitHandler(port)),ActionAuthorizer.DENY_ALL,true,false);
        assertThrows(IllegalArgumentException.class,() -> gate.submit(request,null)); verify(port,never()).executeAction(any());
        assertEquals(CardState.ISSUED,registry.getRecord("act-1").orElseThrow().getState());
    }
    @Test void realPortReceivesPrincipalNotFixedOperator() {
        var port = mock(OpsActionPort.class);
        when(port.executeAction(any())).thenReturn(OpsActionResult.builder().success(true).ticketId("R-1").build());
        var gate = new ActionGate(registry,List.of(new OpsActionCardSubmitHandler(port)),(a,p,v) -> assertEquals("alice",a),false,true);
        gate.submit(issue(),() -> "alice");
        var captor = ArgumentCaptor.forClass(OpsActionParam.class); verify(port).executeAction(captor.capture());
        assertEquals("alice",captor.getValue().getOperator());
    }
}
