package com.example.springai.web.controller;

import com.example.springai.core.event.L2VectorReloadEvent;
import com.example.springai.core.routing.L2VectorRegistry;
import com.example.springai.core.routing.vector.VectorByteUtils;
import com.example.springai.core.routing.vector.VectorEmbeddingService;
import com.example.springai.infra.persistence.entity.FaqDefinitionEntity;
import com.example.springai.infra.persistence.repository.FaqDefinitionRepository;
import com.example.springai.infra.sync.ClusterSyncResult;
import com.example.springai.infra.sync.L2ClusterSync;
import com.example.springai.web.dto.ApiResponse;
import com.example.springai.web.dto.FaqCreateRequest;
import com.example.springai.web.dto.FaqResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * L2 向量知识库管理后台 REST API (L2FaqAdminController)
 * 职责:
 * 1. 标准问与排障 SOP 增删改查与启停控制;
 * 2. 控制面“单点计算 Embedding 向量”，避免全集群 50 个 Pod 冗余请求;
 * 3. 持久化至 MySQL 后，联动本地 Spring Event 与 Apollo 集群广播实现双重热重载。
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1/admin/faqs")
@Slf4j
@RequiredArgsConstructor
public class L2FaqAdminController {

    private final FaqDefinitionRepository faqRepository;
    private final L2VectorRegistry vectorRegistry;
    private final VectorEmbeddingService embeddingService;
    private final ApplicationEventPublisher eventPublisher;
    private final L2ClusterSync clusterSync;

    @GetMapping
    public ResponseEntity<List<FaqResponse>> listFaqs() {
        List<FaqDefinitionEntity> entities = faqRepository.findAll(Sort.by(Sort.Direction.ASC, "priority"));
        List<FaqResponse> responses = entities.stream()
                .map(FaqResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<FaqDefinitionEntity> all = faqRepository.findAll();
        long totalCount = all.size();
        long enabledCount = all.stream().filter(e -> e.getIsEnabled() != null && e.getIsEnabled() == 1).count();
        long totalHits = all.stream().mapToLong(e -> e.getHitCount() != null ? e.getHitCount() : 0L).sum();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCount", totalCount);
        stats.put("enabledCount", enabledCount);
        stats.put("activeInMemoryCount", vectorRegistry.getActiveCount());
        stats.put("totalHits", totalHits);
        stats.put("lastReloadSuccessful", vectorRegistry.isLastReloadSuccessful());
        stats.put("lastReloadError", vectorRegistry.getLastReloadError());
        return ResponseEntity.ok(stats);
    }

    @PostMapping
    public ResponseEntity<?> createFaq(@RequestBody FaqCreateRequest request) {
        if (!StringUtils.hasText(request.getFaqCode())) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(400, "faqCode 不能为空"));
        }
        if (!StringUtils.hasText(request.getFaqTitle())) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(400, "faqTitle 标准问不能为空"));
        }
        if (!StringUtils.hasText(request.getStandardAnswer())) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(400, "standardAnswer 标准答案不能为空"));
        }

        if (faqRepository.findByFaqCode(request.getFaqCode()).isPresent()) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(400, "faqCode 已存在: " + request.getFaqCode()));
        }

        // 1. 控制面单点计算 Embedding 向量（若模型调用失败则阻断入库，严禁写入跨空间伪造向量）
        float[] vector;
        try {
            vector = embeddingService.embed(request.getFaqTitle());
        } catch (Exception ex) {
            log.error("[L2Admin] 计算 FAQ Embedding 失败，已阻断入库: faqCode={}, reason={}",
                    request.getFaqCode(), ex.getMessage());
            return ResponseEntity.status(503)
                    .body(ApiResponse.fail(503, "Embedding 向量计算失败，已阻断入库: " + ex.getMessage()));
        }
        if (vector == null || vector.length == 0) {
            return ResponseEntity.status(503)
                    .body(ApiResponse.fail(503, "Embedding 向量为空，已阻断入库"));
        }
        byte[] vectorBytes = VectorByteUtils.toBytes(vector);
        String actualModel = StringUtils.hasText(embeddingService.modelName())
                ? embeddingService.modelName().trim()
                : "text-embedding-3-small";

        FaqDefinitionEntity entity = new FaqDefinitionEntity();
        entity.setFaqCode(request.getFaqCode());
        entity.setFaqTitle(request.getFaqTitle());
        entity.setSimilarQueries(request.getSimilarQueries());
        entity.setStandardAnswer(request.getStandardAnswer());
        entity.setTargetAgentCode(request.getTargetAgentCode());
        entity.setEmbeddingVector(vectorBytes);
        entity.setEmbeddingModel(actualModel);
        entity.setDimension(vector.length);
        entity.setPriority(request.getPriority() != null ? request.getPriority() : 100);
        entity.setIsEnabled(request.getIsEnabled() != null ? request.getIsEnabled() : 1);
        entity.setDescription(request.getDescription());

        FaqDefinitionEntity saved = faqRepository.save(entity);
        log.info("[L2Admin] 新建 FAQ 成功: faqCode={}, id={}", saved.getFaqCode(), saved.getId());

        // 2. 本地事件重载
        eventPublisher.publishEvent(new L2VectorReloadEvent(this, "新建 FAQ: " + saved.getFaqCode()));

        // 3. 集群版本号广播
        ClusterSyncResult syncResult = clusterSync.publishRevision("新建 FAQ: " + saved.getFaqCode());

        FaqResponse response = FaqResponse.fromEntity(saved);
        response.setClusterSync(syncResult != null ? syncResult.getStatus() : "SKIPPED");
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> toggleStatus(@PathVariable("id") Long id, @RequestParam("enabled") Integer enabled) {
        Optional<FaqDefinitionEntity> opt = faqRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FaqDefinitionEntity entity = opt.get();
        entity.setIsEnabled(enabled != null && enabled == 1 ? 1 : 0);
        faqRepository.save(entity);
        log.info("[L2Admin] 切换 FAQ 状态: id={}, code={}, enabled={}", id, entity.getFaqCode(), entity.getIsEnabled());

        eventPublisher.publishEvent(new L2VectorReloadEvent(this, "更新 FAQ 状态: " + entity.getFaqCode()));
        clusterSync.publishRevision("更新 FAQ 状态: " + entity.getFaqCode());

        return ResponseEntity.ok(ApiResponse.ok("状态更新成功", FaqResponse.fromEntity(entity)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFaq(@PathVariable("id") Long id) {
        Optional<FaqDefinitionEntity> opt = faqRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FaqDefinitionEntity entity = opt.get();
        faqRepository.delete(entity);
        log.info("[L2Admin] 删除 FAQ: id={}, code={}", id, entity.getFaqCode());

        eventPublisher.publishEvent(new L2VectorReloadEvent(this, "删除 FAQ: " + entity.getFaqCode()));
        clusterSync.publishRevision("删除 FAQ: " + entity.getFaqCode());

        return ResponseEntity.ok(ApiResponse.ok("删除成功", null));
    }

    @PostMapping("/reload")
    public ResponseEntity<?> manualReload() {
        vectorRegistry.reload();
        ClusterSyncResult syncResult = clusterSync.publishRevision("手动触发集群重载");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeCount", vectorRegistry.getActiveCount());
        data.put("clusterSync", syncResult != null ? syncResult.getStatus() : "SKIPPED");
        return ResponseEntity.ok(ApiResponse.ok("重载完成", data));
    }
}
