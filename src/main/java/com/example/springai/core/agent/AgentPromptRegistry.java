package com.example.springai.core.agent;

import lombok.extern.slf4j.Slf4j;
import com.example.springai.infra.persistence.entity.AgentDefinitionEntity;
import com.example.springai.core.event.AgentDefinitionReloadEvent;
import com.example.springai.infra.persistence.repository.AgentDefinitionRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 多智能体提示词与人设注册中心 (AgentPromptRegistry)
 * 职责:
 * 1. 维护企业级多智能体矩阵 (Multi-Agent Matrix) 的专属人设与系统提示词；
 * 2. 采用 AtomicReference 无锁内存快照设计，高并发读零阻塞 (<1μs)；
 * 3. 混合治理架构：启动时代码枚举作为基底默认值，数据库有记录时覆写（Overlay）；
 * 4. 监听 AgentDefinitionReloadEvent 事件实现零停机热重载。
 */
@Component
@Slf4j
public class AgentPromptRegistry {


    private final AgentDefinitionRepository agentRepository;

    /**
     * 内存快照原子引用：保证读写隔离与线程安全
     */
    private final AtomicReference<Map<String, AgentDefinition>> registrySnapshot =
            new AtomicReference<>(Collections.emptyMap());

    public AgentPromptRegistry(@Autowired(required = false) AgentDefinitionRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    public AgentPromptRegistry() {
        this(null);
    }

    @PostConstruct
    public void init() {
        log.info("[AgentPromptRegistry] 正在初始化多智能体专属提示词注册中心...");
        reloadFromDatabase();
        log.info("[AgentPromptRegistry] ✅ 多智能体专属提示词注册中心就绪，当前生效数量: {}", registrySnapshot.get().size());
    }

    /**
     * 监听智能体热重载事件，刷新内存快照
     */
    @EventListener(AgentDefinitionReloadEvent.class)
    public void onReloadEvent(AgentDefinitionReloadEvent event) {
        log.info("[AgentPromptRegistry] ⚡ 监听到智能体重载事件: {}", event.getReason());
        reloadFromDatabase();
    }

    /**
     * 从数据库重载快照（带本地枚举默认值兜底）
     */
    public synchronized void reloadFromDatabase() {
        // 1. 本地代码枚举默认值基底
        Map<String, AgentDefinition> snapshotMap = new HashMap<>(buildDefaultAgentDefinitions());

        // 2. 从数据库加载并覆写
        if (agentRepository != null) {
            try {
                List<AgentDefinitionEntity> entities = agentRepository.findAll();
                if (entities != null && !entities.isEmpty()) {
                    for (AgentDefinitionEntity entity : entities) {
                        if (entity.getAgentCode() != null) {
                            snapshotMap.put(entity.getAgentCode(), convertEntityToDefinition(entity));
                        }
                    }
                    log.info("[AgentPromptRegistry] 已从 MySQL 成功加载并覆写 {} 个智能体配置", entities.size());
                } else {
                    log.info("[AgentPromptRegistry] MySQL 暂无智能体定义记录，使用代码内置默认人设");
                }
            } catch (Exception ex) {
                log.warn("[AgentPromptRegistry] 从 MySQL 加载智能体配置异常，降级使用代码内置默认人设: {}", ex.getMessage());
            }
        }
        registrySnapshot.set(Collections.unmodifiableMap(snapshotMap));
    }

    /**
     * 根据智能体枚举获取智能体元数据定义
     */
    public Optional<AgentDefinition> getAgent(AgentType agentType) {
        if (agentType == null) return Optional.empty();
        return getAgent(agentType.getCode());
    }

    /**
     * 根据智能体编码获取智能体元数据定义
     */
    public Optional<AgentDefinition> getAgent(String agentCode) {
        if (agentCode == null) return Optional.empty();
        return Optional.ofNullable(registrySnapshot.get().get(agentCode));
    }

    /**
     * 根据智能体枚举获取系统提示词
     */
    public String getSystemPrompt(AgentType agentType) {
        if (agentType == null) return getSystemPrompt((String) null);
        return getSystemPrompt(agentType.getCode());
    }

    /**
     * 获取指定智能体的系统提示词 (若未配置或禁用则返回兜底提示词)
     * 特殊逻辑：针对 MASTER_AGENT，自动组装主调度基底人设与当前在线的业务子专家清单
     */
    public String getSystemPrompt(String agentCode) {
        if (AgentType.MASTER_AGENT.getCode().equalsIgnoreCase(agentCode)) {
            return buildMasterAgentPrompt();
        }
        return getAgent(agentCode)
                .filter(a -> a.getIsEnabled() != null && a.getIsEnabled() == 1)
                .filter(a -> !AgentStatus.OFFLINE.name().equalsIgnoreCase(a.getStatus()))
                .map(AgentDefinition::getSystemPrompt)
                .orElse("你是一个企业级智能协同助手，请保持客观、严谨、条理清晰的沟通风格。");
    }

    /**
     * 动态组装 MasterAgent 的完整 System Prompt
     * 将主调度基础人设与当前处于 ONLINE 状态的业务子专家矩阵动态拼接
     */
    public String buildMasterAgentPrompt() {
        // 1. 获取 MasterAgent 的基底人设（支持从数据库配置覆写）
        String basePrompt = getAgent(AgentType.MASTER_AGENT.getCode())
                .filter(a -> a.getIsEnabled() != null && a.getIsEnabled() == 1)
                .filter(a -> !AgentStatus.OFFLINE.name().equalsIgnoreCase(a.getStatus()))
                .map(AgentDefinition::getSystemPrompt)
                .orElse("你是一个企业级智能运维与故障排查主调度专家 (MasterAgent)。");

        // 2. 获取当前所有在线且启用的业务专家 (BUSINESS 层且 ONLINE)
        List<AgentDefinition> onlineExperts = getOnlineBusinessAgents();

        // 3. 动态渲染专家清单
        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt.trim()).append("\n\n");
        sb.append("【当前已挂载的可调度业务专家清单（动态热装载）】：\n");

        if (onlineExperts.isEmpty()) {
            sb.append("- （当前系统无在线业务专家，所有复合诉求将执行基础兜底）\n");
        } else {
            for (AgentDefinition expert : onlineExperts) {
                sb.append(String.format("- [%s] (%s): %s\n",
                        expert.getAgentCode(),
                        expert.getAgentName(),
                        expert.getDispatchDesc() != null ? expert.getDispatchDesc() : "无详细调度描述"));
            }
        }

        sb.append("\n【调度决策守则】：\n")
                .append("1. 识别用户输入中的真实意图，匹配最贴切的业务专家；\n")
                .append("2. 多意图复合任务（如查服务错误日志 + 分析数据库慢查），规划依赖顺序协同调用；\n")
                .append("3. 严格禁止调用上述清单以外的未挂载专家。");

        return sb.toString();
    }

