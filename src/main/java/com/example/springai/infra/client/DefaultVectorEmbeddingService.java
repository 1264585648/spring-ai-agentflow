package com.example.springai.infra.client;

import com.example.springai.core.routing.vector.VectorByteUtils;
import com.example.springai.core.routing.vector.VectorEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 向量嵌入计算默认实现 (DefaultVectorEmbeddingService)
 * 职责:
 * 1. 优先调用 Spring AI 自动装配的商业 EmbeddingModel (如 text-embedding-3-small);
 * 2. 在离线、单测或无 API Key 环境下，自动平滑降级为确定性投影向量，保证 100% 可用性与测试稳定性。
 */
@Service
@Slf4j
public class DefaultVectorEmbeddingService implements VectorEmbeddingService {

    public static final int DEFAULT_DIMENSION = 1536;

    private final EmbeddingModel embeddingModel;

    @Autowired
    public DefaultVectorEmbeddingService(@Autowired(required = false) EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        if (embeddingModel != null) {
            log.info("[EmbeddingService] 成功装配 Spring AI EmbeddingModel: {}", embeddingModel.getClass().getSimpleName());
        } else {
            log.warn("[EmbeddingService] 未检测到商业 EmbeddingModel Bean，将启用本地确定性模拟投影兜底");
        }
    }

    @Override
    public float[] embed(String text) {
        if (!StringUtils.hasText(text)) {
            return new float[DEFAULT_DIMENSION];
        }

        if (embeddingModel != null) {
            try {
                float[] result = embeddingModel.embed(text);
                if (result != null && result.length > 0) {
                    return VectorByteUtils.normalize(result);
                }
            } catch (Exception ex) {
                log.warn("[EmbeddingService] 调用商业 EmbeddingModel 异常，降级至本地确定性投影: {}", ex.getMessage());
            }
        }

        // 本地确定性哈希投影 (离线与单元测试保障)
        return generateDeterministicVector(text, DEFAULT_DIMENSION);
    }

    @Override
    public int dimensions() {
        if (embeddingModel != null) {
            try {
                int dims = embeddingModel.dimensions();
                if (dims > 0) return dims;
            } catch (Exception ignored) {
            }
        }
        return DEFAULT_DIMENSION;
    }

    /**
     * 基于多重哈希与 N-Gram 词袋生成确定性浮点向量并归一化
     */
    public static float[] generateDeterministicVector(String text, int dimension) {
        float[] vector = new float[dimension];
        String clean = text.trim().toLowerCase();

        // 1. 全局 MD5 分布打散
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(clean.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < digest.length; i++) {
                int idx = Math.abs((i * 97 + digest[i]) % dimension);
                vector[idx] += (float) (digest[i] & 0xFF) / 255.0f;
            }
        } catch (Exception ignored) {
        }

        // 2. 字符与 2-gram 局部语义特征注入 (确保相似词句具有较高的内积相似度)
        char[] chars = clean.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int charHash = chars[i];
            int idx1 = Math.abs(charHash * 31 % dimension);
            vector[idx1] += 1.0f;

            if (i < chars.length - 1) {
                int biGramHash = (chars[i] << 8) | chars[i + 1];
                int idx2 = Math.abs(biGramHash * 41 % dimension);
                vector[idx2] += 2.0f;
            }
        }

        return VectorByteUtils.normalize(vector);
    }
}
