package com.example.springai.admin.controller;

import com.example.springai.admin.dto.RuleCreateRequest;
import com.example.springai.admin.dto.RuleResponse;
import com.example.springai.pipeline.entity.RuleDefinitionEntity;
import com.example.springai.pipeline.event.L1RuleReloadEvent;
import com.example.springai.pipeline.repository.RuleDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * L1 规则管理后台 REST API 控制器
 * 核心设计:
 * 1. 负责规则的增删改查元数据落库 MySQL；
 * 2. 数据落库后自动发布 L1RuleReloadEvent 事件，实现数据面运行态零停机热重载。
 */
@RestController
@RequestMapping("/api/v1/admin/rules")
public class L1RuleAdminController {

    private static final Logger log = LoggerFactory.getLogger(L1RuleAdminController.class);

    private final RuleDefinitionRepository ruleRepository;
    private final ApplicationEventPublisher eventPublisher;

    public L1RuleAdminController(RuleDefinitionRepository ruleRepository,
                                ApplicationEventPublisher eventPublisher) {
        this.ruleRepository = ruleRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 查询规则全量列表 (按优先级升序)
     */
    @GetMapping
    public ResponseEntity<List<RuleResponse>> listRules() {
        List<RuleDefinitionEntity> entities = ruleRepository.findAll(Sort.by(Sort.Direction.ASC, "priority"));
        List<RuleResponse> responses = entities.stream()
                .map(RuleResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * 新增规则并自动触发热重载
     */
    @PostMapping
    public ResponseEntity<?> createRule(@RequestBody RuleCreateRequest req) {
        if (req.getRuleCode() == null || req.getRuleCode().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ruleCode 不能为空"));
        }
        if (ruleRepository.findByRuleCode(req.getRuleCode()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ruleCode 已存在: " + req.getRuleCode()));
        }

        RuleDefinitionEntity entity = new RuleDefinitionEntity();
        entity.setRuleCode(req.getRuleCode().trim());
        entity.setRuleName(req.getRuleName());
        entity.setMatchType(req.getMatchType() != null ? req.getMatchType().toUpperCase() : "REGEX");
        entity.setPatternExpr(req.getPatternExpr());
        entity.setTargetType(req.getTargetType() != null ? req.getTargetType().toUpperCase() : "TOOL");
        entity.setTargetRef(req.getTargetRef());
        entity.setParamTemplate(req.getParamTemplate());
        entity.setPriority(req.getPriority() != null ? req.getPriority() : 100);
        entity.setIsEnabled(req.getIsEnabled() != null ? req.getIsEnabled() : 1);
        entity.setHitCount(0L);
        entity.setDescription(req.getDescription());

        RuleDefinitionEntity saved = ruleRepository.save(entity);
        log.info("[Admin] 新增规则成功: ruleCode={}, id={}", saved.getRuleCode(), saved.getId());

        // 发布热更新事件，驱动内存快照原子更新
        eventPublisher.publishEvent(new L1RuleReloadEvent(this, "新增规则: " + saved.getRuleCode()));

        return ResponseEntity.ok(RuleResponse.fromEntity(saved));
    }

    /**
     * 一键切换启用/禁用状态
     */
    @PutMapping("/{id}/toggle")
    public ResponseEntity<?> toggleRuleStatus(@PathVariable Long id) {
        Optional<RuleDefinitionEntity> opt = ruleRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RuleDefinitionEntity entity = opt.get();
        entity.setIsEnabled(entity.getIsEnabled() == 1 ? 0 : 1);
        RuleDefinitionEntity saved = ruleRepository.save(entity);

        log.info("[Admin] 规则状态已变更: id={}, ruleCode={}, isEnabled={}", saved.getId(), saved.getRuleCode(), saved.getIsEnabled());

        // 发布热重载事件
        eventPublisher.publishEvent(new L1RuleReloadEvent(this, "切换状态 ID=" + id + " to " + saved.getIsEnabled()));

        return ResponseEntity.ok(RuleResponse.fromEntity(saved));
    }

    /**
     * 手动触发热重载
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> manualReload() {
        log.info("[Admin] 管理员手动请求 L1 规则快照热重载");
        eventPublisher.publishEvent(new L1RuleReloadEvent(this, "管理员后台手动请求刷新"));
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "已发布热重载事件，内存快照已无锁原子替换"
        ));
    }
}
