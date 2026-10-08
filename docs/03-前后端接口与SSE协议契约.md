# 前后端通信协议与 SSE 事件流交互契约规范

## 1. 文档概述

本文档定义了智能业务协同与人机协同系统在**接入层（Access Layer）**的前后端交互规范。系统采用 **HTTP REST + Server-Sent Events (SSE)** 双向/流式交互模式：
- **下行通道 (Server ➔ Client)**：通过 SSE 单向长连接，实时推送大模型思考链 (`thinking`)、打字机文字块 (`message`)、工具调用进度 (`progress`)、方案确认卡片 (`bpm_confirm_card` / `interactive_card`) 以及后续推荐问题 (`recommend_questions`)。
- **上行通道 (Client ➔ Server)**：通过标准 HTTP POST 发送业务提问，并在专员核验方案后提交工单数据。

---

## 2. 交互时序流程图

```mermaid
sequenceDiagram
    autonumber
    participant UI as 前端业务工作台
    participant Controller as 接入层 (ChatController)
    participant Pipeline as 业务流水线 (PipelineService)
    participant BPM as BPM 控制器 / 接口

    Note over UI, Controller: 1. 建立长连接
    UI->>Controller: GET /api/v1/chat/connect?sessionId={sessionId}
    Controller-->>UI: 200 OK (Content-Type: text/event-stream)

    Note over UI, Controller: 2. 专员发起咨询
    UI->>Controller: POST /api/v1/chat/ask {sessionId, query, caseId}
    Controller-->>UI: 200 OK {code: 200, message: "已受理"}
    Controller->>Pipeline: 异步分发处理

    Note over Pipeline, UI: 3. SSE 流式过程下发
    Pipeline-->>UI: event: thinking (思考分析政策)
    Pipeline-->>UI: event: progress (调用业务系统试算折让/补偿上限)
    Pipeline-->>UI: event: message (流式文本回复: "经核实，该客户诉求符合特殊服务补偿条件...")
    
    rect rgb(255, 248, 230)
    Note over Pipeline, UI: 4. Human-in-the-loop 交互确认 (核心)
    Pipeline-->>UI: event: bpm_confirm_card (下发工单预填卡片数据)
    UI->>UI: 渲染方案卡片 (服务单号锁定，补偿金额允许专员微调)
    end

    Pipeline-->>UI: event: recommend_questions (推荐下一步追问)
    Pipeline-->>UI: event: done (本次对话流结束)

    Note over UI, BPM: 5. 专员核验后确认提单
    UI->>BPM: POST /api/v1/bpm/submit-ticket {actionId, sessionId, caseId, formValues}
    BPM-->>UI: 200 OK {code: 200, bpmInstanceId: "BPM-20261008-001"}
```

---

## 3. HTTP 接口定义

### 3.1 建立 SSE 通道连接
* **接口路径**：`GET /api/v1/chat/connect`
* **协议头**：`Accept: text/event-stream`
* **请求参数**：
  | 参数名 | 类型 | 必填 | 说明 |
  | :--- | :--- | :--- | :--- |
  | `sessionId` | String | 是 | 唯一会话标识，用于多轮对话关联与 Emitter 索引 |

* **返回格式**：`text/event-stream;charset=UTF-8`

---

### 3.2 专员发送提问
* **接口路径**：`POST /api/v1/chat/ask`
* **Content-Type**：`application/json`
* **请求体 (Request Body)**：
```json
{
  "sessionId": "sess_88921a9f-4310",
  "query": "服务单 ORD_10086 客户遭遇突发特殊情况影响服务履约，如何申请特批服务补偿？",
  "userId": "AGENT_007",
  "caseId": "ORD_10086"
}
```
* **响应体 (Response Body)**：
```json
{
  "code": 200,
  "message": "请求已提交处理",
  "sessionId": "sess_88921a9f-4310"
}
```

---

### 3.3 确认并提交 BPM 审批工单
* **接口路径**：`POST /api/v1/bpm/submit-ticket`
* **Content-Type**：`application/json`
* **请求体 (Request Body)**：
```json
{
  "actionId": "act_9f8a32b14e9a",
  "sessionId": "sess_88921a9f-4310",
  "caseId": "ORD_10086",
  "bpmProcessKey": "SERVICE_SPECIAL_COMPENSATION_FLOW",
  "formValues": {
    "relief_amount": 500.00,
    "relief_type": "突发特殊情况特批补偿",
    "apply_reason": "客户反馈突发不可抗力事件，已上传官方证明材料，申请酌情给予服务补偿与费用折让。"
  }
}
```
* **响应体 (Response Body)**：
```json
{
  "code": 200,
  "message": "BPM 工单提报成功",
  "bpmInstanceId": "BPM-20261008-9821",
  "processKey": "DEBT_SPECIAL_RELIEF_FLOW"
}
```

---

## 4. SSE 事件流协议与 Payload 契约

后端向前端推送的标准格式遵循 W3C EventSource 规范：
```text
event: <eventType>\n
id: <timestamp>\n
data: <JSON_STRING>\n\n
```

