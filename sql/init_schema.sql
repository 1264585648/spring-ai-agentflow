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
-- 4. 开源演示与种子数据 (Seed Data)
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
    '📌 **可用快捷指令清单**：\n- `/query user_id=<编号>`：快速查询用户流水与状态\n- `/help`：获取支持的全部命令说明\n- `#ping`：极速测试系统响应与直通链路', 
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

SET FOREIGN_KEY_CHECKS = 1;
