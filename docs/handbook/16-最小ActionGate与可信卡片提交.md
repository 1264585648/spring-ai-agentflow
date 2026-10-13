# 最小 ActionGate：把卡片确认变成受控执行入口

> 改造基线：`9fb7cbbd59f6f1d5fda7f1285527263a47ba9ab4`（第一步直接写工具旁路修复）。
> 本文描述本次改动，不表示完整生产审批系统、持久执行引擎或插件沙箱已经完成。

## 为什么是第二步

第一步移除了模型工具挂载中的 `OpsActionTool` 以及写方法的 `@Tool` 注解，但卡片 Controller 仍负责校验、选择 Handler 和执行。它使用客户端 `cardType` 路由，并把原始请求继续交给业务处理器。已有 `CardRegistry` 的服务端快照和字段校验不应推倒重写，应该接入统一入口。

本次只收拢这一条执行链，不新增 DAG、自研组件容器或第二套模型循环。

```text
HTTP JSON + 服务端 Principal
    -> CardInteractionController
    -> ActionGate
         -> 读取服务端提案，绑定 actionId / sessionId / cardType
         -> 按字段快照校验与重建参数
         -> 模拟开关 / 真实写开关 / ActionAuthorizer
         -> Handler 无副作用预检
         -> CardRegistry 原子申领
         -> CardSubmitHandler(ApprovedCardSubmission)
         -> 成功归档，或标记 EXECUTION_UNKNOWN
    -> HTTP 回执；SSE 通知是成功归档后的尽力发送
```

## 请求数据与执行数据必须分开

`CardSubmitRequest` 仍是 HTTP DTO。Handler 改为接收 `ApprovedCardSubmission`：无公开构造器、无 Builder、无 setter，参数 Map 不可修改，值只允许不可变标量。它使用 Lombok `@Data` 和 final 字段，遵循项目的模型规范而不生成可变 setter。

只读字段可以不回传；回传时必须与签发值一致，真正执行时使用服务端值。可编辑字段缺省时沿用签发值；显式清空必填字段会被拒绝。未知字段（包括未签发的 `operator`）、嵌套对象和数组被拒绝。`number` 字段使用 BigDecimal，接受数字字符串，检查有限性和 minLimit/maxLimit；文本字段不通过 trim 改写只读标识。当前 select 没有可选值集合契约，不能声称已经校验了选项白名单。

运维 Handler 不再自己 new Mock，也不再用 `order-service` 或 `trx_10423` 填补缺失执行目标。`operator` 使用批准对象里的 actorId，而不是表单字段。示例卡片工厂仍包含演示预填目标，这不等于已经从真实诊断证据生成安全提案。

## 身份授权：默认拒绝，不用 sessionId 冒充身份

框架装配的两个开关缺省均为 false。仓库应用配置显式打开模拟提交，真实写保持 false。模拟路径只允许声明 `isSimulation()` 的受信任 Handler；运维 Handler 检查实际注入的 Mock 适配器，普通 OpsActionPort 不会被当成模拟。

真实写必须同时满足开关显式启用、有服务端 Principal、宿主提供的 ActionAuthorizer 允许。没有授权插件时使用 DENY_ALL。认证主体不会从请求 JSON、sessionId 或任意 X-User-Id 请求头提取。

ActionAuthorizer 必须核验实际的用户/租户、会话归属、动作类型以及最终目标和参数。**本次未接入公司的认证系统，也没有建立租户/会话所有权数据库。** 只实现扩展入口和缺省拒绝，不提供“打开开关即可生产使用”的承诺。模拟 actor 固定为 `mock-demo`，不能被当成真实审计身份。

`isSimulation` 是受信任插件的契约，不是对恶意 Java 插件的安全认证。普通 Java 仍可直接调用遗留 Port/辅助类；本次收拢卡片路径，并延续上一步对已知模型/L1 旁路的关闭，没有声称全 JVM 写操作都被拦截。

## 失败不能统一处理成“重试”

校验和授权发生在申领前。参数不合规、越权、类型不匹配不会消耗提案，校验修正后仍可提交。