### 4.1 事件全集一览

| 事件类型 (`event`) | 产生时机 | 前端渲染表现 |
| :--- | :--- | :--- |
| `thinking` | Agent 推理中 | 灰色字体折叠面板，带有“思考中...”动态动画 |
| `progress` | 调用 RAG / 业务系统 / BPM 查询时 | 步骤条/轻提示（如：“正在调取业务折让上限...”） |
| `message` | 大模型文本输出 | 打字机逐字输出 Markdown 正文 |
| `bpm_confirm_card` | 形成明确解决方案，需人工核验提单 | 渲染交互式表单卡片，提供输入框、金额微调与提交按钮 |
| `recommend_questions`| 流结束前 | 输出 2~3 个相关联的快捷提问气泡 |
| `done` | 当前回合结束 | 停止加载动画，激活提问输入框 |
| `error` | 处理发生严重异常 | 红色轻提示或降级错误信息 |

---

### 4.2 核心事件 Payload 样例

#### (1) `thinking` 思考事件
```json
{
  "event": "thinking",
  "data": {
    "content": "正在检索业务规范及《企业客户服务特批补偿管理实施细则》，核对凭证要求..."
  }
}
```

#### (2) `progress` 业务进度事件
```json
{
  "event": "progress",
  "data": {
    "stage": "POLICY_RETRIEVAL",
    "description": "已命中特批补偿标准条目，正在核算可补偿金额上限"
  }
}
```

#### (3) `message` 打字机内容流
```json
{
  "event": "message",
  "data": {
    "content": "经核实，客户提供的凭证材料符合特殊服务补偿政策。\n"
  }
}
```

#### (4) `bpm_confirm_card` BPM 方案确认卡片（关键协议）
```json
{
  "event": "bpm_confirm_card",
  "data": {
    "actionId": "act_9f8a32b14e9a",
    "bpmProcessKey": "SERVICE_SPECIAL_COMPENSATION_FLOW",
    "title": "业务特批服务补偿方案申请",
    "description": "系统已根据客户证明材料与订单状态完成初审测算，建议补偿 500.00 元。",
    "formFields": [
      {
        "fieldKey": "case_id",
        "label": "服务单号",
        "type": "text",
        "value": "ORD_10086",
        "editable": false,
        "required": true
      },
      {
        "fieldKey": "relief_type",
        "label": "申请类型",
        "type": "text",
        "value": "突发特殊情况特批补偿",
        "editable": false,
        "required": true
      },
      {
        "fieldKey": "relief_amount",
        "label": "拟补偿金额 (元)",
        "type": "number",
        "value": 500.00,
        "maxLimit": 650.00,
        "editable": true,
        "required": true
      },
      {
        "fieldKey": "apply_reason",
        "label": "提单说明",
        "type": "textarea",
        "value": "客户遭遇不可抗力突发事件，已上传相关凭证材料，申请酌情给予服务补偿与费用折让。",
        "editable": true,
        "required": true
      }
    ],
    "confirmButtonText": "确认并提交审批",
    "cancelButtonText": "放弃提单"
  }
}
```

#### (5) `recommend_questions` 推荐问题事件
```json
{
  "event": "recommend_questions",
  "data": {
    "questions": [
      "特批补偿需留存哪些凭证材料？",
      "审批通过后多长时间系统更新账单状态？",
      "如何申请该单据的加急审批报备？"
    ]
  }
}
```

#### (6) `done` 流终结事件
```json
{
  "event": "done",
  "data": {
    "status": "completed"
  }
}
```

---

## 5. 前端 EventSource 集成示例代码

```javascript
// 前端初始化 SSE 监听示例
const sessionId = "sess_" + Date.now();
const eventSource = new EventSource(`/api/v1/chat/connect?sessionId=${sessionId}`);

eventSource.addEventListener("thinking", (e) => {
  const data = JSON.parse(e.data);
  appendThinkingBubble(data.content);
});

eventSource.addEventListener("progress", (e) => {
  const data = JSON.parse(e.data);
  updateProgressBar(data.stage, data.description);
});

eventSource.addEventListener("message", (e) => {
  const data = JSON.parse(e.data);
  appendTypewriterText(data.content);
});

eventSource.addEventListener("bpm_confirm_card", (e) => {
  const cardData = JSON.parse(e.data);
  renderInteractiveFormCard(cardData, (confirmedValues) => {
    // 用户点击卡片提交工单
    fetch('/api/v1/bpm/submit-ticket', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        actionId: cardData.actionId,
        sessionId: sessionId,
        caseId: confirmedValues.case_id,
        bpmProcessKey: cardData.bpmProcessKey,
        formValues: confirmedValues
      })
    }).then(res => res.json()).then(res => {
      alert('提单成功，工单号：' + res.bpmInstanceId);
    });
  });
});

eventSource.addEventListener("recommend_questions", (e) => {
  const data = JSON.parse(e.data);
  renderQuestionChips(data.questions);
});

eventSource.addEventListener("done", () => {
  enableInput();
});
```
