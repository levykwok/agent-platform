# 详细设计模块与 AgentScope 实现对照

## 说明

本文对照 `RT20263007-新型系统软件平台智能化提升-详细设计（系统软件）.docx` 中的模块清单，说明每个模块在 AgentScope Java / Harness 中已有的实现、当前平台已接入情况、缺口，以及推荐调用方式。

模块清单来自详细设计的“模块列表”：

```text
模型统一接入
对外统一API网关
智能体管理
智能体编排
MCP Server管理
Skills 管理
会话管理
记忆管理
多模态内容呈现
多模态人机交互
运行时
AI模型监控工具
用户和权限管理
安全设计与操作日志
运维智能体
监盘智能体
```

## 总体判断

AgentScope 更偏“智能体运行时框架”，不是完整企业智能体平台。

它已经覆盖：

```text
Agent 执行循环
模型调用抽象
工具调用
MCP 运行时接入
Skill 加载
Workspace
会话上下文
长期记忆
子 Agent
流式事件
沙箱 / 文件系统
Plan Mode
```

它没有完整覆盖：

```text
平台控制台
模型供应商管理
租户 / 用户 / 权限
审计日志
审批工作流
配置持久化管理
数据库表结构
运行记录管理
模型监控统计
业务智能体资产管理
```

所以我们的平台分层应该是：

```text
平台管理层：我们做
平台持久化层：我们做，当前 YAML，后续 DB
运行时适配层：我们做薄封装
Agent 执行层：优先调用 AgentScope Harness
```

## 当前平台接入基线

当前已有关键代码：

```text
src/main/java/com/company/platform/runtime/AgentRuntimeService.java
src/main/java/com/company/platform/adapter/agentscope/AgentScopeHarnessFactory.java
src/main/java/com/company/platform/adapter/agentscope/AgentCapabilityAssembler.java
src/main/java/com/company/platform/control/PlatformConfigStore.java
src/main/java/com/company/platform/web/AgentRunsCompatibilityController.java
```

当前调用主链路：

```text
Frontend AgentWorkbench
  -> POST /agent-runs/run/stream
  -> AgentRunsCompatibilityController
  -> AgentRuntimeService.chat(...)
  -> AgentScopeHarnessFactory.create(...)
  -> HarnessAgent.call(...)
  -> ReActAgent + Middleware + Toolkit + Workspace + Memory + Session
```

## 1. 模型统一接入

### 详细设计目标

统一管理模型供应商、模型配置、协议类型、API Key、Base URL、调用参数、模型测试和后续监控。

### AgentScope 已有能力

AgentScope Core 提供：

```text
Model
ModelRegistry
OpenAIChatModel
GenerateOptions
ExecutionConfig
Formatter
```

它能解决“运行时怎么调用模型”。

### AgentScope 不负责的部分

```text
模型供应商管理页面
API Key 配置页面
自定义 provider 元数据
模型测试接口
模型可用性状态
模型价格、成本、调用统计
模型权限和租户隔离
```

### 当前平台已做

```text
ModelConfigRegistry
ModelProviderRegistry
YamlModelConfigRegistry
YamlModelProviderRegistry
PlatformConfigStore
ModelsAdmin.vue
```

模型配置当前保存到：

```text
workspace/models.yml
workspace/providers.yml
```

### 推荐调用方式

平台管理侧：

```text
UI -> PlatformFrontendCompatibilityController -> ModelConfigRegistry / ModelProviderRegistry
```

运行时：

```text
AgentDefinition.model()
  -> HarnessAgent.builder().model(definition.model())
  -> AgentScope ModelRegistry.resolve(...)
```

### 缺口

```text
模型调用监控
模型调用日志
模型成本统计
模型鉴权脱敏和密钥托管
模型 fallback / routing 策略
embedding 模型统一管理
```

## 2. 对外统一 API 网关

### 详细设计目标

对外提供统一 AgentRun、会话、模型、工具、MCP、Skill 管理接口，并适配不同前端或第三方调用方。

### AgentScope 已有能力

AgentScope 有运行时 Java API：

```java
HarnessAgent.call(...)
HarnessAgent.streamEvents(...)
```

也有生态协议扩展文档：

```text
AG-UI
A2A
Agent Protocol
Chat Completions Web
Channel
```

### AgentScope 不负责的部分

```text
我们的 REST API
认证鉴权
租户上下文解析
统一错误码
接口审计
前端兼容层
```

