# 对外 Agent API

平台提供独立的、版本化的外部调用接口，供业务系统、门户或自动化任务调用已发布 Agent。

## 接口地址

接口统一使用 `/api/v1` 前缀：

| 能力 | 方法 | 地址 |
| --- | --- | --- |
| 健康检查 | `GET` | `/api/v1/health` |
| Agent 目录 | `GET` | `/api/v1/agents` |
| Agent 详情 | `GET` | `/api/v1/agents/{agentId}` |
| 同步调用 | `POST` | `/api/v1/agents/{agentId}/chat` |
| 流式调用 | `POST` | `/api/v1/agents/{agentId}/chat/stream` |

## 鉴权配置

外部 API 默认关闭。服务启动时配置环境变量：

```powershell
$env:AGENT_PLATFORM_EXTERNAL_API_ENABLED = "true"
$env:AGENT_PLATFORM_EXTERNAL_API_KEYS = "demo-key-change-me"
```

多个 Key 使用英文逗号分隔。调用方可以使用以下任一种请求头：

```http
X-API-Key: demo-key-change-me
```

或：

```http
Authorization: Bearer demo-key-change-me
```

## 同步调用示例

```http
POST /api/v1/agents/main/chat
Content-Type: application/json
X-API-Key: demo-key-change-me

{
  "tenant_id": "demo",
  "user_id": "caller-001",
  "session_id": "demo-session-001",
  "message": "请用中文介绍当前平台的能力"
}
```

请求也兼容 `tenantId`、`userId`、`sessionId` 的驼峰写法。返回结果包含请求 ID、实际 Agent、会话 ID 和文本答案。

## 流式调用

将地址改为 `/chat/stream`，并设置：

```http
Accept: text/event-stream
```

返回 Server-Sent Events。每个事件包含事件 ID、事件类型和运行 payload，可用于展示模型调用、工具调用、Skill 执行和工作流步骤。

## 安全边界

外部目录只返回已发布且启用的 Agent，以及名称、版本、编排类型和调用能力；不会返回系统 Prompt、工作目录、内部工具绑定或 Skill 内容。平台内部管理接口仍使用 `/platform/frontend`，不作为外部 API 契约。