一旦进入执行器，超时、异常、空结果或 success=false 都不能证明远端没有生效。因此：

```text
ISSUED -> SUBMITTING -> SUBMITTED
                    -> EXECUTION_UNKNOWN（禁止自动重试）
```

`EXECUTION_UNKNOWN` 必须先核查外部系统，不能为了“重试”直接换一个 actionId 重签。它不是失败回滚、不是 exactly-once 保证，也不是已经实现了结果查询接口。未来应引入持久执行记录、下游幂等键与结果核对机制。

到达 TTL 后，只允许尚未执行的 ISSUED 提案过期。执行中/已完成/结果未知不会被并发的过期检查覆盖。重复 issue 同一 actionId 被拒绝；查询和申领返回快照副本，不能通过修改返回对象重置服务器状态。

当前存储仍是进程内 Map。终态记录保留去重，尚无容量治理；进程重启丢失状态，多节点不能共享锁。这是明确的生产阻断项。

## 前后端协作

HTTP 接口路径和请求字段保持不变。错误响应新增 `errorType` 与 `retryable`。只读篡改、会话/类型/授权错误返回 403，重复或非待提交状态返回 409，结果未知返回 500 并带 `retryable=false`。

前端 API 保留错误类型；卡片对不可重试、回执损坏和网络断开停止再次提交，不继续显示“稍后重试”。前端锁定不是安全边界，最终由服务端状态机拒绝重复执行。这里只更新卡片交互，不实现任务暂停后恢复原 turn，也不增加 SSE 事件种类。

原来卡片入口的通配 CORS 已移除。默认同源/Vite 代理可继续使用；跨域宿主须独立配置可信来源和凭证策略。CSRF、防越权和认证集成仍需宿主负责，不能用通配 CORS 替代。

成功已归档后 SSE 通知失败不再把 HTTP 改成 500，从而避免诱导用户重复点击。

## 接入迁移

业务 Handler 需要将 `handleSubmit(CardSubmitRequest)` 改为 `handleSubmit(ApprovedCardSubmission)`。新增可选的纯参数 `validate` 方法；不得在 validate 中做外部写操作。真实 Handler 不要覆盖 isSimulation 为 true。

CardRegistry 的外部实现需要实现 `markExecutionUnknown`，保证原子状态变化、快照不可外部修改和禁止覆盖签发记录。代码直接构造 CardInteractionController 的接入方需要改为注入 ActionGate 和 SseEventPublisher；Controller 不再自行 new 内存注册表。

完整 HTTP 契约以 [03-前后端接口与SSE协议契约](../03-前后端接口与SSE协议契约.md) 第 3.4 节为准。

## 验证

新增 ActionGateTest、ActionGateConfigurationTest，更新 CardRegistryTest、CardInteractionControllerTest、OpsActionCardSubmitHandlerTest，并保留第一步 DirectWriteToolBoundaryTest 的模型工具定义和 L1 边界断言。

```bash
mvn -B -Dtest=ActionGateTest,ActionGateConfigurationTest,CardRegistryTest,CardInteractionControllerTest,OpsActionCardSubmitHandlerTest,DirectWriteToolBoundaryTest,TroubleshootSubAgentTest,AgentPipelineServiceImplTest,MultiTurnPipelineIntegrationTest,CapabilityToolsTest,L1ToolDispatcherAllowlistTest test

cd frontend
npm ci
npx tsc --strict --target ES2022 --module commonjs --lib ES2022,DOM --outDir /tmp/agentflow-card-tests src/api/sseClient.ts
CARD_CLIENT_JS=/tmp/agentflow-card-tests/api/sseClient.js node --test tests/card-submit.test.cjs
npm run build
```

编辑环境中，API 客户端严格 TypeScript 编译和 9 项 Node 测试已运行通过；InteractiveCard.tsx 仅做语法转译检查；19 个 Java 改动文件仅通过 JDK parse-only 语法解析，未进行类型检查、注解处理或 JUnit 执行。Maven 缺失且外部仓库域名解析失败，不能声称后端已编译通过。新增 CI 配置供可联网 runner 执行，实际结论以对应 PR 的检查记录为准。
