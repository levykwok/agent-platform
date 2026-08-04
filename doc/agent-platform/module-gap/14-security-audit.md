# 安全设计与操作日志

## 结论

AgentScope 有沙箱、权限、Plan Mode、工具确认等运行时安全机制，但企业审计、密钥、审批、操作日志、脱敏策略必须由平台层实现。

## 平台模块功能设计

本模块负责安全策略、敏感信息保护、高风险操作控制和全链路审计。

核心功能：

```text
1. 操作审计：记录模型、Agent、MCP、Skill、记忆、权限等管理操作。
2. 运行审计：记录 AgentRun、工具调用、MCP 调用、等待确认、审批结果。
3. 凭据管理：API Key、token、cert 统一用 credential_ref 管理。
4. 脱敏显示：接口响应、日志、前端展示均不能泄露密钥。
5. 高风险识别：Tool/MCP/Skill 标记 risk_level、side_effect、confirm_required。
6. 调用确认：高风险操作触发 waiting_user_input。
7. 沙箱策略：需要执行命令或文件写入时配置 sandbox/filesystem 限制。
8. 日志分级：debug/info/warn/error 分级，避免输出过多或泄露敏感数据。
9. 异常追踪：trace_id 贯穿 API、runtime、model、tool。
10. 审计查询：按用户、资源、动作、时间、风险等级查询。
```

输入资源：

```text
管理操作请求
AgentRun event
Tool/MCP call event
Credential access
Permission decision
```

输出资源：

```text
AuditLog
SecurityEvent
Masked response
Approval waiting event
```

## AgentScope 对应能力

```text
Sandbox filesystem
Permission system
Plan Mode
Tool confirmation / HITL 基础
AgentTraceMiddleware
Filesystem isolation
Workspace logs
```

这些能力偏运行时控制，不等价于企业安全审计系统。

## 当前平台已实现

目前只有基础日志和部分风险字段，未形成安全审计闭环。

## 应该如何接入

运行前：

```text
权限校验
资源可见性过滤
密钥解析和脱敏
高风险工具标记
```

运行中：

```text
AgentScope permission / HITL
Tool call event
MCP call event
waiting_user_input approval
```

运行后：

```text
AuditLogService 记录操作
RunRecorder 记录工具调用
敏感字段脱敏
```

## 还缺什么

```text
AuditLogService
CredentialService
敏感信息脱敏
高风险工具确认
工具调用审批
操作日志页面
安全策略配置
审计查询接口
```

## 推荐下一步

```text
P0: 所有新增/编辑/删除配置操作记录 audit log
P1: API Key 改成 credential_ref
P2: MCP/Tool 增加 risk_level 和 confirm_required
P3: Runtime 接入 waiting_user_input 审批
```

## 接口调用设计

### 审计日志 DTO

```json
{
  "audit_id": "audit_001",
  "user_id": "platform_admin",
  "org_id": "platform",
  "action": "mcp.update",
  "resource_type": "mcp_server",
  "resource_id": "ops-mcp",
  "risk_level": "medium",
  "result": "success",
  "trace_id": "trace_001",
  "created_at": "2026-06-26T10:00:00Z"
}
```

### 审计调用点

管理操作：

```text
model create/update/delete
agent publish/offline
mcp create/check/sync/delete
skill publish/disable/delete
memory confirm/delete
```

运行操作：

```text
agent run
tool call
mcp call
waiting approval
high risk action
```

### Java 接口建议

```java
auditLogService.record(AuditEvent.builder()
    .principal(principal)
    .action("agent.publish")
    .resource("agent", agentId)
    .result("success")
    .build());
```

### 凭据接口建议

```java
String apiKey = credentialService.resolve("secret://deepseek/api-key");
String masked = credentialService.mask(apiKey);
```

### 高风险工具确认

```text
ToolSpec.riskLevel = high
ToolSpec.confirmRequired = true
  -> Runtime 触发 waiting_user_input
  -> 用户 approve/reject
  -> 审计记录确认结果
```

### 缺口落地

```text
P0: AuditLogService
P1: CredentialService
P2: 所有管理写操作加审计
P3: Tool/MCP 风险字段接 runtime waiting
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
