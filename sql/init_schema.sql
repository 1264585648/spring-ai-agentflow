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
    '📌 **智能运维排障助手快捷指令**：\n- `/504`：极速直出 504 网关超时排查 SOP 手册\n- `/query user_id=<编号>`：快速查询账户水位与状态\n- `/help`：获取全部支持命令\n- `#ping`：极速测试系统响应与直通链路', 
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
    'CMD_SOP_504', 
    '504网关超时排查SOP', 
    'PREFIX', 
    '/504', 
    'STATIC_TEXT', 
    NULL, 
    NULL, 
    5, 
    1, 
    '【504 Gateway Timeout 极速排查 SOP】\n1. 核查上游网关连接池水位（HikariCP/Druid）是否打满；\n2. 检查应用层 GC 停顿耗时（jstat -gcutil）排查 Full GC 阻塞；\n3. 检索数据库慢SQL日志（查询时长 > 3s 的长事务）；\n4. 临时止血：扩容连接池最大上限或切流降级非核心流量。'
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

-- 初始化 6 大核心多智能体矩阵定义 (企业级智能运维与排障体系)
INSERT INTO `sys_agent_definition`
  (`agent_code`, `agent_name`, `agent_type`, `layer`, `system_prompt`, `dispatch_desc`, `model_name`, `temperature`, `attached_tools`, `is_system_core`, `status`, `is_enabled`, `version`, `description`)
VALUES
  (
    'QUERY_REWRITER',
    '会话分析与查询重写智能体',
    'PIPELINE_CORE',
    'ANALYSIS',
    '你是一个专业的运维会话分析与查询重写专家 (QueryRewritingAgent)。\n你的职责是：\n1. 结合多轮对话上下文，消除用户输入中的模糊口语代词（如“这个服务为什么挂了”、“看下刚才那个慢SQL”）；\n2. 提取并补齐关键服务标识与单号（如 order-service、TraceID、工单号）；\n3. 将口语化诉求重写为语义清晰、实体完备的独立查询语句；\n4. 若输入已完备，原样输出，严禁添枝加叶或无故编造。\n注意：直接输出重写后的语句，不要包含任何多余的开场白或解释。',
    '负责多轮运维协同会话指代消除、提取关键服务名、TraceID与故障单号，输出规范查询',
    NULL,
    0.10,
    NULL,
    1,
    'ONLINE',
    1,
    1,
    '分析层前置轻量 AgentBase，负责输入标准化与单号实体补齐'
  ),
  (
    'MASTER_AGENT',
    '主协调调度智能体',
    'PIPELINE_CORE',
    'ORCHESTRATION',
    '你是一个企业级智能运维与故障排查主调度专家 (MasterAgent)。\n你的职责是：\n1. 综合研判工程师的故障排查诉求，识别涉及的子任务（如：日志异常检索、数据库慢查与连接池分析、止血方案制定）；\n2. 拆解任务依赖路径，自主规划并调用专业子智能体（日志专家、数据库专家、SRE协同专家）；\n3. 汇聚各专业智能体的执行结论，向工程师输出结构化、条理清晰的综合研判与下一步操作建议。',
    '负责复杂任务与故障依赖拆解、子专家协同调度与多源分析结果聚合',
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
    'GENERAL_AGENT',
    '通用对话与协同智能体',
    'PIPELINE_CORE',
    'BUSINESS',
    '你是一个企业级智能协同与技术答疑专家 (GeneralAgent)。\n请针对工程师的技术疑问提供专业、严谨、条理清晰的解答；若遇到特定故障场景，引导工程师使用规范指令或提供服务名进行针对性排查。',
    '负责通用技术问答、框架功能说明与未命中特定专家时的兜底解答',
    NULL,
    0.70,
    NULL,
    1,
    'ONLINE',
    1,
    1,
    '业务层通用问答 Agent，负责基础技术咨询与兜底'
  ),
  (
    'LOG_DIAGNOSE_AGENT',
    '日志异常分析智能体',
    'BUSINESS_SUB',
    'BUSINESS',
    '你是一个资深的微服务日志与分布式链路诊断专家 (LogDiagnoseAgent)。\n你的职责是：\n1. 检索目标服务指定时间段内的 Error/Warn 日志，提取核心报错堆栈与异常根因（如 NPE, Timeout, OOM, 连接超时）；\n2. 结合 TraceID 进行全链路拓扑追踪，定位最深层报错源头服务；\n3. 对比历史故障知识库，输出清晰的报错原因与代码层修复排查建议。',
    '负责微服务日志检索、异常堆栈解析、Trace 分布式链路追踪与已知报错排查',
    NULL,
    0.10,
    '[]',
    0,
    'ONLINE',
    1,
    1,
    '业务层 ReActAgent，负责微服务异常日志与全链路 Trace 诊断'
  ),
  (
    'DB_DIAGNOSE_AGENT',
    '数据库诊断智能体',
    'BUSINESS_SUB',
    'BUSINESS',
    '你是一个企业级数据库与高并发性能调优专家 (DbDiagnoseAgent)。\n你的职责是：\n1. 诊断数据库慢查询日志，提取长耗时 SQL 语句并分析缺少索引、大表全表扫描等性能瓶颈；\n2. 监测当前活跃连接、长事务占用与锁等待情况，排查死锁与连接池打满问题；\n3. 输出 Explain 执行计划分析结论与安全的索引调优建议。',
    '负责数据库慢SQL检索、死锁与长事务分析、连接池水位诊断与性能调优建议',
    NULL,
    0.10,
    '[]',
    0,
    'ONLINE',
    1,
    1,
    '业务层 ReActAgent，负责慢查询、死锁与数据库性能水位诊断'
  ),
  (
    'SRE_COPILOT_AGENT',
    '应急止血与运维协同智能体',
    'BUSINESS_SUB',
    'BUSINESS',
    '你是一个 SRE 网站可靠性与应急处置专家 (SreCopilotAgent)。\n你的职责是：\n1. 汇聚日志与数据库的诊断结论，评估系统当前受影响程度与故障级别；\n2. 制定高可行的应急止血方案（如临时调大连接池、Kill 阻塞慢查、流量熔断降级或服务滚动重启）；\n3. 遵循 Human-in-the-loop 安全红线，严禁直接自动执行高危写操作，必须装配标准化应急处置卡片交由工程师二次核验后一键执行。',
    '负责故障综合研判、制定应急止血处置方案、装配确认卡片并引导工程师核验执行',
    NULL,
    0.20,
    '[]',
    0,
    'ONLINE',
    1,
    1,
    '业务层 ReActAgent，负责应急方案拟定与卡片装配'
  );

SET FOREIGN_KEY_CHECKS = 1;

