-- =============================================================================
-- Multi-Agent 微内核骨架 - L1 规则引擎企业级数据表结构初始化脚本
-- 适用数据库：MySQL 8.0+
-- 默认字符集：utf8mb4 (utf8mb4_unicode_ci)
-- 放置路径：sql/init_schema.sql
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 1. 规则定义主表 (sys_rule_definition)
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_rule_definition`;
CREATE TABLE `sys_rule_definition` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `rule_code` VARCHAR(64) NOT NULL COMMENT '规则唯一编码（如 CMD_QUERY_ACCOUNT）',
  `rule_name` VARCHAR(128) NOT NULL COMMENT '规则友好名称',
  `match_type` VARCHAR(32) NOT NULL DEFAULT 'REGEX' COMMENT '匹配类型：PREFIX(前缀), EXACT(完全匹配), REGEX(正则匹配)',
  `pattern_expr` VARCHAR(255) NOT NULL COMMENT '匹配模式表达式',
  `target_type` VARCHAR(32) NOT NULL DEFAULT 'TOOL' COMMENT '执行目标类型：TOOL(调用工具), STATIC_TEXT(静态文本), INTERACTIVE_CARD(交互卡片), WORKFLOW(工作流)',
  `target_ref` VARCHAR(255) NOT NULL COMMENT '执行目标引用（如 userAccountTool.queryBalance 或卡片模板ID）',
  `param_template` VARCHAR(1024) DEFAULT NULL COMMENT '参数提取与映射模板（JSON格式，支持 $1, $2 正则分组注入）',
  `priority` INT NOT NULL DEFAULT 100 COMMENT '匹配优先级（数字越小越优先）',
  `is_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '启停状态：1-启用，0-禁用',
  `hit_count` BIGINT NOT NULL DEFAULT 0 COMMENT '累计命中次数统计',
  `description` VARCHAR(512) DEFAULT NULL COMMENT '业务规则描述与用途说明',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_code` (`rule_code`),
  KEY `idx_enabled_priority` (`is_enabled`, `priority`) COMMENT '供系统启动与热重载时极速拉取生效规则'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='L1规则引擎定义主表';

-- -----------------------------------------------------------------------------
-- 2. 规则链式编排步骤表 (sys_rule_chain_step) - 针对模式 A 确定性工作流
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_rule_chain_step`;
CREATE TABLE `sys_rule_chain_step` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '步骤主键ID',
  `rule_id` BIGINT NOT NULL COMMENT '关联主规则ID',
  `step_order` INT NOT NULL DEFAULT 1 COMMENT '执行步骤顺序（从1递增）',
  `step_name` VARCHAR(128) NOT NULL COMMENT '步骤名称',
  `target_tool` VARCHAR(128) NOT NULL COMMENT '本步骤调用的 Tool Bean及方法（如 paymentTool.queryLatestBills）',
  `param_mapping` VARCHAR(1024) DEFAULT NULL COMMENT '参数映射（JSON，支持取上一节点结果如 #prev.accountId）',
  `failure_strategy` VARCHAR(32) NOT NULL DEFAULT 'ABORT' COMMENT '失败处理策略：ABORT(中断), CONTINUE(忽略继续), FALLBACK(降级转大模型)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_rule_id_step` (`rule_id`, `step_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='L1工作流多步链式步骤表';

