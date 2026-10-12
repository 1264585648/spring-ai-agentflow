package com.example.springai.core.routing.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VectorByteUtilsTest {

    @Test
    @DisplayName("测试 float[] 与 byte[] 二进制互转的无损还原与精度")
    void testToBytesAndFromBytes() {
        float[] original = new float[]{1.0f, -0.5f, 0.123456f, 999.99f};
        byte[] bytes = VectorByteUtils.toBytes(original);

        assertNotNull(bytes);
        assertEquals(original.length * 4, bytes.length);

        float[] restored = VectorByteUtils.fromBytes(bytes);
        assertNotNull(restored);
        assertEquals(original.length, restored.length);

        for (int i = 0; i < original.length; i++) {
            assertEquals(original[i], restored[i], 1e-6f);
        }

        // 测试空与边界
        assertEquals(0, VectorByteUtils.toBytes(null).length);
        assertEquals(0, VectorByteUtils.toBytes(new float[0]).length);
        assertEquals(0, VectorByteUtils.fromBytes(null).length);
        assertEquals(0, VectorByteUtils.fromBytes(new byte[0]).length);
    }

    @Test
    @DisplayName("测试点积与余弦相似度计算")
    void testCosineSimilarityAndDotProduct() {
        float[] v1 = new float[]{1.0f, 0.0f, 0.0f};
        float[] v2 = new float[]{1.0f, 0.0f, 0.0f};
        float[] v3 = new float[]{0.0f, 1.0f, 0.0f};
        float[] v4 = new float[]{-1.0f, 0.0f, 0.0f};

        // 完全相同向量: 相似度为 1.0
        assertEquals(1.0, VectorByteUtils.cosineSimilarity(v1, v2), 1e-6);
        assertEquals(1.0f, VectorByteUtils.dotProduct(v1, v2), 1e-6f);

        // 正交向量: 相似度为 0.0
        assertEquals(0.0, VectorByteUtils.cosineSimilarity(v1, v3), 1e-6);

        // 相反向量: 相似度为 -1.0
        assertEquals(-1.0, VectorByteUtils.cosineSimilarity(v1, v4), 1e-6);
    }

    @Test
    @DisplayName("测试向量归一化")
    void testNormalize() {
        float[] unnormalized = new float[]{3.0f, 4.0f};
        float[] normalized = VectorByteUtils.normalize(unnormalized);

        assertEquals(0.6f, normalized[0], 1e-6f);
        assertEquals(0.8f, normalized[1], 1e-6f);

        // 归一化后的自身点积应为 1.0
        assertEquals(1.0f, VectorByteUtils.dotProduct(normalized, normalized), 1e-6f);
    }
}