    /**
     * 获取当前所有已注册的智能体定义列表
     */
    public List<AgentDefinition> getAllAgents() {
        return new ArrayList<>(registrySnapshot.get().values());
    }

    /**
     * 获取当前处于 ONLINE 状态且已启用的业务领域 Sub-Agent 清单 (供 MasterAgent 动态组装子专家清单)
     */
    public List<AgentDefinition> getOnlineBusinessAgents() {
        return registrySnapshot.get().values().stream()
                .filter(a -> AgentLayer.BUSINESS.name().equalsIgnoreCase(a.getLayer()))
                .filter(a -> AgentStatus.ONLINE.name().equalsIgnoreCase(a.getStatus()))
                .filter(a -> a.getIsEnabled() != null && a.getIsEnabled() == 1)
                .toList();
    }

    /**
     * 动态注册或更新智能体定义（支持内存热更新）
     */
    public void registerOrUpdate(AgentDefinition definition) {
        if (definition == null || definition.getAgentCode() == null) {
            throw new IllegalArgumentException("AgentDefinition and agentCode must not be null");
        }
        Map<String, AgentDefinition> newSnapshot = new ConcurrentHashMap<>(registrySnapshot.get());
        newSnapshot.put(definition.getAgentCode(), definition);
        registrySnapshot.set(Collections.unmodifiableMap(newSnapshot));
        log.info("[AgentPromptRegistry] ⚡ 智能体提示词已热更新: code={}, name={}",
                definition.getAgentCode(), definition.getAgentName());
    }

    /**
     * 全量原子替换快照
     */
    public void replaceAll(List<AgentDefinition> definitions) {
        Map<String, AgentDefinition> newSnapshot = new HashMap<>();
        if (definitions != null) {
            for (AgentDefinition def : definitions) {
                if (def.getAgentCode() != null) {
                    newSnapshot.put(def.getAgentCode(), def);
                }
            }
        }
        registrySnapshot.set(Collections.unmodifiableMap(newSnapshot));
        log.info("[AgentPromptRegistry] ⚡ 全量智能体快照原子替换完成，生效数量: {}", newSnapshot.size());
    }