-- -----------------------------------------------------------------------------
-- 3. 规则命中审计日志表 (sys_rule_audit_log)
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_rule_audit_log`;
CREATE TABLE `sys_rule_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志主键ID',
  `rule_code` VARCHAR(64) NOT NULL COMMENT '命中的规则编码',
  `session_id` VARCHAR(64) DEFAULT NULL COMMENT '用户对话会话ID',
  `raw_input` VARCHAR(1024) NOT NULL COMMENT '用户原始输入文本',
  `extracted_params` TEXT DEFAULT NULL COMMENT '提取并格式化后的JSON参数',
  `status` VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '执行结果：SUCCESS(成功), FAILED(失败)',
  `cost_ms` INT NOT NULL DEFAULT 0 COMMENT '执行耗时(毫秒，用于时延账本)',
  `error_msg` TEXT DEFAULT NULL COMMENT '异常信息或错误堆栈',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_rule_time` (`rule_code`, `created_at`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='L1规则命中与时延审计日志表';

-- -----------------------------------------------------------------------------
-- 4. 多智能体矩阵定义与生命周期配置表 (sys_agent_definition)
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_agent_definition`;
CREATE TABLE `sys_agent_definition` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `agent_code` VARCHAR(64) NOT NULL COMMENT '智能体唯一编码（如 QUERY_REWRITER, MASTER_AGENT）',
  `agent_name` VARCHAR(128) NOT NULL COMMENT '智能体友好名称',
  `agent_type` VARCHAR(32) NOT NULL DEFAULT 'BUSINESS_SUB' COMMENT '类型：PIPELINE_CORE(骨架核心-锁定), BUSINESS_SUB(业务专家-可动态增减)',
  `layer` VARCHAR(32) NOT NULL DEFAULT 'BUSINESS' COMMENT '所属分层：ANALYSIS(分析层), ORCHESTRATION(协调调度层), BUSINESS(业务执行层), DATA_FLYWHEEL(数据闭环层)',
  `system_prompt` MEDIUMTEXT NOT NULL COMMENT '专属系统人设提示词',
  `dispatch_desc` VARCHAR(512) NOT NULL COMMENT '调度语义描述（供 MasterAgent 路由意图判定使用）',
  `model_name` VARCHAR(64) DEFAULT NULL COMMENT '指定模型底座，为空时使用系统缺省配置',
  `temperature` DECIMAL(3,2) NOT NULL DEFAULT 0.30 COMMENT '采样温度(0.00~1.00)',
  `attached_tools` VARCHAR(1024) DEFAULT NULL COMMENT '挂载工具列表(JSON数组，如 ["userAccountTool.queryBalance"])',
  `is_system_core` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否系统核心保留：1-核心锁定(禁止增删类型), 0-业务扩展',
  `status` VARCHAR(32) NOT NULL DEFAULT 'ONLINE' COMMENT '生命周期状态：DRAFT(草稿), ONLINE(在线), DEPRECATED(下线排空中), OFFLINE(已下线)',
  `is_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '启停状态：1-启用，0-禁用',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号，用于版本追溯与乐观锁',
  `description` VARCHAR(512) DEFAULT NULL COMMENT '业务职责与维护备注',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_code` (`agent_code`),
  KEY `idx_type_status` (`agent_type`, `status`),
  KEY `idx_layer_enabled` (`layer`, `is_enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业多智能体矩阵定义与生命周期配置表';

-- -----------------------------------------------------------------------------
-- 5. 开源演示与种子数据 (Seed Data)
-- -----------------------------------------------------------------------------
INSERT INTO `sys_rule_definition` 
  (`rule_code`, `rule_name`, `match_type`, `pattern_expr`, `target_type`, `target_ref`, `param_template`, `priority`, `is_enabled`, `description`)
VALUES
  (
    'CMD_HELP', 
    '系统命令帮助', 
    'PREFIX', 
    '/help', 
    'STATIC_TEXT', 
    '📌 **GitHub 协同助手快捷指令**：\n- `/repo <owner/repo>`：极速查询仓库状态、Star 与活跃度\n- `/issue <owner/repo>`：直出最新 Issue 缺陷列表\n- `/query user_id=<编号>`：快速查询用户流水与状态\n- `/help`：获取全部支持命令\n- `#ping`：极速测试系统响应与直通链路', 
    NULL, 
    1, 
    1, 
    '用户输入 /help 时极速直出指令说明，无需调用大模型或Tool'
  ),
  (
    'CMD_SYS_PING', 
    '系统链路探活', 
    'EXACT', 
    '#ping', 
    'STATIC_TEXT', 
    '🏓 **PONG!** L1 前置规则引擎运行正常，端到端时延 < 5ms。', 
    NULL, 
    2, 
    1, 
    '精确匹配 #ping 直通测试'
  ),
  (
    'CMD_QUERY_REPO', 
    'GitHub 仓库速查', 
    'REGEX', 
    '^/repo\\s+([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+)$', 
    'TOOL', 
    'githubApiTool.queryRepo', 
    '{"repo": "$1"}', 
    5, 
    1, 
    '匹配 /repo owner/repo 格式并极速调用 GitHub API 工具'
  ),
  (
    'CMD_QUERY_ACCOUNT', 
    '快速查账指令', 
    'REGEX', 
    '^/query\\s+user_id=(\\d+)$', 
    'TOOL', 
    'userAccountTool.queryBalance', 
    '{"userId": "$1"}', 
    10, 
    1, 
    '匹配 /query user_id=xxx 并正则提取参数注入 Tool 执行'
  );

-- 初始化 6 大核心多智能体矩阵定义 (基于 GitHub API 研发协同与开源运维体系)
INSERT INTO `sys_agent_definition`
  (`agent_code`, `agent_name`, `agent_type`, `layer`, `system_prompt`, `dispatch_desc`, `model_name`, `temperature`, `attached_tools`, `is_system_core`, `status`, `is_enabled`, `version`, `description`)
