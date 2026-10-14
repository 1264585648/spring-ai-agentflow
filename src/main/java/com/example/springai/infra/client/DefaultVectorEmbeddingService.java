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
    public static final String DEFAULT_COMMERCIAL_MODEL = "text-embedding-3-small";
    public static final String LOCAL_DETERMINISTIC_MODEL = "local-deterministic-md5-ngram-v1";

    private final EmbeddingModel embeddingModel;
    private final String configuredModelName;
    private final boolean localFallbackEnabled;

    @Autowired
    public DefaultVectorEmbeddingService(
            @Autowired(required = false) EmbeddingModel embeddingModel,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String configuredModelName,
            @org.springframework.beans.factory.annotation.Value("${agent.l2.embedding.local-fallback-enabled:true}") boolean localFallbackEnabled) {
        this.embeddingModel = embeddingModel;
        this.configuredModelName = StringUtils.hasText(configuredModelName)
                ? configuredModelName.trim()
                : DEFAULT_COMMERCIAL_MODEL;
        this.localFallbackEnabled = localFallbackEnabled;
        if (embeddingModel != null) {
            log.info("[EmbeddingService] 成功装配 Spring AI EmbeddingModel: {}, modelName={}",
                    embeddingModel.getClass().getSimpleName(), this.configuredModelName);
        } else if (localFallbackEnabled) {
            log.warn("[EmbeddingService] 未检测到商业 EmbeddingModel Bean，启用受控本地模拟投影空间: {}", LOCAL_DETERMINISTIC_MODEL);
        } else {
            log.warn("[EmbeddingService] 未检测到商业 EmbeddingModel Bean 且已禁用本地模拟投影");
        }
    }

    public DefaultVectorEmbeddingService(EmbeddingModel embeddingModel) {
        this(embeddingModel, DEFAULT_COMMERCIAL_MODEL, true);
    }

    public DefaultVectorEmbeddingService(EmbeddingModel embeddingModel, boolean localFallbackEnabled) {
        this(embeddingModel, DEFAULT_COMMERCIAL_MODEL, localFallbackEnabled);
    }

    @Override
    public float[] embed(String text) {
        if (!StringUtils.hasText(text)) {
            return new float[dimensions()];
        }

        // 已配置真实商业模型时：失败必须显式抛出异常，严禁静默降级为本地 MD5 假向量污染索引空间
        if (embeddingModel != null) {
            try {
                float[] result = embeddingModel.embed(text);
                if (result == null || result.length == 0) {
                    throw new IllegalStateException("商业 EmbeddingModel 返回空向量");
                }
                return VectorByteUtils.normalize(result);
            } catch (Exception ex) {
                log.error("[EmbeddingService] 调用商业 EmbeddingModel 失败，拒绝伪造本地向量: {}", ex.getMessage());
                throw new IllegalStateException("Embedding 模型调用失败: " + ex.getMessage(), ex);
            }
        }

        if (!localFallbackEnabled) {
            throw new IllegalStateException("未配置可用 EmbeddingModel，且已禁用本地模拟向量兜底");
        }

        // 仅在无商业模型且显式允许本地模拟（离线 Demo / 单元测试）时使用独立模型空间的确定性投影
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

    @Override
    public String modelName() {
        if (embeddingModel != null) {
            return configuredModelName;
        }
        return LOCAL_DETERMINISTIC_MODEL;
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
