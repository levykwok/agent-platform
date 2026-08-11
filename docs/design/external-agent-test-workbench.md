# 外部 Agent 接入测试工作台设计

状态：MVP 首版已实现，P1/P2 规划中
日期：2026-08-11
适用版本：Agent Platform 0.1.x

## 1. 背景

平台内部已经具备 Agent 工作台和交互问答入口，但外部系统调用 Agent 的链路仍主要依赖手写 curl、Postman 或业务方自行编写客户端验证。这样无法直观看到外部请求经过鉴权、Agent、Workflow、Tool、Skill 后产生的完整事件，也不利于定位流式协议、会话参数和错误响应问题。

平台当前已经提供稳定的外部 API，统一挂载在 `/api/v1`：

- `GET /api/v1/agents`：发现已发布 Agent；
- `POST /api/v1/agents/{agentId}/chat`：同步调用；
- `POST /api/v1/agents/{agentId}/chat/stream`：SSE 流式调用；
- 通过 `X-API-Key` 或 `Authorization: Bearer ...` 鉴权；
- 请求支持 `tenant_id`、`user_id`、`session_id` 和 `message`。

因此，下一步适合建设一个“外部 Agent 接入测试工作台”，以真实外部 API 协议模拟第三方系统调用，而不是再复制一套内部问答逻辑。

## 2. 目标

### 2.1 MVP 目标

1. 让开发、测试和实施人员无需编写代码即可调用一个已发布 Agent。
2. 同时支持同步响应和 SSE 流式响应。
3. 展示最终答案、原始响应、事件时间线、耗时和错误信息。
4. 支持固定 API 地址、API Key、Agent、租户、用户和会话参数。
5. 支持一键复制 curl、JavaScript、Python 调用示例。
6. 支持重放最近一次请求，方便复现问题。

### 2.2 非目标

- 不在工作台中编辑 Agent、Workflow、Tool 或 Skill。
- 不把外部 API Key 写入平台数据库。
- 不在 MVP 中实现任意第三方协议适配，例如 Webhook、WebSocket 或 OpenAI 兼容协议。
- 不将工作台变成完整的 API 管理、配额或计费系统。
- 不默认允许浏览器请求任意互联网地址，避免 CORS 和 SSRF 风险。

## 3. 当前 API 约束

外部 API 由环境变量控制，默认关闭：

```powershell
$env:AGENT_PLATFORM_EXTERNAL_API_ENABLED = "true"
$env:AGENT_PLATFORM_EXTERNAL_API_KEYS = "demo-key-change-me"
```

同步调用示例：

```http
POST /api/v1/agents/researcher/chat
Content-Type: application/json
X-API-Key: demo-key-change-me

{
  "tenant_id": "demo",
  "user_id": "caller-001",
  "session_id": "demo-session-001",
  "message": "请用中文介绍你的能力"
}
```

流式调用使用同一个请求体，将路径改为 `/chat/stream`，并设置：

```http
Accept: text/event-stream
```

SSE 事件保留 runtime 的事件类型、事件 ID 和 payload，外部调用方可以据此消费回答增量、Workflow 步骤和 Tool 活动。

## 4. 产品形态

### 4.1 页面入口

在平台主导航增加：

> 外部接入

页面路由建议为：

```text
/platform/live/external-test
```

前端组件建议命名为 `ExternalAgentWorkbench.vue`。

### 4.2 页面布局

页面采用三栏布局：

```text
┌──────────────────────────────────────────────────────────────┐
│ 外部接入测试工作台                         [同步] [流式]       │
├───────────────┬──────────────────────────┬───────────────────┤
│ 接入配置       │ 请求编辑                 │ 响应与事件         │
│ API 地址       │ Agent                    │ 最终回答           │
│ 鉴权方式       │ tenant_id               │ 状态 / 耗时         │
│ API Key        │ user_id                 │ 事件时间线         │
│                 │ session_id              │ 原始 SSE / JSON     │
│                 │ message                 │ 调用代码           │
│                 │ [发送请求]              │                   │
└───────────────┴──────────────────────────┴───────────────────┘
```

