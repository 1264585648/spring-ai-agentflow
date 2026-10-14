package com.example.springai.core.routing.vector;

/**
 * 向量嵌入计算统一服务端口 (VectorEmbeddingService)
 */
public interface VectorEmbeddingService {

    /**
     * 将输入文本转换为浮点向量
     *
     * @param text 输入文本
     * @return 归一化后的浮点向量
     */
    float[] embed(String text);

    /**
     * 向量维度
     */
    int dimensions();

    /**
     * 当前生成向量所属的模型空间标识（用于入库标记与跨空间隔离校验）
     */
    default String modelName() {
        return "text-embedding-3-small";
    }
}
