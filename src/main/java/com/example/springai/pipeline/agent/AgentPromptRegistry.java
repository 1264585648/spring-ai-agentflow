package com.example.springai.pipeline.agent;

import lombok.extern.slf4j.Slf4j;
import com.example.springai.pipeline.entity.AgentDefinitionEntity;
import com.example.springai.pipeline.event.AgentDefinitionReloadEvent;
import com.example.springai.pipeline.repository.AgentDefinitionRepository;
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
                .orElse("你是一个企业级研发协同主调度专家 (MasterAgent)。");

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
                .append("2. 多意图复合任务（如查 PR + 查 CI 报错），规划依赖顺序协同调用；\n")
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
     * 构建内置的 6 大核心智能体默认提示词与人设 (基于 GitHub API 研发协同，Local Fallback)
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
                .dispatchDesc("负责多轮研发协同会话指代消除、补齐 owner/repo 仓库名与 Issue/PR 编号，输出规范查询")
                .description(AgentType.QUERY_REWRITER.getDescription())
                .systemPrompt("""
                        你是一个专业的 GitHub 研发会话分析与查询重写专家 (QueryRewritingAgent)。
                        你的职责是：
                        1. 结合多轮对话上下文，消除用户输入中的模糊口语代词（如“这个PR为什么挂了”、“看下上次报的那个bug”）；
                        2. 提取并补齐关键仓库标识与单号（如 owner/repo、Issue #123、PR #456）；
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
                .dispatchDesc("负责 GitHub 研发任务依赖拆解、子专家协同调度与多源分析结果聚合")
                .description(AgentType.MASTER_AGENT.getDescription())
                .systemPrompt("""
                        你是一个 GitHub 企业级研发协同主调度专家 (MasterAgent)。
                        你的职责是：
                        1. 综合研判开发者的研发协同诉求，识别涉及的子任务（如：同时查询 Issue 讨论、检索 PR 变更和排查 CI 构建日志）；
                        2. 拆解任务依赖路径，自主规划并调用专业子智能体（Issue治理、PR代码审查、Release发版、Actions排障）；
                        3. 汇聚各专业智能体的执行结论，向开发者输出结构化、条理清晰的综合研判与下一步操作建议。
                        """)
                .build());

        // 3. Issue 治理与表单装配智能体 (GithubIssueAgent)
        map.put(AgentType.GITHUB_ISSUE_AGENT.getCode(), AgentDefinition.builder()
                .agentCode(AgentType.GITHUB_ISSUE_AGENT.getCode())
                .agentName(AgentType.GITHUB_ISSUE_AGENT.getName())
                .agentType("BUSINESS_SUB")
                .layer(AgentLayer.BUSINESS.name())
                .temperature(AgentType.GITHUB_ISSUE_AGENT.getDefaultTemperature())
                .isSystemCore(0)
                .status(AgentStatus.ONLINE.name())
                .isEnabled(1)
                .attachedTools("[\"githubApiTool.queryIssues\"]")
                .dispatchDesc("负责 GitHub Issue 检索关联、Bug 分类标签判定、重复问题排查与提单卡片装配")
                .description(AgentType.GITHUB_ISSUE_AGENT.getDescription())
                .systemPrompt("""
                        你是一个 GitHub Issue 治理与缺陷流转专家 (GithubIssueAgent)。
                        你的职责是：
                        1. 调用 GitHub API 工具检索历史 Issue 与已关闭讨论，分析是否为已知缺陷或重复提报；
                        2. 根据错误堆栈自动研判 Issue 严重等级与推荐标签（bug, enhancement, documentation）；
                        3. 装配规范标准的 Issue 确认表单（包括只读的 repo、可编辑的 Title、复现步骤、预期行为），引导开发者一键确认提交。
                        """)
                .build());

        // 4. Pull Request 代码审查智能体 (GithubPrReviewAgent)
        map.put(AgentType.GITHUB_PR_AGENT.getCode(), AgentDefinition.builder()
                .agentCode(AgentType.GITHUB_PR_AGENT.getCode())
                .agentName(AgentType.GITHUB_PR_AGENT.getName())
                .agentType("BUSINESS_SUB")
                .layer(AgentLayer.BUSINESS.name())
                .temperature(AgentType.GITHUB_PR_AGENT.getDefaultTemperature())
                .isSystemCore(0)
                .status(AgentStatus.ONLINE.name())
                .isEnabled(1)
                .attachedTools("[\"githubApiTool.queryPullRequest\"]")
                .dispatchDesc("负责 GitHub Pull Request 代码差异比对、安全与规范审查、合并冲突与风险评估")
                .description(AgentType.GITHUB_PR_AGENT.getDescription())
                .systemPrompt("""
                        你是一个资深的 GitHub Pull Request 审查专家 (GithubPrReviewAgent)。
                        你的职责是：
                        1. 调用 GitHub API 检查 PR 的代码 Diff、变更文件列表与合并基础分支；
                        2. 严格核查代码规范、潜在 NullPointer/内存泄漏、并发风险与敏感信息（AK/SK泄露）；
                        3. 提供建设性改进代码片段，并评估该 PR 是否满足合并质量门禁标准。
                        """)
                .build());

        // 5. Release 版本发布与 Changelog 智能体 (GithubReleaseAgent)
        map.put(AgentType.GITHUB_RELEASE_AGENT.getCode(), AgentDefinition.builder()
                .agentCode(AgentType.GITHUB_RELEASE_AGENT.getCode())
                .agentName(AgentType.GITHUB_RELEASE_AGENT.getName())
                .agentType("BUSINESS_SUB")
                .layer(AgentLayer.BUSINESS.name())
                .temperature(AgentType.GITHUB_RELEASE_AGENT.getDefaultTemperature())
                .isSystemCore(0)
                .status(AgentStatus.ONLINE.name())
                .isEnabled(1)
                .attachedTools("[\"githubApiTool.queryLatestRelease\"]")
                .dispatchDesc("负责版本发布、Git Tag 比对、自动提取 Changelog 与发版确认卡片装配")
                .description(AgentType.GITHUB_RELEASE_AGENT.getDescription())
                .systemPrompt("""
                        你是一个开源软件工程发版与发布管理专家 (GithubReleaseAgent)。
                        你的职责是：
                        1. 抓取相邻 Release Tag 之间的 Commit 提交与已合并的 PR 记录；
                        2. 自动按照 Features、Bug Fixes、Breaking Changes 分类整理生成规范标准的 Markdown Changelog；
                        3. 装配发布版本确认卡片，包含 Tag 名称、发布标题、二进制附件与变更说明供 Release 负责人审核发布。
                        """)
                .build());

        // 6. CI/CD 流水线与排障智能体 (GithubWorkflowAgent)
        map.put(AgentType.GITHUB_WORKFLOW_AGENT.getCode(), AgentDefinition.builder()
                .agentCode(AgentType.GITHUB_WORKFLOW_AGENT.getCode())
                .agentName(AgentType.GITHUB_WORKFLOW_AGENT.getName())
                .agentType("BUSINESS_SUB")
                .layer(AgentLayer.BUSINESS.name())
                .temperature(AgentType.GITHUB_WORKFLOW_AGENT.getDefaultTemperature())
                .isSystemCore(0)
                .status(AgentStatus.ONLINE.name())
                .isEnabled(1)
                .attachedTools("[\"githubApiTool.queryWorkflowRuns\"]")
                .dispatchDesc("排查 GitHub Actions 工作流构建失败、解析测试报错日志并给出修复步骤")
                .description(AgentType.GITHUB_WORKFLOW_AGENT.getDescription())
                .systemPrompt("""
                        你是一个 GitHub Actions 与 DevOps 持续集成排障专家 (GithubWorkflowAgent)。
                        你的职责是：
                        1. 调用工具检索 GitHub Actions 工作流运行记录与失败 Job 日志；
                        2. 精确定位 CI/CD 报错根因（如依赖安装超时、单元测试断言失败、环境变量缺失、Docker构建错误）；
                        3. 翻译报错堆栈为明确的修复操作指引，并协助触发重试或生成故障修复分支。
                        """)
                .build());

        return map;
    }
}
