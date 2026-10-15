package com.example.springai.web.controller;

import com.example.springai.core.pipeline.AgentPipelineService;
import com.example.springai.infra.sse.SseEmitterManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatCancellationTest {
    @Test
    void cancellationRequiresTurnIdentity() throws Exception {
        var pipeline = mock(AgentPipelineService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ChatController(mock(SseEmitterManager.class), pipeline)).build();
        mvc.perform(post("/api/v1/chat/cancel").param("sessionId", "s")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/chat/cancel").param("sessionId", "s").param("turnId", " "))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(pipeline);
    }

    @Test
    void staleCancellationNeverFallsBackToSessionWideCancellation() throws Exception {
        var pipeline = mock(AgentPipelineService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ChatController(mock(SseEmitterManager.class), pipeline)).build();
        mvc.perform(post("/api/v1/chat/cancel").param("sessionId", "s").param("turnId", "old"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(false));
        verify(pipeline).cancelActiveTurn("s", "old");
        verify(pipeline, never()).cancelActiveTurn("s");
    }
}
