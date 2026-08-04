# 智能体管理

## 结论

AgentScope 能构建 `HarnessAgent`，但不负责企业平台里的 AgentSpec 管理、版本、发布、权限、审计和 UI。

平台应把 Agent 定义保存为自己的 `AgentDefinition`，运行时再转换成 `HarnessAgent.Builder`。

## 平台模块功能设计

本模块负责管理智能体定义，即平台侧的 AgentSpec / AgentDefinition。

核心功能：

```text
1. Agent 创建：配置 agent_id、名称、描述、业务域、模型、系统提示词、workspace。
2. Agent 编辑：维护 toolRefs、mcpRefs、skillRefs、memory 配置、运行参数。
3. Agent 版本管理：草稿、发布版本、历史版本、版本复制。
4. Agent 发布管理：draft -> published -> disabled/offline。
5. Agent 权限范围：控制哪些组织、用户、项目可以查看或运行。
6. Agent 工作区管理：初始化 AGENTS.md、knowledge、skills、subagents 等目录。
7. Agent 能力绑定：绑定模型、工具、MCP、Skill、子 Agent、编排策略。
8. Agent 测试运行：管理端直接用当前草稿配置发起测试。
9. Agent 缓存失效：发布新版本后让运行时使用新 AgentDefinition。
10. Agent 审计：记录创建、编辑、发布、下线、删除。
```

输入资源：

```text
AgentDefinition 表单
模型列表
工具/MCP/Skill 列表
编排配置
权限上下文
```

输出资源：

```text
可发布 AgentDefinition
运行时 HarnessAgent 构建参数
AgentSpec 快照
Agent 版本记录
```

## AgentScope 对应实现

AgentScope 对应能力：

```text
HarnessAgent.Builder
workspace/AGENTS.md
workspace/knowledge/
workspace/skills/
workspace/subagents/
workspace/tools.json
```

AgentScope 的设计是“workspace 即 agent 资产”。但企业平台还需要 DB/YAML 管理 AgentSpec。

## 当前平台已实现

```text
AgentDefinition
AgentDefinitionRegistry
YamlAgentDefinitionRegistry
AgentScopeHarnessFactory
PlatformConfigStore
```

落盘：

```text
workspace/agents.yml
```

当前 AgentDefinition 字段包括：

```text
agentId
version
name
model
systemPrompt
workspace
toolRefs
mcpRefs
skillRefs
orchestration
```

## 运行时转换链路

```java
HarnessAgent.builder()
    .name(definition.name())
    .sysPrompt(definition.systemPrompt())
    .model(definition.model())
    .workspace(definition.workspace())
    .toolkit(toolkit)
    .skillRepositories(skillRepositories)
    .build()
```

## 还缺什么

```text
Agent 新增/编辑 UI
Agent 版本管理
发布/下线/草稿状态
AgentSpec 快照，保证运行时使用固定版本
Agent 权限范围
Agent 工作区资产管理
Agent 变更审计
Agent 测试运行
```

## 推荐下一步

```text
P0: 做 Agent 管理页面，支持 systemPrompt/model/tool/mcp/skill/orchestration 配置
P1: AgentDefinition 增加 status、description、domain、owner、createdAt、updatedAt
P2: 增加 AgentSpec 快照，AgentRun 记录 agent_version 和 agent_spec
P3: DB 化时保留 AgentDefinitionRegistry 接口
```

## 接口调用设计

### Agent 列表

```http
GET /platform/frontend/agents?domain=platform
```

响应建议：

```json
{
  "items": [
    {
      "agent_id": "ops_agent",
      "version": "v1",
      "display_name": "运维智能体",
      "model": "deepseek-v4-flash",
      "status": "published",
      "tool_refs": [],
      "mcp_refs": [],
      "skill_refs": []
    }
  ]
}
```

### Agent 保存

```http
POST /platform/frontend/agents
Content-Type: application/json

{
  "agent_id": "ops_agent",
  "version": "v1",
  "name": "运维智能体",
  "model": "deepseek-v4-flash",
  "system_prompt": "你是运维智能体...",
  "workspace": "agents/ops_agent",
  "tool_refs": ["builtin:read_file"],
  "mcp_refs": ["ops-mcp"],
  "skill_refs": ["alarm-analysis"],
  "orchestration": {
    "mode": "SINGLE"
  }
}
```

