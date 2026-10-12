package com.example.springai.core.routing.vector;

/**
 * 向量检索候选命中结果 (VectorCandidate)
 */
public record VectorCandidate(
        FaqItem item,
        double score
) {
}
