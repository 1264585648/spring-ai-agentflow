# 多轮对话指代消除实战：为什么大模型落地绝不能直接把原始 Query 送进 Pipeline？

> **所属专栏**：企业级 Multi-Agent 架构实战手册  
> **核心标签**：`多轮会话` `指代消除` `QueryRewriter` `ConversationMemory` `微内核意图识别`

---

## 一、 生产痛点：为什么用户总是不好好说话？

在单轮问答（Single-turn QA）场景中，评测效果往往非常惊艳。但在真实生产环境中，**工程师从来不会一遍遍重复完整的上下文**。

典型的企业排障对话轨迹往往是这样的：

```text
Round 1:
工程师: "order-service 最近经常出现 504 Gateway Timeout，帮忙排查下"
助手: "正在检索 order-service 的日志与慢查询...发现长事务 trx_10423 锁住了 orders 表。"

Round 2:
工程师: "把它的错误日志发我看看" (模糊指代: "它"是谁？)

Round 3:
工程师: "刚才那个慢查询 kill 掉了吗？" (省略实体: 哪笔慢查询？属于哪个服务？)
```

如果团队天真地把用户在 Round 2、Round 3 的原始 Query（`"把它的错误日志发我看看"`、`"刚才那个慢查询 kill 掉了吗？"`）直接送进意图降级与流水线 Pipeline，系统将遭遇灾难性的溃败：

| 架构层级 | 直接送入原始 Query 的后果 | 故障现象 |
| :--- | :--- | :--- |
| **L1 规则引擎** | 正则与关键词无法匹配（缺少 `order-service`） | 100% 规则失效放行 |
| **L2 向量检索** | `"把它的错误日志发我看看"` 向量严重语义漂移 | 检索出无关的通用日志 FAQ 或低于阈值放行 |
| **L3 MasterAgent 路由** | 调度器无法从 Query 中提取 `service` 与参数 | 无法准确匹配到 `LOG_DIAGNOSE_AGENT`，沦为通用闲聊兜底 |
| **SubAgent 工具执行** | `LogQueryTool.queryLogs(service, ...)` 参数缺失 | 工具入参为 null 或空字符串，底层调用报错 |

**核心结论**：  
**绝不能让带有代词、省略实体的原始 Query 直接流入 IntentResolver 责任链！**  
在流水线的最前置，必须设立**指代消除与查询重写防线（Query Rewriting Layer）**。

---

## 二、 架构设计：两层防线协同

为了实现零业务侵入与毫秒级时延控制，系统将多轮会话拆解为两级能力：
1. **会话短期记忆（`ConversationMemory`）**：负责跨回合问答沉淀与滑动窗口（Sliding Window FIFO）容量控制；
2. **多轮指代重写器（`QueryRewriter`）**：负责在进入责任链前将口语化代词补全为完整自包含提问。

```mermaid
flowchart TD
    UI["用户发起提问 Query"] --> Pipe["AgentPipelineServiceImpl"]
    
    subgraph MemLayer ["① 会话短期记忆层 (ConversationMemory)"]
        Mem["InMemoryConversationMemory<br/>(滑动窗口 FIFO 自动淘汰，线程安全)"]
    end
    
    subgraph PreProcess ["② 前置意图改写层 (QueryRewriter)"]
        Bypass{"是否为 L1 极速指令?<br/>(以 / 或 # 开头)"}
        Rewriter["LlmQueryRewriter<br/>(挂载 QUERY_REWRITER 专属轻量模型)"]
        Heuristic["离线自适应启发式降级<br/>(实体倒序贪婪回填)"]
    end
    
    subgraph Chain ["③ 多级意图解析责任链 (IntentResolver Chain)"]
        L1["L1RuleResolver (极速规则直出)"]
        L2["L2VectorResolver (权威 FAQ 直出)"]
        L3["L3MasterAgentResolver (专家路由规划)"]
    end
    
    Pipe --> Mem
    Mem -->|提取最近 6 轮历史| Bypass
    Bypass -->|是 (如 /help, #ping)| L1
    Bypass -->|否且存在历史| Rewriter
    Rewriter -->|大模型改写异常或离线| Heuristic
    Rewriter -->|生成 effectiveQuery| L1
    Heuristic -->|生成 effectiveQuery| L1
    L1 --> L2 --> L3
```

---

## 三、 关键实现细节与避坑考量

### 1. 极速绕行机制（Fast-Path Bypass）：守护 L1 毫秒级时延

