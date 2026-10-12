package com.example.springai.core.routing.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VectorSnapshotTest {

    @Test
    @DisplayName("测试 VectorSnapshot 内存点积检索 Top-1 与阈值过滤")
    void testSearchTop1WithThreshold() {
        FaqItem faq504 = new FaqItem(
                "FAQ_SRE_504",
                "504网关超时排障流程",
                List.of("网关报504怎么查"),
                "1. 检查上游连接池; 2. 检查慢查",
                "SRE_COPILOT_AGENT",
                new float[]{1.0f, 0.0f, 0.0f},
                "test-model",
                3,
                100
        );

        FaqItem faqDb = new FaqItem(
                "FAQ_DB_POOL",
                "数据库连接池打满排障",
                List.of("数据库卡慢"),
                "查看活跃线程与长事务",
                "DB_DIAGNOSE_AGENT",
                new float[]{0.0f, 1.0f, 0.0f},
                "test-model",
                3,
                100
        );

        VectorSnapshot snapshot = new VectorSnapshot(List.of(faq504, faqDb), System.currentTimeMillis());

        // 1. 输入与 504 极其相似的 query
        float[] querySimilar504 = new float[]{0.95f, 0.05f, 0.0f};
        Optional<VectorCandidate> top1 = snapshot.searchTop1(querySimilar504, 0.88);
        assertTrue(top1.isPresent());
        assertEquals("FAQ_SRE_504", top1.get().item().faqCode());
        assertTrue(top1.get().score() >= 0.88);

        // 2. 输入偏向 DB 但相似度略低的 query (假设置信度在 0.70~0.88 之间)
        float[] queryMidDb = new float[]{0.4f, 0.8f, 0.0f}; // cos sim 与 [0, 1, 0] 约为 0.894，先测更高门槛
        Optional<VectorCandidate> topDbStrict = snapshot.searchTop1(queryMidDb, 0.95);
        assertFalse(topDbStrict.isPresent(), "相似度未达 0.95 时应被门槛过滤返回 empty");

        Optional<VectorCandidate> topDbNormal = snapshot.searchTop1(queryMidDb, 0.70);
        assertTrue(topDbNormal.isPresent());
        assertEquals("FAQ_DB_POOL", topDbNormal.get().item().faqCode());

        // 3. 完全正交的不相关 query
        float[] queryIrrelevant = new float[]{0.0f, 0.0f, 1.0f};
        Optional<VectorCandidate> irrelevant = snapshot.searchTop1(queryIrrelevant, 0.70);
        assertFalse(irrelevant.isPresent(), "无关 query 应返回 empty");
    }
}