VALUES
  (
    'QUERY_REWRITER',
    '会话分析与查询重写智能体',
    'PIPELINE_CORE',
    'ANALYSIS',
    '你是一个专业的 GitHub 研发会话分析与查询重写专家 (QueryRewritingAgent)。\n你的职责是：\n1. 结合多轮对话上下文，消除用户输入中的模糊口语代词（如“这个PR为什么挂了”、“看下上次报的那个bug”）；\n2. 提取并补齐关键仓库标识与单号（如 owner/repo、Issue #123、PR #456）；\n3. 将口语化诉求重写为语义清晰、实体完备的独立查询语句；\n4. 若输入已完备，原样输出，严禁添枝加叶或无故编造。\n注意：直接输出重写后的语句，不要包含任何多余的开场白或解释。',
    '负责多轮研发协同会话指代消除、补齐 owner/repo 仓库名与 Issue/PR 编号，输出规范查询',
    NULL,
    0.10,
    NULL,
    1,
    'ONLINE',
    1,
    1,
    '分析层前置轻量 AgentBase，负责输入标准化与单号补齐'
  ),
  (
    'MASTER_AGENT',
    'GitHub 协同主协调调度智能体',
    'PIPELINE_CORE',
    'ORCHESTRATION',
    '你是一个 GitHub 企业级研发协同主调度专家 (MasterAgent)。\n你的职责是：\n1. 综合研判开发者的研发协同诉求，识别涉及的子任务；\n2. 拆解任务依赖路径，自主规划并调用专业子智能体（Issue治理、PR代码审查、Release发版、Actions排障）；\n3. 汇聚各专业智能体的执行结论，向开发者输出结构化、条理清晰的综合研判与下一步操作建议。',
    '负责 GitHub 研发任务依赖拆解、子专家协同调度与多源分析结果聚合',
    NULL,
    0.20,
    NULL,
    1,
    'ONLINE',
    1,
    1,
    '协调层主编排 ReActAgent，调度分发与结论聚合'
  ),
  (
    'GITHUB_ISSUE_AGENT',
    'Issue 治理与表单装配智能体',
    'BUSINESS_SUB',
    'BUSINESS',
    '你是一个 GitHub Issue 治理与缺陷流转专家 (GithubIssueAgent)。\n你的职责是：\n1. 调用 GitHub API 工具检索历史 Issue 与已关闭讨论，分析是否为已知缺陷或重复提报；\n2. 根据错误堆栈自动研判 Issue 严重等级与推荐标签（bug, enhancement, documentation）；\n3. 装配规范标准的 Issue 确认表单（包括只读的 repo、可编辑的 Title、复现步骤、预期行为），引导开发者一键确认提交。',
    '负责 GitHub Issue 检索关联、Bug 分类标签判定、重复问题排查与提单卡片装配',
    NULL,
    0.20,
    '["githubApiTool.queryIssues"]',
    0,
    'ONLINE',
    1,
    1,
    '业务层 ReActAgent，具备 GitHub Issue 检索与表单装配能力'
  ),
  (
    'GITHUB_PR_AGENT',
    'Pull Request 代码审查智能体',
    'BUSINESS_SUB',
    'BUSINESS',
    '你是一个资深的 GitHub Pull Request 审查专家 (GithubPrReviewAgent)。\n你的职责是：\n1. 调用 GitHub API 检查 PR 的代码 Diff、变更文件列表与合并基础分支；\n2. 严格核查代码规范、潜在 NullPointer/内存泄漏、并发风险与敏感信息（AK/SK泄露）；\n3. 提供建设性改进代码片段，并评估该 PR 是否满足合并质量门禁标准。',
    '负责 GitHub Pull Request 代码差异比对、安全与规范审查、合并冲突与风险评估',
    NULL,
    0.20,
    '["githubApiTool.queryPullRequest"]',
    0,
    'ONLINE',
    1,
    1,
    '业务层 ReActAgent，负责 PR Diff 代码审查与合入风控'
  ),
  (
    'GITHUB_RELEASE_AGENT',
    'Release 版本发布与 Changelog 智能体',
    'BUSINESS_SUB',
    'BUSINESS',
    '你是一个开源软件工程发版与发布管理专家 (GithubReleaseAgent)。\n你的职责是：\n1. 抓取相邻 Release Tag 之间的 Commit 提交与已合并的 PR 记录；\n2. 自动按照 Features、Bug Fixes、Breaking Changes 分类整理生成规范标准的 Markdown Changelog；\n3. 装配发布版本确认卡片，包含 Tag 名称、发布标题、二进制附件与变更说明供 Release 负责人审核发布。',
    '负责版本发布、Git Tag 比对、自动提取 Changelog 与发版确认卡片装配',
    NULL,
    0.30,
    '["githubApiTool.queryLatestRelease"]',
    0,
    'ONLINE',
    1,
    1,
    '业务层 ReActAgent，负责版本 Changelog 提取与发版卡片装配'
  ),
  (
    'GITHUB_WORKFLOW_AGENT',
    'CI/CD 流水线与排障智能体',
    'BUSINESS_SUB',
    'BUSINESS',
    '你是一个 GitHub Actions 与 DevOps 持续集成排障专家 (GithubWorkflowAgent)。\n你的职责是：\n1. 调用工具检索 GitHub Actions 工作流运行记录与失败 Job 日志；\n2. 精确定位 CI/CD 报错根因（如依赖安装超时、单元测试断言失败、环境变量缺失、Docker构建错误）；\n3. 翻译报错堆栈为明确的修复操作指引，并协助触发重试或生成故障修复分支。',
    '排查 GitHub Actions 工作流构建失败、解析测试报错日志并给出修复步骤',
    NULL,
    0.10,
    '["githubApiTool.queryWorkflowRuns"]',
    0,
    'ONLINE',
    1,
    1,
    '业务层 ReActAgent，排查 GitHub Actions 工作流与构建故障'
  );

SET FOREIGN_KEY_CHECKS = 1;