### 当前平台已做

```text
AgentRunsCompatibilityController
PlatformFrontendCompatibilityController
```

### 推荐调用方式

对话入口：

```text
POST /agent-runs/run/stream
```

内部转：

```java
runtime.chat(agentId, new ChatRequest(orgId, userId, sessionId, query))
```

### 缺口

```text
正式 API DTO
统一错误码
OpenAPI 文档
鉴权过滤器
请求限流
接口审计
非流式 / 异步运行接口
取消运行接口
恢复等待输入接口
```

## 3. 智能体管理

### 详细设计目标

管理 AgentSpec，包括名称、版本、系统提示词、模型、工具、MCP、Skill、工作区、发布状态等。

### AgentScope 已有能力

AgentScope 的对应概念是：

```text
HarnessAgent.Builder
workspace/AGENTS.md
workspace/tools.json
workspace/skills/
workspace/subagents/
```

它能构造 agent，但不提供平台级 AgentSpec 管理。

### 当前平台已做

```text
AgentDefinition
AgentDefinitionRegistry
YamlAgentDefinitionRegistry
AgentScopeHarnessFactory
```

配置当前保存到：

```text
workspace/agents.yml
```

### 推荐调用方式

管理侧：

```text
AgentDefinitionRegistry 查询已发布 AgentDefinition
```

运行侧：

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

### 缺口

```text
Agent 新增/编辑页面
Agent 版本管理
发布/下线状态
AgentSpec 快照
Agent 权限范围
Agent 变更审计
Agent 工作区资产管理
```

## 4. 智能体编排

### 详细设计目标

支持单 Agent、路由、多 Agent、工作流、Supervisor、子 Agent 等编排方式。

### AgentScope 已有能力

AgentScope Harness 支持：

```text
SubagentDeclaration
workspace/subagents/*.md
DynamicSubagentsMiddleware
SubagentsMiddleware
agent_spawn
后台子任务
流式事件转发
```

AgentScope 文档也有：

```text
routing
workflow
supervisor
subagent
handoff
```

### 当前平台已做

```text
OrchestrationPolicy
OrchestrationMode
SubagentBinding
RouteRule
WorkflowStep
AgentRuntimeService.runWorkflow(...)
AgentRuntimeService.route(...)
AgentScopeHarnessFactory.subagent(...)
```

### 推荐调用方式

静态子 Agent：

```java
builder.subagent(SubagentDeclaration.builder()
    .name(binding.bindingId())
    .description(binding.description())
    .workspace(target.workspace())
    .model(target.model())
    .tools(binding.toolRefs())
    .build())
```

平台路由：

```java
AgentRuntimeService.route(definition, request)
```

平台工作流：

```java
for (WorkflowStep step : definition.orchestration().workflow()) {
    runSingle(stepAgent, stepRequest)
}
```

### 缺口

```text
可视化编排页面
编排 DSL 完善
运行时编排事件追踪
节点级工具/Skill/MCP 可见性
失败重试 / 分支 / 并行
Human-in-the-loop 编排节点
编排版本快照
```

## 5. MCP Server 管理

### 详细设计目标

MCP Server 注册、连接检测、工具发现、同步、元数据维护、权限绑定、运行时加载、审计。

### AgentScope 已有能力

AgentScope Harness 提供：

```text
McpServerConfig
McpServerRegistrar
Toolkit
workspace/tools.json
MCP 工具注册到 Toolkit
```

它解决“运行时如何把 MCP 工具挂进 agent”。

### 当前平台已做

```text
McpRegistry
YamlMcpRegistry
McpSpec
AgentCapabilityAssembler.applyMcps(...)
```

配置当前保存到：

```text
workspace/mcps.yml
```

### 推荐调用方式

管理侧：

```text
McpRegistry.upsert(...)
McpRegistry.all()
```

运行侧：

```java
capabilityAssembler.applyToolsAndMcps(toolkit, definition)
```

再由 AgentScope：

```text
McpServerRegistrar -> Toolkit
```

### 缺口

```text
MCP Server 连接检测
MCP Tool 自动发现与同步
MCP Tool schema 保存
MCP Tool 风险等级
confirm_required
调用前审批
健康检测记录
同步日志
MCP 调用审计
密钥 credential_ref
```

## 6. Skills 管理

### 详细设计目标

Skill 注册、解析、版本、发布、测试、依赖、Agent 绑定、运行时加载。

