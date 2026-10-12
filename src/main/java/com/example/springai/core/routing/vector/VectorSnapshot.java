package com.example.springai.core.routing.vector;

import java.util.*;

/**
 * L2 运行时不可变内存向量快照 (VectorSnapshot)
 * 职责:
 * 1. 纯堆内无锁遍历点积与余弦相似度计算，全流程 < 1ms;
 * 2. 保证并发线程读安全 (Copy-On-Write 原子替换载体);
 * 3. 过滤低于初筛阈值的候选条目。
 */
public class VectorSnapshot {

    private final List<FaqItem> items;
    private final long loadedAt;

    public VectorSnapshot(List<FaqItem> items, long loadedAt) {
        this.items = items != null ? List.copyOf(items) : Collections.emptyList();
        this.loadedAt = loadedAt;
    }

    public static VectorSnapshot empty() {
        return new VectorSnapshot(Collections.emptyList(), System.currentTimeMillis());
    }

    public List<FaqItem> getItems() {
        return items;
    }

    public int size() {
        return items.size();
    }

    public long getLoadedAt() {
        return loadedAt;
    }

    /**
     * 极速检索 Top-1 匹配项
     *
     * @param queryVector 归一化后的用户 Query 向量
     * @param threshold   最低置信度初筛门槛 (如 0.70)
     * @return 达到门槛的最佳候选，若无则返回 empty
     */
    public Optional<VectorCandidate> searchTop1(float[] queryVector, double threshold) {
        if (items.isEmpty() || queryVector == null || queryVector.length == 0) {
            return Optional.empty();
        }

        double maxScore = -1.0;
        FaqItem bestMatch = null;

        for (FaqItem item : items) {
            float[] targetVector = item.vector();
            if (targetVector.length != queryVector.length) {
                continue; // 维度不匹配直接跳过，防止空间错乱
            }
            double score = VectorByteUtils.cosineSimilarity(queryVector, targetVector);
            if (score > maxScore) {
                maxScore = score;
                bestMatch = item;
            }
        }

        if (bestMatch != null && maxScore >= threshold) {
            return Optional.of(new VectorCandidate(bestMatch, maxScore));
        }
        return Optional.empty();
    }

    /**
     * 检索 Top-K 匹配项 (按相似度降序排列)
     */
    public List<VectorCandidate> searchTopK(float[] queryVector, int topK, double threshold) {
        if (items.isEmpty() || queryVector == null || queryVector.length == 0 || topK <= 0) {
            return Collections.emptyList();
        }

        List<VectorCandidate> candidates = new ArrayList<>();
        for (FaqItem item : items) {
            float[] targetVector = item.vector();
            if (targetVector.length != queryVector.length) {
                continue;
            }
            double score = VectorByteUtils.cosineSimilarity(queryVector, targetVector);
            if (score >= threshold) {
                candidates.add(new VectorCandidate(item, score));
            }
        }

        candidates.sort(Comparator.comparingDouble(VectorCandidate::score).reversed());
        if (candidates.size() > topK) {
            return candidates.subList(0, topK);
        }
        return candidates;
    }
}
