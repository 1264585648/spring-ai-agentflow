# AgentFlow Framework（企业级通用智能体微内核骨架）

> **核心架构守则（每次修改前必读）**  
> 本项目定位为通用的开源 Multi-Agent 微内核框架，不绑定具体业务。设计与重构以 [通用 Agent 骨架边界与设计哲学](docs/02-通用Agent骨架边界与设计哲学.md) 和 [内核架构设计与演进路线](docs/07-内核架构设计与演进路线.md) 为准。目标架构与已实现的代码不同，当前限制见下文。

## 项目介绍

基于 Java / Spring Boot 4 / Spring AI 2.0，以及 React 18 / TypeScript / Vite 的多智能体开发骨架。当前参考业务是 [智能运维与故障排障](docs/08-智能运维排障架构与详细设计说明书.md)，由 TroubleshootSubAgent 组织。

项目围绕流式交互、人机协同、L1/L2/L3 意图降级和 SubAgent / CardSubmitHandler 扩展点展开。最终目标是业务方引入底座并实现插件；当前仍是单工程、单机内存状态的开发阶段，不是已经完成的通用 DAG 引擎或可直接接生产写系统的成品。

## 当前实现与边界

最新说明：[10｜复核缺陷修复与验收](docs/10-复核缺陷修复与验收-2026-10-14.md)。[09｜原审查报告](docs/09-项目架构与代码审查报告-2026-10-13.md) 的问题没有一律标记关闭。

| 能力 | 已实现 | 边界 |
| --- | --- | --- |
| SSE | 8 类业务事件、turnId、sequence、独立 eventId、客户端去重 | 单机连接表，没有持久断点回放 |
| 卡片审批 | 快照、字段校验、批准对象、Principal 与 ActionAuthorizer、未知结果禁止重试 | 单 JVM；无持久账本与人工核查工作流 |
| API 访问 | 数据面/管理面默认拒绝，WebAccessPolicy 扩展点 | 未接真实 SSO/租户权限目录；demo 仅供本机 |
| L1 规则 | 快照、管理接口、白名单工具直通和同步适配器 | 真实数据库与多节点需环境验收 |
| L2 向量 | 真实模型失败不静默伪造向量，检索校验模型空间 | 历史数据和异常元数据治理待补齐 |
| 路由与协同 | 规则/关键词路由、顺序 StepResult 传递、失败停止依赖 | 非完整自主规划、DAG 或人工确认后原回合续跑 |
| 多轮记忆 | 有界窗口、TTL、改写及最终 Prompt 上下文 | 单机短期记忆，非持久存储 |
| 运行时控制 | 有界 worker、整回合 deadline、队列取消、中断、迟到输出拦截 | 不能保证撤销外部副作用，I/O 仍需自身超时 |
| Agent 配置 | 模型/温度/工具配置进入运行时 | 空清单语义、厂商可用性及内核边界待完善 |
| 前端 | 多卡片、回合定位、正文超时、未知回执锁定 | 类型/协议/构建验证不等于浏览器端到端验收 |

默认真实写操作关闭。模型不直接挂载运维写工具；卡片经 ActionGate，Handler 只接收 ApprovedCardSubmission。类型约束是可信 Java 插件间契约，不是恶意同进程代码的安全沙箱。

## 环境要求

本分支 CI 使用 **JDK 21、Node.js 22**。pom 的 Java 编译目标仍为 17；本轮没有在 JDK 17 运行全量测试，不宣称所有 JDK 组合均已验证。后端使用 Maven 3.9+；元数据依赖 MySQL 8.0+，Apollo 按集群同步配置启用。

## 本机快速开始

1. 创建独立演示 MySQL 数据库，配置 MYSQL_HOST、MYSQL_PORT、MYSQL_DB、MYSQL_USER、MYSQL_PASSWORD，执行 [init_schema.sql](sql/init_schema.sql)。默认初始化不执行 DROP；[reset_demo_schema.sql](sql/reset_demo_schema.sql) 是明确的破坏性重置，仅限可丢弃演示数据，不用于升级已有业务库。
2. 在线模型配置 OPENAI_API_KEY，可选 OPENAI_BASE_URL 和 OPENAI_MODEL。凭证只放环境变量或未入库的本地配置，不提交真实密钥。
3. 显式启用本机 demo profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

4. 启动前端：

```bash
cd frontend
npm ci
npm run dev
```

使用前端开发服务器的 /api 代理访问后端。可试 /help、#ping、/query user_id=10001，或输入“order-service 出现 504 网关超时，请协助排查”。模拟卡片提交只记录 Mock 结果，不修改真实系统。

**demo 绑定 127.0.0.1，允许本机匿名 Mock 演示。不要通过反向代理向公网或其他用户转发它。** 在线 LLM 不会把 Mock 日志/数据库证据变为真实证据。

不使用 demo 时，API 默认要求宿主认证的 Principal 及 WebAccessPolicy。无认证返回 401，无授权策略返回 403，自报用户 ID 请求头不能获得权限。真实写另需 ActionAuthorizer 和显式配置，但开关不替代持久执行记录、结果核查和生产验收。

## 验证

Actions 执行全量 Maven 测试、客户端严格 TypeScript 编译、frontend/tests/client-regression.test.cjs 与前端生产构建。以对应提交 CI 日志为准，见 [验收记录](docs/10-复核缺陷修复与验收-2026-10-14.md)。

```bash
mvn -B --no-transfer-progress test
cd frontend
npm ci
npx tsc --strict --target ES2022 --module commonjs --lib ES2022,DOM --outDir /tmp/agentflow-client-tests src/api/sseClient.ts
CARD_CLIENT_JS=/tmp/agentflow-client-tests/api/sseClient.js node --test tests/client-regression.test.cjs
npm run build
```

客户端命令使用 POSIX shell 环境变量语法；Windows 可在 WSL/Git Bash 运行，或用 PowerShell 设置对应变量。

## 开发工作流与知识沉淀

每步改动保持可审查，不用跳过测试掩盖接口迁移问题；同步记录实现、验证范围和未完成事项。取舍沉淀到 docs/handbook。

本次新增：[执行结果未知为什么不能自动重试](docs/handbook/16-执行结果未知为什么不能自动重试.md)、[SSE 事件身份与回合截止时间](docs/handbook/17-SSE事件身份与回合截止时间.md)。

## 核心文档

完整索引：[docs/README.md](docs/README.md)。

- [00｜工程规范](docs/00-工程规范与编码守则.md)、[02｜骨架边界](docs/02-通用Agent骨架边界与设计哲学.md)、[07｜演进路线](docs/07-内核架构设计与演进路线.md)
- [03｜接口与 SSE 契约](docs/03-前后端接口与SSE协议契约.md)、[05｜前端架构](docs/05-前端工程架构与组件设计说明书.md)、[08｜运维场景](docs/08-智能运维排障架构与详细设计说明书.md)
- [04｜智能体矩阵](docs/04-企业多智能体职责矩阵与分层设计.md)、[06｜FlowUs MCP](docs/06-息流FlowUs-MCP集成指南.md)
- [实战专栏](docs/handbook/README.md)、[Java 工程手册](docs/java-engineering/README.md)、[AI 基础专栏](docs/ai-fundamentals/README.md)
