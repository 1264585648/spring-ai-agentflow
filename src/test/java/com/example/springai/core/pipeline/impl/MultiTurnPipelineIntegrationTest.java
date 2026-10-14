package com.example.springai.core.pipeline.impl;

import com.example.springai.core.agent.GeneralChatAgent;
import com.example.springai.core.context.AgentContext;
import com.example.springai.core.memory.impl.InMemoryConversationMemory;
import com.example.springai.core.routing.*;
import com.example.springai.core.tool.L1ToolDispatcher;
import com.example.springai.infra.sse.*;
import com.example.springai.web.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiTurnPipelineIntegrationTest {
    @Test void completedTurnIsArchivedAndActualSecondResolverReceivesRewrittenHistory() {
        var transport=mock(SseEmitterManager.class); when(transport.send(anyString(),any())).thenReturn(true);
        var publisher=new SseEventPublisher(transport); var memory=new InMemoryConversationMemory(12);
        var resolver=mock(IntentResolver.class); when(resolver.resolve(any())).thenReturn(Optional.of(Resolution.directReply("L1","TEST","目标 order-service，连接数 50")));
        var rewriter=mock(QueryRewriter.class); when(rewriter.rewrite(any(),anyList())).thenReturn("order-service 的连接池怎么调整？");
        try(var pipeline=new AgentPipelineServiceImpl(publisher,List.of(resolver),mock(L1ToolDispatcher.class),List.of(),
                mock(GeneralChatAgent.class),null,memory,rewriter,Runnable::run)) {
            var first=new ChatRequest(); first.setSessionId("s"); first.setTurnId("turn-1"); first.setQuery("order-service 连接数是多少？");
            pipeline.process(first);
            assertEquals(2,memory.size("s")); verifyNoInteractions(rewriter);
            assertEquals(first.getQuery(),memory.getRecentMessages("s",2).get(0).getContent());
            assertEquals("目标 order-service，连接数 50",memory.getRecentMessages("s",2).get(1).getContent());
            var second=new ChatRequest(); second.setSessionId("s"); second.setTurnId("turn-2"); second.setQuery("它怎么调整？");
            pipeline.process(second);
            var contexts=ArgumentCaptor.forClass(AgentContext.class); verify(resolver,times(2)).resolve(contexts.capture());
            AgentContext ctx=contexts.getAllValues().get(1);
            assertEquals("它怎么调整？",ctx.getQuery()); assertEquals("order-service 的连接池怎么调整？",ctx.getEffectiveQuery());
            assertEquals(2,ctx.getHistory().size()); assertEquals(first.getQuery(),ctx.getHistory().get(0).getContent());
            verify(rewriter).rewrite(same(ctx),eq(ctx.getHistory())); assertEquals(4,memory.size("s"));
            assertEquals(0,publisher.activeTurnCount());
        }
    }
}