很多工程师在引入查询重写时，会给每个请求都无差别地调用一遍大模型，导致原本 `< 1ms` 响应的快捷指令（如 `/help`、`#ping`、`/504`）突然暴增到了 `1000ms+`！

**架构守则**：
```java
// LlmQueryRewriter.java
String trimmed = rawQuery.trim();

// 极速绕行：若以命令前缀 / 或 # 开头，属于 L1 极速指令，绝对不进入 LLM 改写链路！
if (trimmed.startsWith("/") || trimmed.startsWith("#")) {
    log.debug("[QueryRewriter] 检测到 L1 极速指令前缀，跳过改写: {}", trimmed);
    return rawQuery;
}

// 若无历史记录，无需进行指代消除
if (history == null || history.isEmpty()) {
    return rawQuery;
}
```

### 2. 角色隔离与专用人设（`QUERY_REWRITER`）

查询重写必须使用专用的轻量小模型与紧凑人设，**严禁使用与业务专家相同的 ChatClient**（防范人设污染与 Token 膨胀，见 Handbook 第 08 篇）。

在 `sys_agent_definition` 数据库中预制独立的人设与超低采样温度（`temperature = 0.1`）：
```sql
INSERT INTO `sys_agent_definition`
  (`agent_code`, `agent_name`, `agent_type`, `temperature`, `system_prompt`)
VALUES
  (
    'QUERY_REWRITER',
    '会话分析与查询重写智能体',
    'PIPELINE_CORE',
    0.10,
    '你是一个专业的运维会话分析与查询重写专家 (QueryRewritingAgent)。\n你的职责是：\n1. 结合多轮对话上下文，消除用户输入中的模糊口语代词（如“这个服务为什么挂了”、“看下刚才那个慢SQL”）；\n2. 提取并补齐关键服务标识与单号（如 order-service、TraceID、工单号）；\n3. 将口语化诉求重写为语义清晰、实体完备的独立查询语句；\n4. 若输入已完备，原样输出，严禁添枝加叶或无故编造。\n注意：直接输出重写后的语句，不要包含任何多余的开场白或解释。'
  );
```

### 3. 全链路透传：`effectiveQuery` 一次改写，全链受益

改写后的文本统一设入 `AgentContext.setEffectiveQuery(rewritten)`，并在 SSE 思考链发出友好提示：
```java
if (queryRewriter != null && history != null && !history.isEmpty()) {
    String rewritten = queryRewriter.rewrite(context, history);
    if (StringUtils.hasText(rewritten) && !rewritten.trim().equalsIgnoreCase(query.trim())) {
        log.info("[Pipeline] 意图指代消除完成: '{}' ➔ '{}'", query, rewritten);
        context.setEffectiveQuery(rewritten);
        context.getSink().thinking("检测到多轮上下文，问题已自动补全为: " + rewritten);
    }
}
```

下游的 `L1RuleResolver`、`L2VectorResolver`、`L3MasterAgentResolver` 统一读取 `effectiveQuery`：
- 用户提问：`"把它的错误日志发我看看"`
- 补全输出：`"把 order-service 的错误日志发我看看"`
- `MasterAgentRouter` 动态识别出核心关键词 `order-service` 与 `日志`，精准分发至 `LOG_DIAGNOSE_AGENT`；
- `TroubleshootSubAgent` 接收到的 `step.getTaskDesc()` 包含了准确的服务名，日志分析端口立即成功下发查询！

### 4. 离线单测与自适应启发式降级（Heuristic Fallback）

大模型接口可能发生超时、限流或在本地 CI/CD 单测中无外部网络。重写器内置了自适应启发式降级逻辑：
- 倒序遍历最近历史对话；
- 通过正则抓取明确的服务名（如 `order-service`、`payment-service`）；
- 将代词（`它` / `这个服务`）精准替换为历史实体，保障在完全离线环境下整套多轮协同链路仍可 100% 跑通测试。

---

## 四、 总结与最佳实践

1. **Query 是可变的，Context 是贯穿的**：通过 `AgentContext` 将 `query`（用户原始输入）与 `effectiveQuery`（改写后的有效输入）清晰区分，保留原始 audit 审计线索的同时，为下游提供完备实体；
2. **端到端闭环**：`SseAgentEventSink` 收集流式输出并在 `done()` 时自动沉淀本轮问答，使得流水线各环节无感知完成记忆累加；
3. **安全与性能兼备**：指令前缀白名单绕行守护核心极速直出时延，专用小模型人设守护改写语义质量。