### 发布接口建议

```http
POST /platform/frontend/agents/{agentId}/publish
Content-Type: application/json

{
  "version": "v1",
  "comment": "首次发布"
}
```

### 后端调用链路

```text
Agent 管理 UI
  -> AgentController / PlatformFrontendCompatibilityController
  -> AgentDefinitionRegistry.upsert(...)  当前待补
  -> PlatformConfigStore
  -> agents.yml
```

运行时读取：

```java
AgentDefinition definition = registry.findPublished(agentId).orElseThrow(...);
HarnessAgent agent = harnessFactory.create(definition);
```

### 运行时缓存

当前：

```java
String key = definition.agentId() + ":" + definition.version();
agentCache.computeIfAbsent(key, ignored -> harnessFactory.create(definition));
```

如果 AgentDefinition 被编辑，必须处理缓存失效：

```text
发布新 version -> 新 cache key
修改同 version -> 清理旧 cache 或禁止修改已发布版本
```

### DB 化边界

```text
AgentDefinitionRegistry
  -> findPublished(agentId)
  -> allPublished()
  -> upsertDraft(...)
  -> publish(...)
```

Controller 不直接关心 YAML/DB。

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

## 2026-07-09 更新：AgentDefinition 与 HarnessAgent 的边界

当前更准确的边界如下：

```text
AgentDefinition：平台业务对象，保存配置、绑定关系、编排、模型策略、状态、版本和权限。
HarnessAgent：AgentScope 运行时对象，负责执行模型调用、工具调用、MCP、Skill、memory、workspace 和事件流。
```

平台不应该把 HarnessAgent 当作可直接编辑的业务定义。正确链路是：

```text
UI/接口编辑 AgentDefinition
-> 保存到 SQLite
-> 发布/启用
-> AgentScopeHarnessFactory.create(definition)
-> HarnessAgent.Builder 注入 model/toolkit/mcp/skill/memory/stateStore/workspace
-> 运行时执行
```

能力绑定修正：

```text
1. toolRefs、mcpRefs、skillRefs 只描述“这个 agent 可以使用哪些能力”。
2. 真正注入发生在 runtime 创建 HarnessAgent 时。
3. MCP 选择服务器即可默认启用该服务器当前可见工具；精细到 tool 级别应作为高级配置。
4. 平台工具和 MCP 工具不应在 UI 上混成一个层级，应分组展示。
5. Skill 绑定应在 Agent 配置里操作，Skill 页面只展示反向绑定关系。
```

当前实现注意点：

```text
1. AgentStateStore 已显式指向 workspace/agent-state/{agentId}。
2. AgentScope workspace 运行文件应按用户隔离路径写入，例如 workspace/{agentId}/{org}_{user}/MEMORY.md。
3. Agent 缓存发布后要 evict，否则旧配置可能继续生效。
4. 默认模型不应自动勾选成 mock；没有显式选择时应走平台默认 slot。
```

缺口：

```text
1. 草稿/发布版本链路还不完整。
2. 多租户/多用户的 AgentDefinition 可见性需要补权限模型。
3. Agent 工作台和交互问答需要区分：工作台看执行过程，交互问答面向普通对话。
4. Agent 运行时的能力清单、实际工具调用、Skill 加载、memory 写入应持续展示在事件流里。
```

## 2026-07-09 补充：管理态 / 运行态边界（不把 HarnessAgent 当成业务定义）

统一口径如下：

```text
管理态：平台侧保存 AgentDefinition（业务规则、绑定关系、版本、权限、发布状态）。
运行态：AgentScopeHarnessFactory 把 AgentDefinition 映射为 HarnessAgent.Builder 并装配执行上下文。
```

平台应持久化：

```text
- AgentDefinition
- 版本元数据
- 发布状态和权限配置
- 与 Agent 有关的 policy、配置面参数
```

以下属性属于 runtime 构造属性，不建议直接作为平台主数据：

```text
model / toolkit / skillRepositories / memory / workspace / stateStore
```

这些属性用于每次创建 `HarnessAgent` 时注入，并可随执行环境变化（模型路由、MCP 可见性、skill 存储策略、stateStore 形态）发生变化。

该边界不否定 HarnessAgent 能力，只是避免把“运行时对象”混入“平台治理对象”。
