# 多模态人机交互

## 结论

AgentScope 支持多模态输入结构和 HITL/等待类能力方向，但我们平台还没有完整附件上传、图片输入、等待恢复和审批闭环。

## 平台模块功能设计

本模块负责用户向 Agent 提交文本以外的输入，并支持运行过程中用户补充、确认、审批。

核心功能：

```text
1. 文本输入：普通对话消息。
2. 图片输入：上传截图、监盘画面、告警图片。
3. 文件输入：上传日志、报告、配置、表格、压缩包。
4. 附件解析：提取文本、元数据、预览图或 RAG 索引。
5. Artifact 管理：保存 artifact_id、文件名、类型、大小、来源。
6. 多模态消息构造：把附件转成 AgentScope ContentBlock。
7. 用户澄清：Agent 需要更多信息时暂停并询问用户。
8. 用户选择：Agent 给出候选项时用户选择。
9. 用户确认/审批：高风险工具或操作前要求确认。
10. 等待恢复：用户提交后恢复原 AgentRun。
```

输入资源：

```text
用户文本
上传文件
artifact_refs
waiting_user_input event
用户确认提交
```

输出资源：

```text
ContentBlock
ArtifactRef
RuntimeWaitingContext
恢复后的 AgentRun event
```

## AgentScope 对应实现

相关能力：

```text
ContentBlock / DataBlock
RuntimeContext
AgentEvent
waiting_user_input 类事件
Permission system
Plan Mode
Channel / AG-UI
```

## 当前平台已实现

前端已有：

```text
AgentWaitingCard
waiting_user_input 展示
```

后端当前有部分事件兼容，但不是完整等待恢复链路。

## 目标调用链路

附件/图片输入：

```text
前端上传文件
  -> AttachmentService 保存
  -> AgentRunRequest.artifact_refs
  -> Runtime 构造 Msg ContentBlock
  -> HarnessAgent.call(...)
```

等待用户输入：

```text
AgentScope event: waiting_user_input
  -> RuntimeWaitingContext 落库
  -> SSE 推给前端
  -> 用户提交选择/确认
  -> resume API
  -> Runtime 恢复执行
```

## 还缺什么

```text
附件上传接口
附件存储和解析
图片输入 DTO
文件/图片 ContentBlock 映射
等待输入持久化
resume API
审批确认
等待超时处理
```

## 推荐下一步

```text
P0: 定义 AttachmentRef 和 AgentRun artifact_refs
P1: 实现 waiting 状态表和提交接口
P2: 前端 composer 增加文件/图片上传
P3: 高风险工具调用接等待确认
```

## 接口调用设计

### 上传附件

```http
POST /platform/frontend/artifacts
Content-Type: multipart/form-data

file=@alarm.png
agent_id=monitoring_agent
session_id=sess_xxx
```

响应：

```json
{
  "artifact_id": "art_001",
  "filename": "alarm.png",
  "content_type": "image/png",
  "url": "/platform/frontend/artifacts/art_001/content"
}
```

### 带附件运行

```http
POST /agent-runs/run/stream
Content-Type: application/json

{
  "agent_id": "monitoring_agent",
  "session_id": "sess_xxx",
  "payload": {
    "query": "看下这张告警截图"
  },
  "artifacts": [
    { "artifact_id": "art_001", "type": "image" }
  ]
}
```

### 后端构造 AgentScope 消息

```text
artifact_refs
  -> ArtifactService 读取元数据/URL
  -> ContentBlock / DataBlock
  -> Msg
  -> HarnessAgent.call(...)
```

### 用户等待输入

等待事件：

```json
{
  "type": "waiting_user_input",
  "run_id": "run_001",
  "waiting": {
    "waiting_id": "wait_001",
    "waiting_type": "confirmation",
    "question": "是否执行重启服务操作？",
    "options": ["approve", "reject"]
  }
}
```

提交恢复：

```http
POST /agent-runs/{runId}/waiting/{waitingId}/submit
Content-Type: application/json

{
  "value": "approve",
  "comment": "确认执行"
}
```

### 缺口落地

```text
P0: ArtifactService + artifacts 表
P1: AgentRunRequest 支持 artifact_refs
P2: RuntimeWaitingService + waiting 表
P3: 前端 composer 支持上传和 waiting 提交
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
