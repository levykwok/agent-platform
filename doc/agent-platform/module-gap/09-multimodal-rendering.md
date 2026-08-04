# 多模态内容呈现

## 结论

AgentScope Core 有多模态消息结构，但平台前端还没有完整展示层。当前主要是文本、步骤、等待卡片和引用 chip。

本模块应该把 AgentScope 的 `ContentBlock` 和 `AgentEvent` 转成前端可展示的统一消息模型。

## 平台模块功能设计

本模块负责把 Agent 输出的多类型内容转换成前端可读、可交互、可追溯的展示组件。

核心功能：

```text
1. 文本渲染：支持普通文本和 Markdown 安全渲染。
2. 图片渲染：展示模型输出或工具返回的图片。
3. 文件渲染：展示 PDF、日志、报告、压缩包等文件卡片。
4. 表格渲染：展示结构化检查结果、告警列表、对比结果。
5. JSON 渲染：展示工具原始结果和结构化输出。
6. 工具结果展示：显示工具名称、输入摘要、输出摘要、耗时和状态。
7. 引用展示：展示文档、记忆、附件、工具结果来源。
8. 大内容查看：通过 payload_ref 按需加载大结果。
9. 错误展示：区分模型错误、工具错误、权限错误、业务错误。
10. 移动端适配：超长内容折叠、换行、横向滚动。
```

输入资源：

```text
AgentEvent
Msg ContentBlock
Tool result
Artifact metadata
Citation refs
```

输出资源：

```text
PlatformContentBlock
MessageRenderer 组件数据
附件预览数据
引用来源数据
```

## AgentScope 对应实现

关键概念：

```text
Msg
ContentBlock
TextBlock
DataBlock
URLSource
AgentEvent
TextBlockDeltaEvent
```

AgentScope 支持消息里带不同类型的内容块，而不是只有字符串。

## 当前平台已实现

前端：

```text
AgentWorkbench.vue
AgentWaitingCard.vue
```

当前支持：

```text
文本消息
执行过程 steps
waiting_user_input 卡片
引用 chip
会话详情抽屉
```

## 推荐调用链路

后端应该保留结构：

```text
Msg / AgentEvent
  -> PlatformMessageDTO / PlatformEventDTO
  -> SSE
  -> 前端按 block type 渲染
```

不要过早把所有内容压成一个字符串，否则图片、文件、表格、工具结果都会丢结构。

## 还缺什么

```text
图片展示
文件卡片
表格结果
JSON 结构化结果
工具结果详情
payload_ref 大内容查看
引用定位
附件预览
Markdown 安全渲染
```

## 推荐下一步

```text
P0: 定义 PlatformContentBlock DTO
P1: AgentRuntimeService.stream 使用 AgentScope events 映射多模态事件
P2: 前端 MessageRenderer 按 block.type 渲染
P3: 大内容走 payload_ref，不塞 SSE 大包
```

## 接口调用设计

### 后端消息 DTO 建议

```json
{
  "message_id": "msg_001",
  "role": "assistant",
  "blocks": [
    { "type": "text", "text": "分析结果如下" },
    { "type": "table", "columns": ["项", "值"], "rows": [] },
    { "type": "file", "file_id": "file_001", "filename": "report.pdf" }
  ],
  "citations": []
}
```

### AgentScope 映射

```text
Msg
  -> ContentBlock
  -> PlatformContentBlock
  -> 前端 MessageRenderer
```

文本：

```text
TextBlock -> {type: "text", text: "..."}
```

文件/图片：

```text
DataBlock / URLSource -> {type: "image" | "file", url/file_id/...}
```

### SSE 事件建议

```text
message_delta
content_block_start
content_block_delta
content_block_stop
tool_result_ref
final_result
```

### 前端组件建议

```text
MessageRenderer.vue
TextBlockRenderer.vue
ImageBlockRenderer.vue
FileBlockRenderer.vue
TableBlockRenderer.vue
JsonBlockRenderer.vue
ToolResultRenderer.vue
```

### 缺口落地

```text
P0: 定义 PlatformContentBlock 类型
P1: 前端拆 MessageRenderer
P2: payload_ref 大内容查看接口
P3: Markdown 安全渲染和 XSS 处理
```

## 当前平台接入基线

当前主运行链路：

```text
Frontend AgentWorkbench
  -> POST /agent-runs/run/stream
  -> AgentRunsCompatibilityController
  -> AgentRuntimeService.chat(...)
  -> AgentScopeHarnessFactory.create(...)
  -> HarnessAgent.call(...)
  -> ReActAgent + Middleware + Toolkit + Workspace + Memory + Session
```

当前平台配置统一入口：

```text
PlatformConfigStore
  -> models.yml
  -> providers.yml
  -> agents.yml
  -> tools.yml
  -> mcps.yml
  -> skills.yml
```

当前默认 workspace：

```text
workspace/
```