### AgentScope 已有能力

AgentScope Harness 支持：

```text
AgentSkillRepository
ClasspathSkillRepository
FileSystemSkillRepository
GitSkillRepository
Nacos skill repository
MySQL/PostgreSQL skill repository
load_skill_through_path
Skill runtime
workspace/skills/
```

它解决“运行时如何加载 Skill”。

### 当前平台已做

```text
SkillRegistry
YamlSkillRegistry
SkillSpec
AgentCapabilityAssembler.buildSkillRepositories(...)
```

配置当前保存到：

```text
workspace/skills.yml
```

### 推荐调用方式

管理侧：

```text
SkillRegistry.upsert(...)
SkillRegistry.all()
```

运行侧：

```java
List<AgentSkillRepository> repos =
    capabilityAssembler.buildSkillRepositories(definition);

HarnessAgent.builder()
    .skillRepositories(repos)
```

### 缺口

```text
Skill 版本表
Skill 测试流程
Skill 发布/停用/废弃状态
Skill 依赖校验
Skill 风险等级
Skill 资源包管理
Skill 调用记录关联
Skill 审批
```

## 7. 会话管理

### 详细设计目标

会话创建、消息保存、上下文恢复、摘要压缩、运行记录关联、附件复用、权限隔离。

### AgentScope 已有能力

AgentScope Harness / Core 提供：

```text
RuntimeContext(userId, sessionId)
AgentState
AgentStateStore
SessionTree
WorkspaceManager
sessions.json
{sessionId}.jsonl
{sessionId}.log.jsonl
SessionSearchTool
CompactionMiddleware
```

### 当前平台已做

```text
PlatformWorkspaceSessionStore
PlatformFrontendCompatibilityController /chat/sessions
AgentRuntimeService.runtimeContext(...)
AgentWorkbench.vue
```

文档：

```text
doc/company-platform/agentscope-session.md
```

### 推荐调用方式

运行时必须走：

```java
HarnessAgent.call(message, runtimeContext)
HarnessAgent.streamEvents(message, runtimeContext)
```

不要手写 session jsonl/log。

管理侧读取：

```java
WorkspaceManager.readManagedWorkspaceFileUtf8(runtimeContext, relativePath)
```

### 缺口

```text
PlatformSessionService
正式会话 DB 表
消息级权限
会话摘要管理
AgentRun 和会话消息强关联
附件和 RAG 关联
会话归档/删除/恢复
会话详情 panel 使用正确 RuntimeContext 读取 memory/session
```

## 8. 记忆管理

### 详细设计目标

长期记忆提取、确认、治理、检索、注入、冲突合并、可信度、向量索引。

### AgentScope 已有能力

AgentScope Harness 提供：

```text
MemorySaveTool
MemorySearchTool
MemoryGetTool
MemoryFlushMiddleware
MemoryConsolidator
MemoryMaintenanceMiddleware
MemoryConfig
MEMORY.md
memory/YYYY-MM-DD.md
```

默认自动启用：

```text
每次 call 后 MemoryFlushMiddleware 尝试抽取长期记忆
memory_save 可手动保存
MemoryConsolidator 周期合并到 MEMORY.md
```

### 当前平台已做

文档：

```text
doc/company-platform/agentscope-memory.md
```

运行时通过 Harness 默认 memory 链路间接接入。

### 推荐调用方式

运行时：

```java
HarnessAgent.builder()
    .memory(MemoryConfig.builder()
        .flushPrompt(...)
        .flushTrigger(...)
        .build())
```

读取管理侧：

```java
workspaceManager.readManagedWorkspaceFileUtf8(runtimeContext, "MEMORY.md")
workspaceManager.readManagedWorkspaceFileUtf8(runtimeContext, "memory/YYYY-MM-DD.md")
```

### 缺口

```text
PlatformMemoryService
记忆列表/详情/新增/确认/停用接口
记忆确认状态 pending_confirm / active / rejected
可信度评分
冲突检测与合并
向量索引
按 scope 查询 user/project/org/agent/global
记忆管理页面
AgentDefinition 中暴露 memory 配置
```

## 9. 多模态内容呈现

### 详细设计目标

前端展示文本、图片、文件、结构化结果、工具结果、引用来源等。

### AgentScope 已有能力

AgentScope Core 提供：

```text
ContentBlock
TextBlock
DataBlock
URLSource
File / multimodal message support
AgentEvent
```