    /**
     * 将实体转换为内存模型对象
     */
    private AgentDefinition convertEntityToDefinition(AgentDefinitionEntity entity) {
        return AgentDefinition.builder()
                .agentCode(entity.getAgentCode())
                .agentName(entity.getAgentName())
                .agentType(entity.getAgentType())
                .layer(entity.getLayer())
                .systemPrompt(entity.getSystemPrompt())
                .dispatchDesc(entity.getDispatchDesc())
                .modelName(entity.getModelName())
                .temperature(entity.getTemperature())
                .attachedTools(entity.getAttachedTools())
                .isSystemCore(entity.getIsSystemCore())
                .status(entity.getStatus())
                .isEnabled(entity.getIsEnabled())
                .version(entity.getVersion())
                .description(entity.getDescription())
                .build();
    }

    /**
     * 构建内置的 6 大核心智能体默认提示词与人设 (企业级智能运维与排障体系，Local Fallback)
     */
    private Map<String, AgentDefinition> buildDefaultAgentDefinitions() {
        Map<String, AgentDefinition> map = new HashMap<>();

        // 1. 查询重写智能体 (QueryRewriter)
        map.put(AgentType.QUERY_REWRITER.getCode(), AgentDefinition.builder()
                .agentCode(AgentType.QUERY_REWRITER.getCode())
                .agentName(AgentType.QUERY_REWRITER.getName())
                .agentType("PIPELINE_CORE")
                .layer(AgentLayer.ANALYSIS.name())
                .temperature(AgentType.QUERY_REWRITER.getDefaultTemperature())
                .isSystemCore(1)
                .status(AgentStatus.ONLINE.name())
                .isEnabled(1)
                .dispatchDesc("负责多轮运维协同会话指代消除、提取关键服务名、TraceID与故障单号，输出规范查询")
                .description(AgentType.QUERY_REWRITER.getDescription())
                .systemPrompt("""
                        你是一个专业的运维会话分析与查询重写专家 (QueryRewritingAgent)。
                        你的职责是：
                        1. 结合多轮对话上下文，消除用户输入中的模糊口语代词（如“这个服务为什么挂了”、“看下刚才那个慢SQL”）；
                        2. 提取并补齐关键服务标识与单号（如 order-service、TraceID、工单号）；
                        3. 将口语化诉求重写为语义清晰、实体完备的独立查询语句；
                        4. 若输入已完备，原样输出，严禁添枝加叶或无故编造。
                        注意：直接输出重写后的语句，不要包含任何多余的开场白或解释。
                        """)
                .build());

        // 2. 主调度协调智能体 (MasterAgent)
        map.put(AgentType.MASTER_AGENT.getCode(), AgentDefinition.builder()
                .agentCode(AgentType.MASTER_AGENT.getCode())
                .agentName(AgentType.MASTER_AGENT.getName())
                .agentType("PIPELINE_CORE")
                .layer(AgentLayer.ORCHESTRATION.name())
                .temperature(AgentType.MASTER_AGENT.getDefaultTemperature())
                .isSystemCore(1)
                .status(AgentStatus.ONLINE.name())
                .isEnabled(1)
                .dispatchDesc("负责复杂任务与故障依赖拆解、子专家协同调度与多源分析结果聚合")
                .description(AgentType.MASTER_AGENT.getDescription())
                .systemPrompt("""
                        你是一个企业级智能运维与故障排查主调度专家 (MasterAgent)。
                        你的职责是：
                        1. 综合研判工程师的故障排查诉求，识别涉及的子任务（如：日志异常检索、数据库慢查与连接池分析、止血方案制定）；
                        2. 拆解任务依赖路径，自主规划并调用专业子智能体（日志专家、数据库专家、SRE协同专家）；
                        3. 汇聚各专业智能体的执行结论，向工程师输出结构化、条理清晰的综合研判与下一步操作建议。
                        """)
                .build());

        // 3. 通用对话与协同智能体 (GeneralAgent)
        map.put(AgentType.GENERAL_AGENT.getCode(), AgentDefinition.builder()
                .agentCode(AgentType.GENERAL_AGENT.getCode())
                .agentName(AgentType.GENERAL_AGENT.getName())
                .agentType("PIPELINE_CORE")
                .layer(AgentLayer.BUSINESS.name())
                .temperature(AgentType.GENERAL_AGENT.getDefaultTemperature())
                .isSystemCore(1)
                .status(AgentStatus.ONLINE.name())
                .isEnabled(1)
                .dispatchDesc("负责通用技术问答、框架功能说明与未命中特定专家时的兜底解答")
                .description(AgentType.GENERAL_AGENT.getDescription())
                .systemPrompt("""
                        你是一个企业级智能协同与技术答疑专家 (GeneralAgent)。
                        请针对工程师的技术疑问提供专业、严谨、条理清晰的解答；若遇到特定故障场景，引导工程师使用规范指令或提供服务名进行针对性排查。
                        """)
                .build());

        // 4. 日志异常分析智能体 (LogDiagnoseAgent)
        map.put(AgentType.LOG_DIAGNOSE_AGENT.getCode(), AgentDefinition.builder()
                .agentCode(AgentType.LOG_DIAGNOSE_AGENT.getCode())
                .agentName(AgentType.LOG_DIAGNOSE_AGENT.getName())
                .agentType("BUSINESS_SUB")
                .layer(AgentLayer.BUSINESS.name())
                .temperature(AgentType.LOG_DIAGNOSE_AGENT.getDefaultTemperature())
                .isSystemCore(0)
                .status(AgentStatus.ONLINE.name())
                .isEnabled(1)
                .attachedTools("[]")
                .dispatchDesc("负责微服务日志检索、异常堆栈解析、Trace 分布式链路追踪与已知报错排查")
                .description(AgentType.LOG_DIAGNOSE_AGENT.getDescription())
                .systemPrompt("""
                        你是一个资深的微服务日志与分布式链路诊断专家 (LogDiagnoseAgent)。
                        你的职责是：
                        1. 检索目标服务指定时间段内的 Error/Warn 日志，提取核心报错堆栈与异常根因（如 NPE, Timeout, OOM, 连接超时）；
                        2. 结合 TraceID 进行全链路拓扑追踪，定位最深层报错源头服务；
                        3. 对比历史故障知识库，输出清晰的报错原因与代码层修复排查建议。
                        """)
                .build());

        // 5. 数据库诊断智能体 (DbDiagnoseAgent)
        map.put(AgentType.DB_DIAGNOSE_AGENT.getCode(), AgentDefinition.builder()
                .agentCode(AgentType.DB_DIAGNOSE_AGENT.getCode())
                .agentName(AgentType.DB_DIAGNOSE_AGENT.getName())
                .agentType("BUSINESS_SUB")
                .layer(AgentLayer.BUSINESS.name())
                .temperature(AgentType.DB_DIAGNOSE_AGENT.getDefaultTemperature())
                .isSystemCore(0)
                .status(AgentStatus.ONLINE.name())
                .isEnabled(1)
                .attachedTools("[]")
                .dispatchDesc("负责数据库慢SQL检索、死锁与长事务分析、连接池水位诊断与性能调优建议")
                .description(AgentType.DB_DIAGNOSE_AGENT.getDescription())
                .systemPrompt("""
                        你是一个企业级数据库与高并发性能调优专家 (DbDiagnoseAgent)。
                        你的职责是：
                        1. 诊断数据库慢查询日志，提取长耗时 SQL 语句并分析缺少索引、大表全表扫描等性能瓶颈；
                        2. 监测当前活跃连接、长事务占用与锁等待情况，排查死锁与连接池打满问题；
                        3. 输出 Explain 执行计划分析结论与安全的索引调优建议。
                        """)
                .build());

        // 6. 应急止血与运维协同智能体 (SreCopilotAgent)
        map.put(AgentType.SRE_COPILOT_AGENT.getCode(), AgentDefinition.builder()
                .agentCode(AgentType.SRE_COPILOT_AGENT.getCode())
                .agentName(AgentType.SRE_COPILOT_AGENT.getName())
                .agentType("BUSINESS_SUB")
                .layer(AgentLayer.BUSINESS.name())
                .temperature(AgentType.SRE_COPILOT_AGENT.getDefaultTemperature())
                .isSystemCore(0)
                .status(AgentStatus.ONLINE.name())
                .isEnabled(1)
                .attachedTools("[]")
                .dispatchDesc("负责故障综合研判、制定应急止血处置方案、装配确认卡片并引导工程师核验执行")
                .description(AgentType.SRE_COPILOT_AGENT.getDescription())
                .systemPrompt("""
                        你是一个 SRE 网站可靠性与应急处置专家 (SreCopilotAgent)。
                        你的职责是：
                        1. 汇聚日志与数据库的诊断结论，评估系统当前受影响程度与故障级别；
                        2. 制定高可行的应急止血方案（如临时调大连接池、Kill 阻塞慢查、流量熔断降级或服务滚动重启）；
                        3. 遵循 Human-in-the-loop 安全红线，严禁直接自动执行高危写操作，必须装配标准化应急处置卡片交由工程师二次核验后一键执行。
                        """)
                .build());

        return map;
    }
}