小屏幕下改为纵向排列，响应面板位于请求面板下方。

## 5. 交互设计

### 5.1 初始化

1. 页面默认使用当前平台地址和 `/api/v1`。
2. 页面加载 Agent 列表：`GET /api/v1/agents`。
3. 如果外部 API 未启用，显示明确错误：`external_api_disabled`。
4. API Key 输入框默认空白，内容使用密码样式。
5. API Key 只保存在页面内存，不写入 localStorage、SQLite 或会话历史。

### 5.2 请求配置

MVP 的结构化字段：

| 字段 | 必填 | 说明 |
|---|---:|---|
| API Base URL | 是 | 默认当前站点 `/api/v1` |
| 鉴权方式 | 是 | `X-API-Key` 或 Bearer |
| API Key | 是 | 仅内存保存，展示时脱敏 |
| Agent | 是 | 从已发布 Agent 列表选择，也允许手动输入 ID |
| tenant_id | 否 | 默认为 `demo` |
| user_id | 否 | 默认为 `external-user` |
| session_id | 否 | 支持自动生成或手动固定 |
| message | 是 | 外部系统发送的用户消息 |
| 调用模式 | 是 | 同步或流式 |

高级区域允许查看和编辑完整 JSON，但结构化字段仍应作为默认入口，避免用户一开始就面对原始协议。

### 5.3 发送与取消

- 点击“发送”后锁定请求编辑区域，展示请求开始时间。
- 同步请求显示加载状态，完成后展示答案和原始 JSON。
- 流式请求实时追加答案增量，并将每个 SSE 事件追加到时间线。
- 流式请求显示“停止”按钮；停止时主动取消浏览器请求并标记为“用户中止”。
- 发送失败时保留请求体和响应错误，允许一键重试。

### 5.4 响应面板

响应面板至少包含：

1. 状态：成功、失败、用户中止、超时；
2. HTTP 状态码；
3. 总耗时；
4. 首个响应事件耗时；
5. 最终答案；
6. SSE/JSON 原始内容；
7. 事件数量和事件类型统计；
8. 复制响应、下载响应和复制调用代码按钮。

事件时间线按到达顺序展示：

```text
接收请求 → Agent 开始 → Workflow 步骤 → Tool 调用 → Token 增量 → 完成
```

未知事件不能导致解析失败，应以“未知事件”展示原始 payload。

### 5.5 重放和历史

MVP 只保存当前页面最近 20 次请求，保存在内存中，不落库 API Key。

每条历史记录保存：

- 时间；
- Agent ID；
- 调用模式；
- tenant/user/session；
- 消息摘要；
- 成功状态；
- 总耗时；
- 是否包含错误。

历史记录可以重放，但重放前必须使用当前页面内存中的 API Key，不能从历史记录恢复密钥。

## 6. 请求与事件处理

### 6.1 请求构造

结构化表单统一转换成：

```json
{
  "tenant_id": "demo",
  "user_id": "caller-001",
  "session_id": "demo-session-001",
  "message": "请介绍当前 Agent 的能力"
}
```

调用 URL：

```text
同步：{baseUrl}/agents/{agentId}/chat
流式：{baseUrl}/agents/{agentId}/chat/stream
```

### 6.2 SSE 解析

解析器需要支持：

- `event:`；
- `id:`；
- 一个事件包含多个 `data:` 行；
- 空行作为事件结束；
- `[DONE]` 结束标记；
- 非 JSON data；
- 连接中断和半包数据。

解析后的事件统一为：

```ts
type ExternalEvent = {
  sequence: number
  event: string
  id: string
  data: unknown
  receivedAt: string
}
```

如果事件 payload 中存在文本增量，工作台将其追加到最终答案；其余事件保留在事件时间线和原始面板中。

## 7. 安全设计

