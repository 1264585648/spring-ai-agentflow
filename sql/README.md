# 数据库脚本与架构规范 (sql/)

本目录包含 Multi-Agent 系统的数据库建表与初始化脚本，面向企业级部署与开源使用者一键执行。

---

## 🗄️ 文件清单

| 文件名 | 用途说明 | 适用版本 |
| :--- | :--- | :--- |
| [init_schema.sql](./init_schema.sql) | L1 规则引擎与工作流核心表结构定义及演示种子数据 | MySQL 8.0+ |

---

## 📊 实体关系图 (ER Diagram)

```mermaid
erDiagram
    SYS_RULE_DEFINITION ||--o{ SYS_RULE_CHAIN_STEP : "1:N (当 target_type=WORKFLOW 时级联步骤)"
    SYS_RULE_DEFINITION ||--o{ SYS_RULE_AUDIT_LOG : "1:N (记录命中与时延日志)"

    SYS_RULE_DEFINITION {
        bigint id PK "主键自增"
        varchar rule_code UK "唯一业务编码(如 CMD_QUERY_ACCOUNT)"
        varchar rule_name "规则友好名称"
        varchar match_type "匹配类型: PREFIX, EXACT, REGEX"
        varchar pattern_expr "匹配模式表达式"
        varchar target_type "执行类型: TOOL, STATIC_TEXT, INTERACTIVE_CARD, WORKFLOW"
        varchar target_ref "执行目标引用(Tool标识/卡片模板/静态文本)"
        varchar param_template "参数解析与映射模板(JSON格式)"
        int priority "匹配优先级(数字越小越先匹配)"
        tinyint is_enabled "启用状态(1=启用, 0=禁用)"
        bigint hit_count "累计命中计数"
        varchar description "业务描述说明"
        datetime created_at "创建时间"
        datetime updated_at "更新时间"
    }

    SYS_RULE_CHAIN_STEP {
        bigint id PK "主键自增"
        bigint rule_id FK "关联规则主表ID"
        int step_order "步骤顺序(1, 2, 3...)"
        varchar step_name "步骤名称"
        varchar target_tool "目标 Tool Bean及方法"
        varchar param_mapping "步骤入参映射"
        varchar failure_strategy "失败策略: ABORT, CONTINUE, FALLBACK"
        datetime created_at "创建时间"
        datetime updated_at "更新时间"
    }

    SYS_RULE_AUDIT_LOG {
        bigint id PK "主键自增"
        varchar rule_code "命中的规则编码"
        varchar session_id "对话会话ID"
        varchar raw_input "用户原始输入文本"
        text extracted_params "提取的JSON参数"
        varchar status "执行状态: SUCCESS, FAILED"
        int cost_ms "执行耗时(毫秒)"
        text error_msg "异常信息"
        datetime created_at "记录时间"
    }
```

---

## 🚀 部署执行说明

### 1. 登录 MySQL 并创建数据库
推荐使用 UTF-8 超集编码（`utf8mb4`）以及现代排序规则（`utf8mb4_unicode_ci`）：

```sql
CREATE DATABASE IF NOT EXISTS `ai_agent_db` 
  DEFAULT CHARACTER SET utf8mb4 
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE `ai_agent_db`;
```

### 2. 执行初始化脚本
通过命令行导入：
```bash
mysql -u root -p ai_agent_db < sql/init_schema.sql
```
或者在 Navicat / DataGrip / DBeaver 等可视化客户端中打开 `sql/init_schema.sql` 并在 `ai_agent_db` 下运行全部语句。
