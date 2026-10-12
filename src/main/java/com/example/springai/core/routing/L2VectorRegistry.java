package com.example.springai.core.routing;

import com.example.springai.core.event.L2VectorReloadEvent;
import com.example.springai.core.routing.vector.FaqItem;
import com.example.springai.core.routing.vector.VectorByteUtils;
import com.example.springai.core.routing.vector.VectorCandidate;
import com.example.springai.core.routing.vector.VectorSnapshot;
import com.example.springai.infra.persistence.entity.FaqDefinitionEntity;
import com.example.springai.infra.persistence.repository.FaqDefinitionRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * L2 运行时向量知识高并发内存注册表 (L2VectorRegistry)
 * 核心架构:
 * 1. 读写分离: 读走本地堆内存无锁快照 (VectorSnapshot)，耗时 < 1ms；
 * 2. 零停机热重载: 基于 AtomicReference 实现 Copy-On-Write 原子快照替换；
 * 3. 零额外 Embedding 计费: 直接反序列化 MySQL 中预先计算好的二进制向量；
 * 4. 旁路异步审计: 命中计数异步落库，绝不阻塞用户通信主线程。
 */
@Component
@Slf4j
public class L2VectorRegistry {

    private final FaqDefinitionRepository faqRepository;
    private final Executor asyncExecutor;

    /**
     * 运行态只读快照容器 (无锁 CAS 引用)
     */
    private final AtomicReference<VectorSnapshot> activeSnapshotHolder =
            new AtomicReference<>(VectorSnapshot.empty());

    private volatile boolean lastReloadSuccessful;
    private volatile String lastReloadError;

    public L2VectorRegistry(FaqDefinitionRepository faqRepository,
                            @Qualifier("agentAsyncPostExecutor") Executor asyncExecutor) {
        this.faqRepository = faqRepository;
        this.asyncExecutor = asyncExecutor;
    }

    @PostConstruct
    public void init() {
        log.info("[L2VectorRegistry] 正在从 MySQL 预热加载 L2 向量知识快照...");
        reload();
    }

    @EventListener(L2VectorReloadEvent.class)
    public void onReload(L2VectorReloadEvent event) {
        log.info("[L2VectorRegistry] 接收到 L2 向量重载事件，触发原因: {}", event.getReason());
        reload();
    }

    /**
     * 执行全量重载 (Copy-On-Write 原子替换)
     */
    public synchronized void reload() {
        long startTime = System.currentTimeMillis();
        try {
            List<FaqDefinitionEntity> entities = faqRepository.findByIsEnabledOrderByPriorityAsc(1);
            List<FaqItem> newItems = new ArrayList<>();
            List<String> skippedCodes = new ArrayList<>();

            for (FaqDefinitionEntity entity : entities) {
                try {
                    float[] vector = VectorByteUtils.fromBytes(entity.getEmbeddingVector());
                    if (vector == null || vector.length == 0) {
                        skippedCodes.add(entity.getFaqCode());
                        log.warn("[L2VectorRegistry] FAQ {} 缺少向量数据，跳过加载", entity.getFaqCode());
                        continue;
                    }

                    // 统一归一化处理，确保点积等价于余弦相似度
                    float[] normalizedVector = VectorByteUtils.normalize(vector);

                    List<String> similarQueries = parseSimilarQueries(entity.getSimilarQueries());

                    FaqItem item = new FaqItem(
                            entity.getFaqCode(),
                            entity.getFaqTitle(),
                            similarQueries,
                            entity.getStandardAnswer(),
                            entity.getTargetAgentCode(),
                            normalizedVector,
                            entity.getEmbeddingModel(),
                            entity.getDimension() != null ? entity.getDimension() : normalizedVector.length,
                            entity.getPriority() != null ? entity.getPriority() : 100
                    );
                    newItems.add(item);
                } catch (Exception ex) {
                    skippedCodes.add(entity.getFaqCode());
                    log.error("[L2VectorRegistry] 加载 FAQ {} 异常: {}", entity.getFaqCode(), ex.getMessage());
                }
            }

            VectorSnapshot snapshot = new VectorSnapshot(newItems, System.currentTimeMillis());
            activeSnapshotHolder.set(snapshot);
            lastReloadSuccessful = skippedCodes.isEmpty();
            lastReloadError = null;

            long cost = System.currentTimeMillis() - startTime;
            log.info("[L2VectorRegistry] L2 向量快照重载完成，生效条目: {}, 跳过: {}, 耗时: {}ms",
                    newItems.size(), skippedCodes, cost);
        } catch (Exception e) {
            lastReloadSuccessful = false;
            lastReloadError = e.getMessage();
            log.error("[L2VectorRegistry] L2 向量快照重载失败，保持上一次内存快照运行: {}", e.getMessage(), e);
        }
    }

    /**
     * 极速无锁检索 Top-1
     *
     * @param queryVector 归一化后的用户 Query 向量
     * @param threshold   最低初筛门槛
     */
    public Optional<VectorCandidate> searchTop1(float[] queryVector, double threshold) {
        if (queryVector == null || queryVector.length == 0) {
            return Optional.empty();
        }
        return activeSnapshotHolder.get().searchTop1(queryVector, threshold);
    }

    /**
     * 极速无锁检索 Top-K
     */
    public List<VectorCandidate> searchTopK(float[] queryVector, int topK, double threshold) {
        if (queryVector == null || queryVector.length == 0) {
            return Collections.emptyList();
        }
        return activeSnapshotHolder.get().searchTopK(queryVector, topK, threshold);
    }

    /**
     * 获取当前生效的快照
     */
    public VectorSnapshot getActiveSnapshot() {
        return activeSnapshotHolder.get();
    }

    public int getActiveCount() {
        return activeSnapshotHolder.get().size();
    }

    public boolean isLastReloadSuccessful() {
        return lastReloadSuccessful;
    }

    public String getLastReloadError() {
        return lastReloadError;
    }

    /**
     * 旁路异步递增命中次数
     */
    public void recordHitCountAsync(String faqCode) {
        try {
            asyncExecutor.execute(() -> {
                try {
                    faqRepository.incrementHitCount(faqCode);
                } catch (Exception e) {
                    log.warn("[L2VectorRegistry] 异步累加命中数失败 (faqCode={}): {}", faqCode, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[L2VectorRegistry] 提交异步累加任务失败: {}", e.getMessage());
        }
    }

    private List<String> parseSimilarQueries(String similarQueriesStr) {
        if (similarQueriesStr == null || similarQueriesStr.trim().isEmpty()) {
            return Collections.emptyList();
        }
        // 支持逗号分隔或简单 JSON 数组
        String clean = similarQueriesStr.trim();
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        String[] parts = clean.split("[,;，]");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim().replace("\"", "").replace("'", "");
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
