# 息流（FlowUs）MCP 客户端集成指南

## 1. 概述

本工程与智能体已接入息流（FlowUs）官方 Model Context Protocol (MCP) 服务。智能体可直接通过 MCP 标准工具与息流工作区进行交互（如读取知识库、同步文档、检索 SOP 及管理工作空间）。

---

## 2. 协议与端点规范

- **MCP Server 端点**：`https://mcp.flowus.cn/message`
- **传输协议**：HTTP Streamable / SSE 远程传输
- **鉴权机制**：MCP 原生 OAuth 2.0 / PKCE（RFC 8414 & RFC 9207 动态元数据发现与动态客户端注册）
- **安全约束**：
  - 严禁在 URL 中硬编码或附加 `token`、`api_key` 等静态凭证。
  - 不使用开发者中心静态 Token，所有凭证由 Antigravity 客户端本地安全密钥环（Windows Credential Manager / Keyring）加密存储与自动刷新。

---

## 3. 客户端配置结构

配置文件路径：`~/.gemini/config/mcp_config.json`

```json
{
  "mcpServers": {
    "flowus": {
      "serverUrl": "https://mcp.flowus.cn/message"
    }
  }
}
```

---

## 4. 原生 MCP OAuth 授权步骤

由于遵循原生 OAuth 交互安全协议，授权过程在客户端本地与浏览器中完成：

1. 打开 Antigravity 客户端界面，进入 **Settings（设置）** -> **Installed MCP Servers（已安装 MCP）**。
2. 在列表项 `flowus` 右侧点击 **Authenticate** 按钮。
3. 系统将调用默认浏览器打开 FlowUs 授权页面，登录并确认工作区访问授权。
4. 授权成功后，浏览器自动跳转至 `https://antigravity.google/oauth-callback`，页面显示生成的授权码（Authorization Code）。
5. 点击页面上的 **Copy to Clipboard** 复制授权码。
6. 返回 Antigravity 客户端，在 `flowus` 下方出现的 **Paste auth code** 输入框中粘贴授权码并点击 **Submit**。
7. 客户端底层 Language Server 会自动调用 Token 端点换取 Access Token 与 Refresh Token，并在本地安全持久化。

---

## 5. 校验与连接状态

- 授权提交完成后，客户端会自动触发 `initialize` 与 `tools/list`。
- 状态变为 `MCP_SERVER_STATUS_READY` 即代表成功注册。
- 智能体即可在开发与交互过程中直接调用 `flowus` 下的各项工作区工具。
