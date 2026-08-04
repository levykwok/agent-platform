# 用户和权限管理

## 结论

AgentScope 有 `RuntimeContext.userId` 和 `IsolationScope`，只能解决运行时身份和数据隔离的一部分。企业用户、组织、角色、资源权限必须由平台自己实现。

## 平台模块功能设计

本模块负责企业用户、组织、项目、角色和资源权限，保证 Agent、模型、工具、MCP、Skill、会话、记忆都按授权访问。

核心功能：

```text
1. 用户身份：解析登录用户、组织、项目、角色。
2. 资源权限：控制 Agent、模型、MCP、Tool、Skill、Memory、Session 可见性。
3. 操作权限：控制创建、编辑、发布、删除、运行、审批。
4. 运行权限：运行前校验用户是否可执行指定 Agent。
5. 工具权限：运行时过滤未授权 Tool/MCP/Skill。
6. 数据隔离：构造 RuntimeContext.userId，配合 AgentScope IsolationScope。
7. 跨项目控制：禁止或限制跨项目会话、记忆、附件复用。
8. 管理端权限：控制配置页面按钮和接口。
9. 审计关联：所有审计记录带 user/org/project。
10. 后续单点登录：预留 token/session/OAuth/OIDC 接入。
```

输入资源：

```text
认证 token
用户组织角色
资源授权关系
请求资源 ID
```

输出资源：

```text
PlatformPrincipal
PermissionDecision
过滤后的资源列表
RuntimeContext identity
```

## AgentScope 对应能力

```text
RuntimeContext.userId
RuntimeContext.sessionId
RuntimeContext extras
IsolationScope.USER / SESSION / AGENT / GLOBAL
Permission system / HITL 基础
```

默认 workspace 数据隔离：

```text
IsolationScope.USER
```

当前平台 userKey：

```text
tenantId + ':' + userId
```

## 当前平台已实现

当前主要依赖请求头：

```text
x-user-id
x-org-id
```

然后运行时构造：

```java
RuntimeContext.builder()
    .userId(tenant + ":" + user)
    .sessionId(sessionId)
    .put("tenant_id", tenant)
    .build()
```

## 平台应该负责什么

```text
登录认证
组织/租户
项目空间
角色
资源授权
Agent 可见性
Tool/MCP/Skill 可见性
模型可用权限
高风险操作审批权限
```

## 还缺什么

```text
用户表
组织表
角色表
权限表
前端登录态
权限过滤器
资源级授权
操作级授权
审计关联 user/org/project
```

## 推荐下一步

```text
P0: 定义 PlatformPrincipal 和 PermissionService
P1: Controller 不直接信任 header，统一从 Principal 取身份
P2: Agent/Tool/MCP/Skill 查询接口接权限过滤
P3: Runtime 装配前做资源可见性过滤
```

## 接口调用设计

### 统一 Principal

```java
public record PlatformPrincipal(
    String tenantId,
    String userId,
    String orgId,
    String projectId,
    Set<String> roles) {}
```

Controller 不应该散落读取：

```text
x-user-id
x-org-id
```

应该统一由认证层生成 `PlatformPrincipal`。

### 权限检查接口

```java
permissionService.check(principal, "agent:run", agentId);
permissionService.check(principal, "model:use", modelId);
permissionService.check(principal, "tool:call", toolId);
permissionService.filterAgents(principal, agents);
```

### 前端资源过滤

```http
GET /platform/frontend/agents
Authorization: Bearer ...
```

返回只包含当前用户可见 Agent。

### RuntimeContext 构造

```java
RuntimeContext.builder()
    .userId(principal.tenantId() + ":" + principal.userId())
    .sessionId(sessionId)
    .put("tenant_id", principal.tenantId())
    .put("org_id", principal.orgId())
    .put("project_id", principal.projectId())
    .build();
```

### 缺口落地

```text
P0: PlatformPrincipal
P1: PermissionService 接口
P2: Agent/Model/MCP/Skill 列表过滤
P3: Runtime 装配前权限校验
P4: 用户/角色/授权表
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
