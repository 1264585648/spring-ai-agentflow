package com.example.springai.core.routing.vector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量二进制字节编解码与数学计算工具 (VectorByteUtils)
 * 职责:
 * 1. float[] 与 byte[] 之间的高性能无锁二进制互转 (节省 80% 存储并免除 JSON 解析);
 * 2. 向量点积 (Dot Product) 与余弦相似度 (Cosine Similarity) 快速计算;
 * 3. 向量 L2 范数归一化处理。
 */
public final class VectorByteUtils {

    private VectorByteUtils() {
    }

    /**
     * 将 float[] 转换为二进制字节数组 (Little Endian)
     */
    public static byte[] toBytes(float[] vector) {
        if (vector == null || vector.length == 0) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    /**
     * 从二进制字节数组中反序列化恢复 float[]
     */
    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    /**
     * 计算两个向量的点积 (Dot Product)
     */
    public static float dotProduct(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            return 0.0f;
        }
        float sum = 0.0f;
        for (int i = 0; i < v1.length; i++) {
            sum += v1[i] * v2[i];
        }
        return sum;
    }

    /**
     * 计算两个向量的余弦相似度 (Cosine Similarity)
     * 若两向量皆已归一化，则余弦相似度与点积相等。
     */
    public static double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            return 0.0;
        }
        double dot = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += (double) v1[i] * (double) v2[i];
            norm1 += (double) v1[i] * (double) v1[i];
            norm2 += (double) v2[i] * (double) v2[i];
        }
        if (norm1 <= 0.0 || norm2 <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 对向量进行 L2 归一化 (Unit Vector)
     */
    public static float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            return new float[0];
        }
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += (double) v * (double) v;
        }
        double norm = Math.sqrt(sumSq);
        if (norm == 0.0) {
            return vector.clone();
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }
}
