package com.example.springai.core.routing;

import com.example.springai.core.context.AgentContext;
import com.example.springai.core.routing.impl.L2VectorResolver;
import com.example.springai.core.routing.vector.FaqItem;
import com.example.springai.core.routing.vector.VectorCandidate;
import com.example.springai.core.routing.vector.VectorEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class L2VectorResolverTest {

    private L2VectorRegistry registry;
    private VectorEmbeddingService embeddingService;
    private L2VectorResolver resolver;

    @BeforeEach
    void setUp() {
        registry = mock(L2VectorRegistry.class);
        embeddingService = mock(VectorEmbeddingService.class);
        resolver = new L2VectorResolver(registry, embeddingService);
    }

    @Test
    @DisplayName("测试高置信度 (>=0.88) 命中: 短路直出 Resolution.DirectReply，零 LLM 调用")
    void testHighConfidenceDirectReply() {
        AgentContext context = AgentContext.builder()
                .sessionId("sess-1")
                .turnId("turn-1")
                .query("网关报504了怎么办")
                .effectiveQuery("网关报504了怎么办")
                .attributes(new HashMap<>())
                .build();

        float[] mockVector = new float[]{1.0f, 0.0f, 0.0f};
        when(registry.getActiveCount()).thenReturn(1);
        when(embeddingService.embed("网关报504了怎么办")).thenReturn(mockVector);

        FaqItem faq504 = new FaqItem(
                "FAQ_SRE_504",
                "504网关超时排障流程",
                List.of("504超时"),
                "1. 检查连接池; 2. 检查慢查",
                "SRE_COPILOT_AGENT",
                mockVector,
                "test-model",
                3,
                100
        );

        // 模拟相似度 0.95 (>= 0.88)
        when(registry.searchTop1(mockVector, 0.70))
                .thenReturn(Optional.of(new VectorCandidate(faq504, 0.95)));

        Optional<Resolution> resolutionOpt = resolver.resolve(context);
        assertTrue(resolutionOpt.isPresent());

        Resolution resolution = resolutionOpt.get();
        assertInstanceOf(Resolution.DirectReply.class, resolution);

        Resolution.DirectReply direct = (Resolution.DirectReply) resolution;
        assertEquals("L2", direct.level());
        assertEquals("FAQ_SRE_504", direct.ruleCode());
        assertEquals("1. 检查连接池; 2. 检查慢查", direct.text());

        // 验证记录命中数
        verify(registry, times(1)).recordHitCountAsync("FAQ_SRE_504");
    }

    @Test
    @DisplayName("测试中置信度 (0.70~0.88) 命中: 知识增强路由 Resolution.Route 并挂载上下文")
    void testMediumConfidenceRouteWithContext() {
        AgentContext context = AgentContext.builder()
                .sessionId("sess-1")
                .turnId("turn-1")
                .query("服务有点卡慢")
                .effectiveQuery("服务有点卡慢")
                .attributes(new HashMap<>())
                .build();

        float[] mockVector = new float[]{0.5f, 0.5f, 0.0f};
        when(registry.getActiveCount()).thenReturn(1);
        when(embeddingService.embed("服务有点卡慢")).thenReturn(mockVector);

        FaqItem faqDb = new FaqItem(
                "FAQ_DB_POOL",
                "数据库连接池打满排障",
                List.of(),
                "查看慢查与长事务",
                "DB_DIAGNOSE_AGENT",
                mockVector,
                "test-model",
                3,
                100
        );

        // 模拟相似度 0.78 (0.70 <= score < 0.88)
        when(registry.searchTop1(mockVector, 0.70))
                .thenReturn(Optional.of(new VectorCandidate(faqDb, 0.78)));

        Optional<Resolution> resolutionOpt = resolver.resolve(context);
        assertTrue(resolutionOpt.isPresent());

        Resolution resolution = resolutionOpt.get();
        assertInstanceOf(Resolution.Route.class, resolution);

        Resolution.Route route = (Resolution.Route) resolution;
        assertEquals("L2", route.level());
        assertFalse(route.steps().isEmpty());
        assertEquals("DB_DIAGNOSE_AGENT", route.steps().get(0).getTargetAgent());

        // 验证上下文 attributes 中成功注入知识提示
        assertEquals("查看慢查与长事务", context.getAttributes().get("L2_HINT_ANSWER"));
        assertEquals("FAQ_DB_POOL", context.getAttributes().get("L2_HINT_FAQ_CODE"));
    }

    @Test
    @DisplayName("测试低置信度 (<0.70) 未命中: 返回 Optional.empty() 放行至 L3")
    void testLowConfidenceFallthrough() {
        AgentContext context = AgentContext.builder()
                .sessionId("sess-1")
                .turnId("turn-1")
                .query("请帮我写一段冒泡排序代码")
                .effectiveQuery("请帮我写一段冒泡排序代码")
                .attributes(new HashMap<>())
                .build();

        float[] mockVector = new float[]{0.0f, 0.0f, 1.0f};
        when(registry.getActiveCount()).thenReturn(1);
        when(embeddingService.embed("请帮我写一段冒泡排序代码")).thenReturn(mockVector);

        // 相似度未达 0.70，searchTop1 返回 empty
        when(registry.searchTop1(mockVector, 0.70)).thenReturn(Optional.empty());

        Optional<Resolution> resolutionOpt = resolver.resolve(context);
        assertTrue(resolutionOpt.isEmpty(), "低置信度应返回 empty 平滑下沉至 L3");
    }

    @Test
    @DisplayName("测试空 Query 或空本地库时快速放行")
    void testEmptyConditions() {
        // 空 query
        AgentContext emptyContext = AgentContext.builder().query("").effectiveQuery("").build();
        assertTrue(resolver.resolve(emptyContext).isEmpty());

        // 本地库条目数为 0
        AgentContext normalContext = AgentContext.builder().query("ping").build();
        when(registry.getActiveCount()).thenReturn(0);
        assertTrue(resolver.resolve(normalContext).isEmpty());
    }
}
