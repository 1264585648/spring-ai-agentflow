package com.example.springai.web.controller;

import com.example.springai.core.event.L2VectorReloadEvent;
import com.example.springai.core.routing.L2VectorRegistry;
import com.example.springai.core.routing.vector.VectorEmbeddingService;
import com.example.springai.infra.persistence.entity.FaqDefinitionEntity;
import com.example.springai.infra.persistence.repository.FaqDefinitionRepository;
import com.example.springai.infra.sync.ClusterSyncResult;
import com.example.springai.infra.sync.L2ClusterSync;
import com.example.springai.web.dto.FaqCreateRequest;
import com.example.springai.web.dto.FaqResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class L2FaqAdminControllerTest {

    private FaqDefinitionRepository faqRepository;
    private L2VectorRegistry vectorRegistry;
    private VectorEmbeddingService embeddingService;
    private ApplicationEventPublisher eventPublisher;
    private L2ClusterSync clusterSync;
    private L2FaqAdminController controller;

    @BeforeEach
    void setUp() {
        faqRepository = mock(FaqDefinitionRepository.class);
        vectorRegistry = mock(L2VectorRegistry.class);
        embeddingService = mock(VectorEmbeddingService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        clusterSync = mock(L2ClusterSync.class);

        when(clusterSync.publishRevision(anyString())).thenReturn(ClusterSyncResult.ok());

        controller = new L2FaqAdminController(
                faqRepository,
                vectorRegistry,
                embeddingService,
                eventPublisher,
                clusterSync
        );
    }

    @Test
    @DisplayName("测试管理端新增 FAQ: 单点计算 Embedding、落库、触发本地重载与集群广播")
    void testCreateFaqSuccess() {
        FaqCreateRequest req = new FaqCreateRequest();
        req.setFaqCode("FAQ_SRE_504");
        req.setFaqTitle("504网关超时排障流程");
        req.setSimilarQueries("网关504, 接口504");
        req.setStandardAnswer("1. 检查连接池; 2. 检查慢查");
        req.setTargetAgentCode("SRE_COPILOT_AGENT");

        when(faqRepository.findByFaqCode("FAQ_SRE_504")).thenReturn(Optional.empty());

        float[] mockVector = new float[]{1.0f, 0.0f, 0.0f};
        when(embeddingService.embed("504网关超时排障流程")).thenReturn(mockVector);

        FaqDefinitionEntity savedEntity = new FaqDefinitionEntity();
        savedEntity.setId(10L);
        savedEntity.setFaqCode("FAQ_SRE_504");
        savedEntity.setFaqTitle("504网关超时排障流程");
        savedEntity.setStandardAnswer("1. 检查连接池; 2. 检查慢查");
        savedEntity.setCreatedAt(LocalDateTime.now());
        savedEntity.setUpdatedAt(LocalDateTime.now());

        when(faqRepository.save(any(FaqDefinitionEntity.class))).thenReturn(savedEntity);

        ResponseEntity<?> response = controller.createFaq(req);
        assertEquals(200, response.getStatusCode().value());

        FaqResponse body = (FaqResponse) response.getBody();
        assertNotNull(body);
        assertEquals("FAQ_SRE_504", body.getFaqCode());
        assertEquals("OK", body.getClusterSync());

        // 验证只调用了 1 次 Embedding API
        verify(embeddingService, times(1)).embed("504网关超时排障流程");
        // 验证触发本地事件
        verify(eventPublisher, times(1)).publishEvent(any(L2VectorReloadEvent.class));
        // 验证触发集群广播
        verify(clusterSync, times(1)).publishRevision(anyString());
    }

    @Test
    @DisplayName("测试切换状态与手动重载")
    void testToggleStatusAndReload() {
        FaqDefinitionEntity entity = new FaqDefinitionEntity();
        entity.setId(10L);
        entity.setFaqCode("FAQ_504");
        entity.setIsEnabled(1);

        when(faqRepository.findById(10L)).thenReturn(Optional.of(entity));

        ResponseEntity<?> toggleResp = controller.toggleStatus(10L, 0);
        assertEquals(200, toggleResp.getStatusCode().value());
        assertEquals(0, entity.getIsEnabled());
        verify(eventPublisher, times(1)).publishEvent(any(L2VectorReloadEvent.class));

        ResponseEntity<?> reloadResp = controller.manualReload();
        assertEquals(200, reloadResp.getStatusCode().value());
        verify(vectorRegistry, times(1)).reload();
        verify(clusterSync, times(2)).publishRevision(anyString());
    }
}
