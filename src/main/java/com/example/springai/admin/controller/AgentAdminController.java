package com.example.springai.admin.controller;

import com.example.springai.admin.dto.AgentCreateRequest;
import com.example.springai.admin.dto.AgentResponse;
import com.example.springai.admin.dto.AgentUpdateRequest;
import com.example.springai.pipeline.entity.AgentDefinitionEntity;
import com.example.springai.pipeline.entity.RuleDefinitionEntity;
import com.example.springai.pipeline.event.AgentDefinitionReloadEvent;
import com.example.springai.pipeline.repository.AgentDefinitionRepository;
import com.example.springai.pipeline.repository.RuleDefinitionRepository;
import com.example.springai.pipeline.sync.ClusterSyncResult;
import com.example.springai.pipeline.sync.L1ClusterSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能体元数据与生命周期管理后台 REST API (AgentAdminController)
 * 严格遵循工程规范 RULE-01 与 RULE-06:
 * 1. 骨架核心智能体 (is_system_core=1) 锁定保护，严禁下线或修改结构；
 * 2. 业务专家智能体支持动态新增、提示词热更新与参数微调；
 * 3. 严禁物理删除 (DELETE)，推行生命周期状态机与前置规则依赖审计；
 * 4. 修改后发布 Spring 事件驱动内存零停机热重载，并可选广播版本号至集群。
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1/admin/agents")
public class AgentAdminController {

    private static final Logger log = LoggerFactory.getLogger(AgentAdminController.class);

    private static final Set<String> VALID_STATUSES = Set.of("DRAFT", "ONLINE", "DEPRECATED", "OFFLINE");
    private static final Set<String> VALID_LAYERS = Set.of("ANALYSIS", "ORCHESTRATION", "BUSINESS", "DATA_FLYWHEEL");

    private final AgentDefinitionRepository agentRepository;
    private final RuleDefinitionRepository ruleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final L1ClusterSync clusterSync;

    public AgentAdminController(AgentDefinitionRepository agentRepository,
                                RuleDefinitionRepository ruleRepository,
                                ApplicationEventPublisher eventPublisher,
                                @Autowired(required = false) L1ClusterSync clusterSync) {
        this.agentRepository = agentRepository;
        this.ruleRepository = ruleRepository;
        this.eventPublisher = eventPublisher;
        this.clusterSync = clusterSync;
    }