### 当前平台已做

当前 `AgentWorkbench.vue` 主要展示：

```text
文本消息
执行步骤
等待用户输入卡片
引用 chip
会话详情抽屉
```

### 推荐调用方式

运行时保留 AgentScope 的消息结构：

```text
Msg -> ContentBlock -> 前端展示 DTO
AgentEvent -> SSE -> 前端事件流
```

### 缺口

```text
图片展示
文件卡片
表格/JSON 结构化结果展示
工具结果详情
大内容 payload_ref 展示
引用定位
附件预览
```

## 10. 多模态人机交互

### 详细设计目标

支持用户上传图片/文件/附件，多轮补充，用户确认、选择、审批、澄清。

### AgentScope 已有能力

AgentScope Core 支持多模态消息块。

AgentScope Harness / ecosystem 支持：

```text
HITL
Plan Mode
权限系统
waiting_user_input 类事件
AG-UI / Channel 集成
```

### 当前平台已做

当前前端已有：

```text
AgentWaitingCard
waiting_user_input 事件展示
```

但后端当前还没有完整等待恢复链路。

### 推荐调用方式

前端：

```text
SSE 接收 waiting_user_input
提交用户选择/确认
```

后端：

```text
RuntimeWaitingContext
waiting 状态持久化
resume API
```

### 缺口

```text
附件上传
图片输入
文件解析
等待输入持久化
恢复执行 API
审批流
权限确认
多模态消息 DTO
```

## 11. 运行时

### 详细设计目标

AgentHandle 获取、上下文构建、资源装配、统一执行、流式输出、等待恢复、事件记录、异常降级。

### AgentScope 已有能力

AgentScope 提供核心运行能力：

```text
HarnessAgent.call
HarnessAgent.streamEvents
ReActAgent
RuntimeContext
AgentState
Middleware
Toolkit
AgentEvent
ExecutionConfig
GenerateOptions
```

### 当前平台已做

```text
AgentRuntime
AgentRuntimeService
ChatRequest
ChatResponse
AgentEventEnvelope
AgentRunsCompatibilityController
```

### 推荐调用方式

同步：

```java
agent(definition).call(request.message(), context)
```

流式：

```java
agent(definition).streamEvents(request.message(), context)
```

缓存：

```java
agentCache.computeIfAbsent(agentId + ":" + version, ...)
```

### 缺口

```text
真正使用 streamEvents 输出 token/event
AgentRun DB 表
AgentRunEvent DB 表
AgentToolCall DB 表
取消运行
超时控制
等待输入恢复
错误码映射
运行状态查询
事件重放
异步运行
```

## 12. AI 模型监控工具

### 详细设计目标

监控模型调用量、耗时、错误、成本、token、可用性、告警。

### AgentScope 已有能力

AgentScope 有模型调用抽象和事件/中间件机制，可以作为采集点。

可用挂点：

```text
Model wrapper
Middleware
AgentEvent
ExecutionConfig timeout
```

### AgentScope 不负责的部分

```text
监控指标存储
成本计算
告警策略
可视化大盘
模型 SLA
供应商状态
```

### 当前平台已做

仅有模型测试和基础配置管理，还没有监控工具。

### 推荐调用方式

实现一个平台模型代理：

```text
PlatformObservedModel implements Model
```

或在 ModelFactory 创建模型时包装：

```text
Model raw = ModelRegistry.resolve(modelId)
Model observed = new ObservedModel(raw, metricsRecorder)
```

### 缺口

```text
ModelCallRecord
token 统计
latency 统计
错误率统计
成本配置
监控 UI
告警
```

## 13. 用户和权限管理

### 详细设计目标

用户、组织、项目、角色、资源权限、操作权限。

### AgentScope 已有能力

AgentScope 有：

```text
RuntimeContext.userId
RuntimeContext extras
IsolationScope
权限系统 / HITL 文档
工具调用确认机制基础
```

它只提供运行时身份和隔离，不提供企业权限系统。

### 当前平台已做

当前主要通过 header：

```text
x-user-id
x-org-id
```

传给：

```java
RuntimeContext.userId = tenant + ":" + user
```

### 推荐调用方式

平台必须在进入 AgentScope 前做权限过滤：

```text
查询用户权限
过滤 Agent 可见性
过滤 Tool/MCP/Skill 可见性
构造 AgentDefinition
构造 RuntimeContext
```

