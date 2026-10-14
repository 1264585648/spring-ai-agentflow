-- DESTRUCTIVE LOCAL DEMO RESET. Never run against production or existing user data.
-- 警告：本文件删除全部演示配置表；正常初始化请使用 init_schema.sql。
SET NAMES utf8mb4;
DROP TABLE IF EXISTS `sys_faq_definition`;
DROP TABLE IF EXISTS `sys_agent_definition`;
DROP TABLE IF EXISTS `sys_rule_audit_log`;
DROP TABLE IF EXISTS `sys_rule_chain_step`;
DROP TABLE IF EXISTS `sys_rule_definition`;