    /**
     * 查询所有智能体列表 (系统核心优先，ID正序)
     */
    @GetMapping
    public ResponseEntity<List<AgentResponse>> listAgents() {
        List<AgentDefinitionEntity> entities = agentRepository.findAllByOrderByIsSystemCoreDescIdAsc();
        List<AgentResponse> responses = entities.stream()
                .map(AgentResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * 根据编码获取智能体详情
     */
    @GetMapping("/{agentCode}")
    public ResponseEntity<?> getAgent(@PathVariable String agentCode) {
        Optional<AgentDefinitionEntity> opt = agentRepository.findByAgentCode(agentCode.trim());
        if (opt.isEmpty()) {
            return notFound("未找到编码为 [" + agentCode + "] 的智能体");
        }
        return ResponseEntity.ok(AgentResponse.fromEntity(opt.get()));
    }

    /**
     * 动态新增业务领域 Sub-Agent
     */
    @PostMapping
    public ResponseEntity<?> createAgent(@RequestBody AgentCreateRequest req) {
        if (req.getAgentCode() == null || req.getAgentCode().trim().isEmpty()) {
            return badRequest("agentCode 不能为空");
        }
        String cleanCode = req.getAgentCode().trim().toUpperCase(Locale.ROOT);
        if (!cleanCode.matches("^[A-Z0-9_]{3,64}$")) {
            return badRequest("agentCode 必须由 3~64 位大写字母、数字或下划线组成");
        }
        if (agentRepository.existsByAgentCode(cleanCode)) {
            return badRequest("agentCode 已存在: " + cleanCode);
        }
        if (req.getAgentName() == null || req.getAgentName().trim().isEmpty()) {
            return badRequest("agentName 不能为空");
        }
        if (req.getSystemPrompt() == null || req.getSystemPrompt().trim().isEmpty()) {
            return badRequest("systemPrompt 提示词不能为空");
        }
        if (req.getDispatchDesc() == null || req.getDispatchDesc().trim().isEmpty()) {
            return badRequest("dispatchDesc 调度意图描述不能为空（供 MasterAgent 路由判定使用）");
        }

        String layer = req.getLayer() != null ? req.getLayer().trim().toUpperCase(Locale.ROOT) : "BUSINESS";
        if (!VALID_LAYERS.contains(layer)) {
            return badRequest("不支持的 layer 分层: " + layer + "，合法值为: " + VALID_LAYERS);
        }

        Double temp = req.getTemperature() != null ? req.getTemperature() : 0.30;
        if (temp < 0.0 || temp > 1.0) {
            return badRequest("temperature 采样温度必须在 0.00 ~ 1.00 之间");
        }

        AgentDefinitionEntity entity = AgentDefinitionEntity.builder()
                .agentCode(cleanCode)
                .agentName(req.getAgentName().trim())
                .agentType("BUSINESS_SUB")
                .layer(layer)
                .systemPrompt(req.getSystemPrompt().trim())
                .dispatchDesc(req.getDispatchDesc().trim())
                .modelName(req.getModelName() != null && !req.getModelName().trim().isEmpty() ? req.getModelName().trim() : null)
                .temperature(temp)
                .attachedTools(req.getAttachedTools())
                .isSystemCore(0)
                .status("ONLINE")
                .isEnabled(1)
                .version(1)
                .description(req.getDescription())
                .build();

        AgentDefinitionEntity saved = agentRepository.save(entity);
        log.info("[AgentAdmin] ⚡ 动态新增业务智能体成功: code={}, id={}", saved.getAgentCode(), saved.getId());

        // 驱动本机快照热重载
        eventPublisher.publishEvent(new AgentDefinitionReloadEvent(this, "新增业务智能体: " + saved.getAgentCode()));

        AgentResponse resp = AgentResponse.fromEntity(saved);
        resp.setClusterSync(publishClusterSync("新增业务智能体: " + saved.getAgentCode()).getStatus());
        return ResponseEntity.ok(resp);
    }

    /**
     * 更新智能体人设提示词、调度描述与运行参数
     */
    @PutMapping("/{agentCode}")
    public ResponseEntity<?> updateAgent(@PathVariable String agentCode, @RequestBody AgentUpdateRequest req) {
        Optional<AgentDefinitionEntity> opt = agentRepository.findByAgentCode(agentCode.trim());
        if (opt.isEmpty()) {
            return notFound("未找到编码为 [" + agentCode + "] 的智能体");
        }

        AgentDefinitionEntity entity = opt.get();
        if (req.getAgentName() != null && !req.getAgentName().trim().isEmpty()) {
            entity.setAgentName(req.getAgentName().trim());
        }
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().trim().isEmpty()) {
            entity.setSystemPrompt(req.getSystemPrompt().trim());
        }
        if (req.getDispatchDesc() != null && !req.getDispatchDesc().trim().isEmpty()) {
            entity.setDispatchDesc(req.getDispatchDesc().trim());
        }
        if (req.getModelName() != null) {
            entity.setModelName(req.getModelName().trim().isEmpty() ? null : req.getModelName().trim());
        }
        if (req.getTemperature() != null) {
            if (req.getTemperature() < 0.0 || req.getTemperature() > 1.0) {
                return badRequest("temperature 采样温度必须在 0.00 ~ 1.00 之间");
            }
            entity.setTemperature(req.getTemperature());
        }
        if (req.getAttachedTools() != null) {
            entity.setAttachedTools(req.getAttachedTools());
        }
        if (req.getDescription() != null) {
            entity.setDescription(req.getDescription());
        }

        entity.setVersion(entity.getVersion() != null ? entity.getVersion() + 1 : 1);
        AgentDefinitionEntity saved = agentRepository.save(entity);

        log.info("[AgentAdmin] ⚡ 智能体更新成功: code={}, version={}", saved.getAgentCode(), saved.getVersion());
        eventPublisher.publishEvent(new AgentDefinitionReloadEvent(this, "更新智能体人设: " + saved.getAgentCode()));

        AgentResponse resp = AgentResponse.fromEntity(saved);
        resp.setClusterSync(publishClusterSync("更新智能体人设: " + saved.getAgentCode()).getStatus());
        return ResponseEntity.ok(resp);
    }

    /**
     * 变更智能体生命周期状态 (包含核心保护锁与依赖审计)
     */
    @PostMapping("/{agentCode}/status")
    public ResponseEntity<?> updateStatus(@PathVariable String agentCode, @RequestBody Map<String, String> body) {
        String targetStatus = body != null ? body.get("status") : null;
        if (targetStatus == null || targetStatus.trim().isEmpty()) {
            return badRequest("status 不能为空");
        }
        String cleanStatus = targetStatus.trim().toUpperCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(cleanStatus)) {
            return badRequest("不支持的 status: " + cleanStatus + "，合法值为: " + VALID_STATUSES);
        }

        Optional<AgentDefinitionEntity> opt = agentRepository.findByAgentCode(agentCode.trim());
        if (opt.isEmpty()) {
            return notFound("未找到编码为 [" + agentCode + "] 的智能体");
        }

        AgentDefinitionEntity entity = opt.get();

        // 1. 系统核心锁定保护防线
        if (entity.getIsSystemCore() != null && entity.getIsSystemCore() == 1) {
            if (!"ONLINE".equalsIgnoreCase(cleanStatus)) {
                return badRequest("违反 RULE-06 规范：系统核心骨架智能体 (is_system_core=1) 受到锁定保护，禁止下线或停用！");
            }
        }

        // 2. 下线/停用前置依赖审计防线 (当转为 DEPRECATED 或 OFFLINE 时)
        if ("DEPRECATED".equalsIgnoreCase(cleanStatus) || "OFFLINE".equalsIgnoreCase(cleanStatus)) {
            List<RuleDefinitionEntity> dependentRules = checkRuleDependencies(entity.getAgentCode());
            if (!dependentRules.isEmpty()) {
                String ruleCodes = dependentRules.stream().map(RuleDefinitionEntity::getRuleCode).collect(Collectors.joining(", "));
                return badRequest(String.format("下线阻断：检测到 L1 规则引擎存在硬依赖，规则 [%s] 正在指向该智能体，请先调整规则！", ruleCodes));
            }
        }

        entity.setStatus(cleanStatus);
        // 若为 OFFLINE 则同步禁用，其余状态保持启用
        entity.setIsEnabled("OFFLINE".equalsIgnoreCase(cleanStatus) ? 0 : 1);
        entity.setVersion(entity.getVersion() != null ? entity.getVersion() + 1 : 1);

        AgentDefinitionEntity saved = agentRepository.save(entity);
        log.info("[AgentAdmin] ⚡ 智能体状态流转成功: code={}, status={}, enabled={}",
                saved.getAgentCode(), saved.getStatus(), saved.getIsEnabled());

        eventPublisher.publishEvent(new AgentDefinitionReloadEvent(this, "智能体状态流转: " + saved.getAgentCode() + " -> " + cleanStatus));

        AgentResponse resp = AgentResponse.fromEntity(saved);
        resp.setClusterSync(publishClusterSync("智能体状态流转: " + saved.getAgentCode()).getStatus());
        return ResponseEntity.ok(resp);
    }