### 缺口

```text
用户表
组织表
角色表
资源授权
Agent 权限
Tool/MCP/Skill 权限
操作审批权限
前端登录态
```

## 14. 安全设计与操作日志

### 详细设计目标

敏感操作审计、工具风险控制、密钥脱敏、危险操作确认、异常日志。

### AgentScope 已有能力

AgentScope Harness 有：

```text
Permission system
Plan Mode
Sandbox
Filesystem isolation
Tool confirmation 基础
AgentTraceMiddleware
Workspace logs
```

### 当前平台已做

当前仅有基础日志和部分配置字段，没有完整安全审计。

### 推荐调用方式

运行前：

```text
平台权限校验
资源可见性过滤
高风险工具标记
```

运行中：

```text
AgentScope Permission / HITL
Tool call event 记录
```

运行后：

```text
审计日志落库
敏感字段脱敏
```

### 缺口

```text
AuditLogService
敏感信息脱敏
凭据服务
高风险工具确认
工具调用审批
操作日志页面
安全策略配置
```

## 15. 运维智能体

### 详细设计目标

面向运维场景的业务智能体，结合工具、MCP、Skill、记忆、会话、RAG。

### AgentScope 已有能力

AgentScope 能承载业务 agent：

```text
HarnessAgent
AGENTS.md
knowledge/
skills/
tools
MCP
memory
subagents
```

### 当前平台已做

当前只是有通用 AgentDefinition 和运行时，没有完整业务资产。

### 推荐调用方式

定义一个运维 Agent：

```yaml
agentId: ops_agent
model: deepseek-v4-flash
systemPrompt: ...
toolRefs: [...]
mcpRefs: [...]
skillRefs: [...]
orchestration: ...
```

运行：

```text
POST /agent-runs/run/stream
agent_id=ops_agent
```

### 缺口

```text
运维领域系统提示词
运维 Skill 包
运维 MCP 工具
运维知识库/RAG
运维风险控制
业务测试集
```

## 16. 监盘智能体

### 详细设计目标

面向监盘、告警、状态分析、联动建议的业务智能体。

### AgentScope 已有能力

同运维智能体，AgentScope 提供运行底座。

### 当前平台已做

没有独立监盘智能体资产。

### 推荐调用方式

定义一个监盘 Agent：

```yaml
agentId: monitoring_agent
model: deepseek-v4-flash
systemPrompt: ...
toolRefs: [...]
mcpRefs: [...]
skillRefs: [...]
```

### 缺口

```text
监盘业务 prompt
告警分析 Skill
遥测/告警 MCP 工具
告警上下文 RAG
业务权限
结果展示组件
```

## 横向缺口汇总

### P0：先补正确接入链路

```text
会话详情读取 RuntimeContext 修正
真正使用 streamEvents 输出 AgentScope 事件
PlatformMemoryService
PlatformSessionService
Agent 管理页面
AgentDefinition memory 配置
```

### P1：补管理闭环

```text
MCP 连接检测与工具同步
Skill 版本/测试/发布
Agent 版本/发布/快照
运行记录 AgentRun/AgentRunEvent/AgentToolCall
等待用户输入恢复
```

### P2：补企业能力

```text
用户权限
审计日志
凭据管理
高风险工具审批
模型监控
配置 DB 化
```

### P3：补业务智能体

```text
运维智能体资产
监盘智能体资产
业务 MCP
业务 Skill
业务知识库
业务测试集
```

## 推荐平台调用边界

### 不要自己重写的部分

```text
Agent 执行循环
AgentState 恢复
SessionTree 写入
MemoryFlush
MemoryConsolidation
Skill runtime loading
MCP tool runtime registration
Subagent runtime
Compaction
```

这些优先走 AgentScope。

### 必须自己做的平台部分

```text
配置管理
DB 存储
权限
审计
UI
模型供应商管理
业务资产管理
运行记录查询
企业级监控
```

### 关键原则

运行时只通过：

```java
HarnessAgent.call(...)
HarnessAgent.streamEvents(...)
```

管理侧通过平台 service 读写：

```text
AgentDefinitionRegistry
ModelConfigRegistry
ModelProviderRegistry
ToolRegistry
McpRegistry
SkillRegistry
PlatformMemoryService
PlatformSessionService
```

底层持久化当前是：

```text
PlatformConfigStore + YAML
```

后续切换为：

```text
DB-backed Registry / Store
```
