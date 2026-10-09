package com.example.springai.admin.controller;

import com.example.springai.admin.dto.RuleCreateRequest;
import com.example.springai.admin.dto.RuleResponse;
import com.example.springai.pipeline.dispatcher.L1ToolCatalog;
import com.example.springai.pipeline.entity.RuleDefinitionEntity;
import com.example.springai.pipeline.event.L1RuleReloadEvent;
import com.example.springai.pipeline.intent.L1RuleRegistry;
import com.example.springai.pipeline.repository.RuleDefinitionRepository;
import com.example.springai.pipeline.sync.ClusterSyncResult;
import com.example.springai.pipeline.sync.L1ClusterSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * L1 规则管理后台 REST API 控制器。
 * 落库后先完成本机热重载，再按配置广播版本号。广播失败不回滚本机快照。
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1/admin/rules")
public class L1RuleAdminController {

    private static final Logger log = LoggerFactory.getLogger(L1RuleAdminController.class);

    private static final Set<String> MATCH_TYPES = Set.of("EXACT", "PREFIX", "REGEX");
    private static final Set<String> TARGET_TYPES = Set.of("TOOL", "STATIC_TEXT", "INTERACTIVE_CARD", "WORKFLOW");

    private final RuleDefinitionRepository ruleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final L1RuleRegistry ruleRegistry;
    private final L1ToolCatalog toolCatalog;
    private final L1ClusterSync clusterSync;

    public L1RuleAdminController(RuleDefinitionRepository ruleRepository,
                                 ApplicationEventPublisher eventPublisher,
                                 L1RuleRegistry ruleRegistry,
                                 L1ToolCatalog toolCatalog,
                                 L1ClusterSync clusterSync) {
        this.ruleRepository = ruleRepository;
        this.eventPublisher = eventPublisher;
        this.ruleRegistry = ruleRegistry;
        this.toolCatalog = toolCatalog;
        this.clusterSync = clusterSync;
    }