    /**
     * 物理删除操作拦截 (遵循 RULE-06 红线：严禁物理删除)
     */
    @DeleteMapping("/{agentCode}")
    public ResponseEntity<?> deleteAgent(@PathVariable String agentCode) {
        return badRequest("违反 RULE-06 规范：智能体严禁物理删除！请通过 POST /api/v1/admin/agents/"
                + agentCode + "/status 接口流转至 DEPRECATED 或 OFFLINE 状态进行安全软下线。");
    }

    /**
     * 检查是否有 L1 规则正在依赖该 Agent
     */
    private List<RuleDefinitionEntity> checkRuleDependencies(String agentCode) {
        if (ruleRepository == null) return Collections.emptyList();
        List<RuleDefinitionEntity> allRules = ruleRepository.findAll();
        return allRules.stream()
                .filter(r -> r.getIsEnabled() != null && r.getIsEnabled() == 1)
                .filter(r -> r.getTargetRef() != null && r.getTargetRef().contains(agentCode))
                .collect(Collectors.toList());
    }

    /**
     * 广播集群版本号
     */
    private ClusterSyncResult publishClusterSync(String reason) {
        if (clusterSync == null) {
            return ClusterSyncResult.skipped();
        }
        try {
            return clusterSync.publishRevision(reason);
        } catch (Exception ex) {
            log.warn("[AgentAdmin] 广播集群版本号异常: {}", ex.getMessage());
            return ClusterSyncResult.failed(ex.getMessage());
        }
    }

    private ResponseEntity<?> badRequest(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", 400);
        err.put("error", "Bad Request");
        err.put("message", message);
        return ResponseEntity.badRequest().body(err);
    }

    private ResponseEntity<?> notFound(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", 404);
        err.put("error", "Not Found");
        err.put("message", message);
        return ResponseEntity.status(404).body(err);
    }
}
