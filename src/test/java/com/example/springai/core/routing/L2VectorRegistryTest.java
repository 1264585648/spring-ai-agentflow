package com.example.springai.core.routing;

import com.example.springai.core.event.L2VectorReloadEvent;
import com.example.springai.core.routing.vector.VectorByteUtils;
import com.example.springai.core.routing.vector.VectorCandidate;
import com.example.springai.infra.persistence.entity.FaqDefinitionEntity;
import com.example.springai.infra.persistence.repository.FaqDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class L2VectorRegistryTest {

    private FaqDefinitionRepository faqRepository;
    private Executor asyncExecutor;
    private L2VectorRegistry registry;

    @BeforeEach
    void setUp() {
        faqRepository = mock(FaqDefinitionRepository.class);
        // 使用同步直接执行的 Executor，方便测试断言
        asyncExecutor = Runnable::run;

        // 构造测试数据
        float[] v504 = new float[]{1.0f, 0.0f, 0.0f};
        FaqDefinitionEntity entity504 = new FaqDefinitionEntity(
                1L,
                "FAQ_SRE_504",
                "504网关超时排障流程",
                "网关504,504超时",
                "1. 检查连接池; 2. 检查慢查",
                "SRE_COPILOT_AGENT",
                VectorByteUtils.toBytes(v504),
                "test-model",
                3,
                100,
                1,
                0L,
                "504 SOP",
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(faqRepository.findByIsEnabledOrderByPriorityAsc(1)).thenReturn(List.of(entity504));

        registry = new L2VectorRegistry(faqRepository, asyncExecutor);
        registry.init();
    }

    @Test
    @DisplayName("测试 L2VectorRegistry 启动预热成功并完成索引快照构建")
    void testInitPreheat() {
        assertEquals(1, registry.getActiveCount());
        assertTrue(registry.isLastReloadSuccessful());
        assertNull(registry.getLastReloadError());

        // 检索完全相同的向量，相似度应为 1.0
        float[] query = new float[]{1.0f, 0.0f, 0.0f};
        Optional<VectorCandidate> candidateOpt = registry.searchTop1(query, 0.88);
        assertTrue(candidateOpt.isPresent());
        assertEquals("FAQ_SRE_504", candidateOpt.get().item().faqCode());
        assertEquals(1.0, candidateOpt.get().score(), 1e-5);
    }

    @Test
    @DisplayName("测试 L2VectorReloadEvent 事件触发内存 Copy-On-Write 热重载")
    void testReloadEvent() {
        float[] vDb = new float[]{0.0f, 1.0f, 0.0f};
        FaqDefinitionEntity entityDb = new FaqDefinitionEntity(
                2L,
                "FAQ_DB_POOL",
                "数据库连接池打满排障",
                "连接池爆满",
                "查看慢查与长事务",
                "DB_DIAGNOSE_AGENT",
                VectorByteUtils.toBytes(vDb),
                "test-model",
                3,
                50,
                1,
                0L,
                "DB SOP",
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        // 模拟数据库更新了新记录
        when(faqRepository.findByIsEnabledOrderByPriorityAsc(1)).thenReturn(List.of(entityDb));

        registry.onReload(new L2VectorReloadEvent(this, "新增 DB SOP"));

        assertEquals(1, registry.getActiveCount());
        float[] queryDb = new float[]{0.0f, 1.0f, 0.0f};
        Optional<VectorCandidate> candidate = registry.searchTop1(queryDb, 0.88);
        assertTrue(candidate.isPresent());
        assertEquals("FAQ_DB_POOL", candidate.get().item().faqCode());
    }

    @Test
    @DisplayName("测试异步累加命中数调用")
    void testRecordHitCountAsync() {
        registry.recordHitCountAsync("FAQ_SRE_504");
        verify(faqRepository, times(1)).incrementHitCount("FAQ_SRE_504");
    }
}