    @GetMapping
    public ResponseEntity<List<RuleResponse>> listRules() {
        List<RuleDefinitionEntity> entities = ruleRepository.findAll(Sort.by(Sort.Direction.ASC, "priority"));
        List<RuleResponse> responses = entities.stream()
                .map(RuleResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<RuleDefinitionEntity> all = ruleRepository.findAll();
        long totalCount = all.size();
        long enabledCount = all.stream().filter(e -> e.getIsEnabled() != null && e.getIsEnabled() == 1).count();
        long totalHitCount = all.stream().mapToLong(e -> e.getHitCount() != null ? e.getHitCount() : 0L).sum();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalRules", totalCount);
        body.put("enabledRules", enabledCount);
        body.put("memoryActiveRules", ruleRegistry.getActiveRules().size());
        body.put("totalHits", totalHitCount);
        body.put("snapshotStatus", ruleRegistry.isLastReloadSuccessful() ? "HEALTHY_IN_MEMORY" : "DEGRADED");
        body.put("skippedRuleCodes", ruleRegistry.getSkippedRuleCodes());
        if (ruleRegistry.getLastReloadError() != null) {
            body.put("lastReloadError", ruleRegistry.getLastReloadError());
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<?> createRule(@RequestBody RuleCreateRequest req) {
        if (req.getRuleCode() == null || req.getRuleCode().trim().isEmpty()) {
            return badRequest("ruleCode 不能为空");
        }
        if (req.getRuleName() == null || req.getRuleName().trim().isEmpty()) {
            return badRequest("ruleName 不能为空");
        }
        if (req.getPatternExpr() == null || req.getPatternExpr().trim().isEmpty()) {
            return badRequest("patternExpr 不能为空");
        }
        if (req.getTargetRef() == null || req.getTargetRef().trim().isEmpty()) {
            return badRequest("targetRef 不能为空");
        }
        if (ruleRepository.findByRuleCode(req.getRuleCode().trim()).isPresent()) {
            return badRequest("ruleCode 已存在: " + req.getRuleCode().trim());
        }

        String matchType = normalize(req.getMatchType(), "REGEX");
        String targetType = normalize(req.getTargetType(), "TOOL");
        String validationError = validateRule(matchType, req.getPatternExpr(), targetType, req.getTargetRef());
        if (validationError != null) {
            return badRequest(validationError);
        }

        RuleDefinitionEntity entity = new RuleDefinitionEntity();
        entity.setRuleCode(req.getRuleCode().trim());
        entity.setRuleName(req.getRuleName().trim());
        entity.setMatchType(matchType);
        entity.setPatternExpr(req.getPatternExpr());
        entity.setTargetType(targetType);
        entity.setTargetRef(req.getTargetRef().trim());
        entity.setParamTemplate(req.getParamTemplate());
        entity.setPriority(req.getPriority() != null ? req.getPriority() : 100);
        entity.setIsEnabled(req.getIsEnabled() != null ? req.getIsEnabled() : 1);
        entity.setHitCount(0L);
        entity.setDescription(req.getDescription());

        RuleDefinitionEntity saved = ruleRepository.save(entity);
        log.info("[Admin] 新增规则成功: ruleCode={}, id={}", saved.getRuleCode(), saved.getId());
        eventPublisher.publishEvent(new L1RuleReloadEvent(this, "新增规则: " + saved.getRuleCode()));

        RuleResponse body = RuleResponse.fromEntity(saved);
        body.setClusterSync(publishCluster("新增规则: " + saved.getRuleCode()).getStatus());
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateRule(@PathVariable Long id, @RequestBody RuleCreateRequest req) {
        Optional<RuleDefinitionEntity> opt = ruleRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RuleDefinitionEntity entity = opt.get();
        String matchType = req.getMatchType() != null ? normalize(req.getMatchType(), entity.getMatchType()) : entity.getMatchType();
        String patternExpr = req.getPatternExpr() != null ? req.getPatternExpr() : entity.getPatternExpr();
        String targetType = req.getTargetType() != null ? normalize(req.getTargetType(), entity.getTargetType()) : entity.getTargetType();
        String targetRef = req.getTargetRef() != null ? req.getTargetRef() : entity.getTargetRef();
        String validationError = validateRule(matchType, patternExpr, targetType, targetRef);
        if (validationError != null) {
            return badRequest(validationError);
        }

        if (req.getRuleName() != null) entity.setRuleName(req.getRuleName());
        entity.setMatchType(matchType);
        entity.setPatternExpr(patternExpr);
        entity.setTargetType(targetType);
        entity.setTargetRef(targetRef);
        if (req.getParamTemplate() != null) entity.setParamTemplate(req.getParamTemplate());
        if (req.getPriority() != null) entity.setPriority(req.getPriority());
        if (req.getIsEnabled() != null) entity.setIsEnabled(req.getIsEnabled());
        if (req.getDescription() != null) entity.setDescription(req.getDescription());

        RuleDefinitionEntity saved = ruleRepository.save(entity);
        log.info("[Admin] 规则修改成功: ruleCode={}, id={}", saved.getRuleCode(), saved.getId());
        eventPublisher.publishEvent(new L1RuleReloadEvent(this, "更新规则: " + saved.getRuleCode()));

        RuleResponse body = RuleResponse.fromEntity(saved);
        body.setClusterSync(publishCluster("更新规则: " + saved.getRuleCode()).getStatus());
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/toggle")
    public ResponseEntity<?> toggleRuleStatus(@PathVariable Long id) {
        Optional<RuleDefinitionEntity> opt = ruleRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RuleDefinitionEntity entity = opt.get();
        int current = entity.getIsEnabled() == null ? 0 : entity.getIsEnabled();
        entity.setIsEnabled(current == 1 ? 0 : 1);
        RuleDefinitionEntity saved = ruleRepository.save(entity);

        log.info("[Admin] 规则状态已变更: id={}, ruleCode={}, isEnabled={}", saved.getId(), saved.getRuleCode(), saved.getIsEnabled());
        eventPublisher.publishEvent(new L1RuleReloadEvent(this, "切换状态 ID=" + id + " to " + saved.getIsEnabled()));

        RuleResponse body = RuleResponse.fromEntity(saved);
        body.setClusterSync(publishCluster("切换规则状态: " + saved.getRuleCode()).getStatus());
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRule(@PathVariable Long id) {
        Optional<RuleDefinitionEntity> opt = ruleRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String ruleCode = opt.get().getRuleCode();
        ruleRepository.deleteById(id);
        log.info("[Admin] 规则已删除: id={}, ruleCode={}", id, ruleCode);
        eventPublisher.publishEvent(new L1RuleReloadEvent(this, "删除规则: " + ruleCode));

        ClusterSyncResult sync = publishCluster("删除规则: " + ruleCode);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "规则已成功删除: " + ruleCode);
        body.put("clusterSync", sync.getStatus());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> manualReload() {
        log.info("[Admin] 管理员手动请求 L1 规则快照热重载");
        eventPublisher.publishEvent(new L1RuleReloadEvent(this, "管理员后台手动请求刷新"));
        ClusterSyncResult sync = publishCluster("管理员手动刷新");

        boolean complete = ruleRegistry.isLastReloadSuccessful();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", complete);
        body.put("activeRulesCount", ruleRegistry.getActiveRules().size());
        body.put("skippedRuleCodes", ruleRegistry.getSkippedRuleCodes());
        body.put("clusterSync", sync.getStatus());
        if (ruleRegistry.getLastReloadError() != null) {
            body.put("message", ruleRegistry.getLastReloadError());
        } else if (!complete) {
            body.put("message", "重载完成，但有规则被跳过");
        } else {
            body.put("message", "内存快照已更新");
        }
        return ResponseEntity.ok(body);
    }

    private ClusterSyncResult publishCluster(String reason) {
        try {
            return clusterSync.publishRevision(reason);
        } catch (Exception ex) {
            log.error("[Admin] 集群版本号广播异常，本机规则已生效: {}", ex.getMessage(), ex);
            return ClusterSyncResult.failed(ex.getMessage());
        }
    }

    private String validateRule(String matchType, String patternExpr, String targetType, String targetRef) {
        if (matchType == null || !MATCH_TYPES.contains(matchType)) {
            return "matchType 不合法，允许 EXACT、PREFIX、REGEX";
        }
        if (targetType == null || !TARGET_TYPES.contains(targetType)) {
            return "targetType 不合法，允许 TOOL、STATIC_TEXT、INTERACTIVE_CARD、WORKFLOW";
        }
        if ("REGEX".equals(matchType)) {
            if (patternExpr == null || patternExpr.isBlank()) {
                return "REGEX 类型必须提供 patternExpr";
            }
            try {
                Pattern.compile(patternExpr, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException ex) {
                return "正则表达式不合法: " + ex.getDescription();
            }
        }
        if ("TOOL".equals(targetType)) {
            if (targetRef == null || targetRef.isBlank()) {
                return "TOOL 类型必须提供 targetRef";
            }
            if (!toolCatalog.isRegistered(targetRef.trim())) {
                return "targetRef 不是已注册的 @Tool 方法: " + targetRef.trim();
            }
        }
        return null;
    }

    private String normalize(String raw, String defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return raw.trim().toUpperCase();
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