1. 外部 API 必须显式启用，页面不能绕过后端开关。
2. API Key 不写入数据库、localStorage、URL、请求历史或错误日志。
3. 页面展示 API Key 时默认遮罩，仅允许临时显示。
4. 复制 curl 或代码时给出脱敏版本，并提供“显示真实 Key 后复制”的二次确认。
5. 默认只允许当前站点同源调用，避免浏览器 CORS 问题。
6. 如果未来增加服务端代理，必须限制目标地址白名单，禁止任意 URL 转发，防止 SSRF。
7. 请求超时、取消和页面离开时要中断连接。
8. 原始事件面板对疑似密钥字段进行脱敏，例如 `api_key`、`authorization`、`token`。
9. 外部 Agent 列表只展示已发布 Agent 的公开元数据，不展示 Prompt、工作区路径和内部绑定。

## 8. 错误状态

工作台需要把后端错误映射为可行动的提示：

| 错误 | 页面提示 | 建议动作 |
|---|---|---|
| `external_api_disabled` | 外部 API 未启用 | 检查服务端环境变量 |
| `external_api_not_configured` | 外部 API 未配置 Key | 配置 API Key 列表 |
| `external_api_unauthorized` | API Key 无效 | 检查 Key 或鉴权方式 |
| Agent 不存在 | Agent 未发布或不存在 | 刷新 Agent 列表 |
| 连接失败 | 无法连接目标地址 | 检查地址、端口和 CORS |
| SSE 解析失败 | 收到无法解析的事件 | 查看原始响应 |
| 超时 | 请求超过配置时限 | 重试或缩短输入 |

## 9. 实现拆分

### P0：可调用

1. 增加导航入口和页面路由。
2. 实现 API 地址、Key、Agent、租户、用户、会话和消息表单。
3. 实现 `/api/v1/agents` 加载和 Agent 选择。
4. 实现同步调用和结果展示。
5. 实现 API Key 脱敏与内存保存。

### P1：可调试

1. 实现 SSE 流式解析和事件时间线。
2. 增加请求/响应原始 JSON 面板。
3. 增加耗时、首事件、事件数量统计。
4. 增加停止、重试和最近请求重放。
5. 增加 curl、JavaScript、Python 代码生成。

### P2：可交付

1. 增加外部接入测试用例模板。
2. 增加断言：状态码、响应字段、答案包含关键字、最大耗时。
3. 增加测试报告导出。
4. 增加可选的服务端代理和目标地址白名单。
5. 增加契约测试和 Playwright 回归用例。

## 10. 验收标准

### 功能验收

- [ ] 外部 API 关闭时，页面能显示明确的禁用原因。
- [ ] 能加载并选择已发布 Agent。
- [ ] 能通过 API Key 发起同步调用并展示最终答案。
- [ ] 能发起流式调用并逐条展示 SSE 事件。
- [ ] 能区分同步成功、流式完成、用户中止、超时和失败。
- [ ] 能复制脱敏 curl、JavaScript 和 Python 示例。
- [ ] 能在不重新输入 Key 的情况下重放最近一次请求。

### 安全验收

- [ ] API Key 不出现在 URL、localStorage、请求历史和错误提示中。
- [ ] 页面刷新后 API Key 消失。
- [ ] 原始响应面板对 Authorization 等字段脱敏。
- [ ] 默认不会向任意第三方地址发起浏览器请求。

### 兼容性验收

- [ ] Chrome/Edge 下同步调用通过。
- [ ] Chrome/Edge 下流式调用通过。
- [ ] SSE 半包、多 data 行和未知事件不会导致页面崩溃。
- [ ] 页面在窄屏下仍可完成一次调用和查看错误。

## 11. 待确认事项

1. 工作台是否只允许调用当前平台地址，还是允许配置多个环境地址。
2. MVP 是否需要支持图片、附件和多模态消息。
3. Agent 列表是否只显示已发布 Agent，还是允许手动输入未发布 Agent ID。
4. 请求历史是否需要跨页面刷新保存；如果需要，是否允许只保存脱敏请求体。
5. 是否需要加入面向实施人员的固定测试模板和一键验收报告。

## 12. 参考资料

- [External Agent API](../../EXTERNAL_API.md)
- [交互问答与 Agent 工作台](../guide/conversation.md)
