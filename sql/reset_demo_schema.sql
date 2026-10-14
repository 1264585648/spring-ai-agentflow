-- =============================================================================
-- Multi-Agent 微内核骨架 - 本地演示环境破坏性重置脚本 (仅限本地开发/Demo环境使用)
-- 警告：本脚本会 DROP 全部核心配置表并清空已有数据，严禁在生产环境或含增量数据的环境执行！
-- 正常初始化或增量升级请使用 sql/init_schema.sql。
-- 放置路径：sql/reset_demo_schema.sql
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `sys_faq_definition`;
DROP TABLE IF EXISTS `sys_agent_definition`;
DROP TABLE IF EXISTS `sys_rule_audit_log`;
DROP TABLE IF EXISTS `sys_rule_chain_step`;
DROP TABLE IF EXISTS `sys_rule_definition`;

SET FOREIGN_KEY_CHECKS = 1;
