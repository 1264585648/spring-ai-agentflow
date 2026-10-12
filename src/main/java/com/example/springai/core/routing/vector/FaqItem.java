package com.example.springai.core.routing.vector;

import java.util.Collections;
import java.util.List;

/**
 * 运行时不可变 FAQ 标准问条目 (FaqItem)
 * 供 L2 内存向量快照进行纯堆内无锁点积检索。
 */
public record FaqItem(
        String faqCode,
        String faqTitle,
        List<String> similarQueries,
        String standardAnswer,
        String targetAgentCode,
        float[] vector,
        String embeddingModel,
        int dimension,
        int priority
) {
    public FaqItem {
        similarQueries = similarQueries != null ? List.copyOf(similarQueries) : Collections.emptyList();
        vector = vector != null ? vector.clone() : new float[0];
    }

    @Override
    public float[] vector() {
        return vector != null ? vector.clone() : new float[0];
    }
}
