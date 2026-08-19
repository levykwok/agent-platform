# AgentScope 工具治理与运行时安全审查

> 审查日期：2026-08-19  
> 审查范围：AgentScope Harness 运行时、平台工具装配、MCP/Skill、Agent 编排、工具目录与启停链路  
> 文档状态：审查结论，尚未包含修复实现

## 1. 结论摘要

当前平台的“工具目录”与 Agent 实际拿到的 Toolkit 不是同一份清单。

普通 Agent 在运行时会被 AgentScope Harness 自动增加文件、Shell、记忆、会话、子 Agent 和 Skill 工具。当前平台没有统一的运行时 Tool Policy，也没有显式关闭这些默认能力。因此，普通 Agent 实际可能拿到约 20 个内置工具，Supervisor 还会额外拿到定时任务工具。

当前存在四个需要优先处理的问题：

1. ReAct 最大轮数依赖 AgentScope 默认值 `10`，平台没有显式配置，也没有暴露总工具调用次数、总执行时间等硬限制。
2. 沙箱默认关闭，但默认本地文件系统仍可能注册宿主机 `execute` 工具；文件访问根目录包含 `${user.dir}` 和 Agent workspace。
3. Tools 页面展示的是配置目录，不是最终运行时工具；页面的“启用/停用”只保存 binding 状态，当前不会可靠地改变运行时 Toolkit。
4. Single Agent 默认拥有 `agent_spawn`，可以创建 `general-purpose` 子 Agent；子 Agent 会复制父级 Toolkit，空工具白名单还会被解释成“继承全部父工具”。

在完成工具收口、文件系统隔离和子 Agent 权限继承修复之前，当前实现不应被视为适合不可信多用户环境。

## 2. 当前运行时工具组成

### 2.1 AgentScope Harness 自动工具

当前 [AgentScopeHarnessFactory](../../src/main/java/io/agent/platform/adapter/agentscope/AgentScopeHarnessFactory.java) 创建 Harness Agent 时配置了 workspace、memory、compaction、skill repositories 和 permission context，但没有调用以下收口方法：

```java
.disableFilesystemTools()
.disableShellTool()
.disableMemoryTools()
.disableSubagents()
.disableDynamicSubagents()
.disableWorkspaceContext()
```

因此，以下工具会根据 Harness 默认逻辑自动出现：

| 类别 | 工具 | 当前行为 | 风险 |
|---|---|---|---|
| 文件读取 | `read_file`、`grep_files`、`glob_files`、`list_files` | 默认注册 | 高：可能枚举和读取项目、配置、数据库、日志 |
| 文件写入 | `write_file`、`edit_file` | 默认注册 | 高：可能修改 workspace、Skill、Agent 配置 |
| Shell | `execute` | 默认本地文件系统为 Shell-aware 时注册 | 严重：可能执行宿主机命令、访问网络和其他文件 |
| 长期记忆 | `memory_search`、`memory_get`、`memory_save` | Factory 始终配置 memory | 中高：读取或写入长期记忆 |
| 会话检索 | `session_search`、`session_list`、`session_history` | 默认注册 | 中高：可能读取其他 Agent 或历史会话 |
| 子 Agent | `agent_spawn`、`agent_send`、`agent_list` | 默认动态子 Agent Middleware | 高：派生任务、继承工具、扩大资源消耗 |
| 后台任务 | `task_output`、`task_cancel`、`task_list` | 与子 Agent 工具一起注册 | 中高：读取和操作后台任务 |
| Skill | `load_skill_through_path` | Agent 有 Skill repository 时注册 | 中：加载 Skill 内容，并可能间接触发脚本执行 |

当前普通 Agent 约有 20 个 Harness 内置工具。该数量不包含平台显式工具和 MCP 工具。

### 2.2 当前平台显式工具

当前 SQLite 中的 `tools.yml` 包含：

| 工具 | 类型 | 当前引用情况 | 风险 |
|---|---|---|---|
| `platform_web_tools` | Java | researcher 引用 | 中高 |
| `template` | Python | 当前已启用，但当前已发布 Agent 未引用 | 严重 |

`platform_web_tools` 暴露两个工具：

- `web_search`：访问 DuckDuckGo 搜索接口。
- `web_fetch`：访问任意 `http/https` URL 并返回正文摘要。

`web_fetch` 只做了 URL scheme 校验，没有完整的内网、loopback、云元数据地址和 DNS 解析后的 IP allowlist，因此存在 SSRF 和数据外传风险。实现见 [PlatformWebTools.java](../../src/main/java/io/agent/platform/tool/PlatformWebTools.java)。

`template` 是 Python 进程工具。当前 [PythonScriptTool.java](../../src/main/java/io/agent/platform/tool/PythonScriptTool.java) 通过宿主机 `ProcessBuilder` 直接启动 Python，没有 Docker 沙箱、系统调用限制、网络限制或文件系统隔离。因此它属于宿主机代码执行能力，风险等级应为 `critical`，而不是普通 Python 工具。

### 2.3 当前 MCP 工具

当前 SQLite 中配置了三个 MCP Server，每个启用三个工具：

| MCP Server | 工具 |
|---|---|
| `platform-demo-stdio` | `platform_calculate`、`platform_text_brief`、`platform_markdown_toc` |
| `platform-demo-sse` | `platform_json_validate`、`platform_json_pick`、`platform_date_math` |
| `platform-demo-streamable-http` | `platform_fetch_url`、`platform_extract_links`、`platform_make_id` |

其中 `platform_fetch_url` 和 `platform_extract_links` 允许根据参数访问 URL，同样需要 SSRF 防护。MCP Server 本身还拥有进程、网络、环境变量和远程端点风险，不能仅按工具名称判定为低风险。

### 2.4 Supervisor 自动工具

[AgentCapabilityAssembler](../../src/main/java/io/agent/platform/adapter/agentscope/AgentCapabilityAssembler.java) 对所有 `SUPERVISOR` Agent 自动补充以下 8 个定时任务工具：

```text
schedule_create
schedule_list
schedule_get
schedule_get_runs
schedule_pause
schedule_resume
schedule_delete
schedule_run_now
```

这意味着 Agent 定义中的 `toolRefs` 为空时，Supervisor 仍可能拿到工具。当前能力统计只统计定义中的 `toolRefs`，因此会低估真实工具数量。

### 2.5 当前未由平台 Factory 主动启用的 Harness 工具

以下工具在 AgentScope Harness 中存在，但当前平台 Factory 没有显式启用：

- `agent_generate`
- `skill_manage`
- `propose_skill`
- `plan_enter`
- `plan_write`
- `plan_exit`
- `wait_async_results`
- Core Todo/Meta 工具

它们不能被认为是永久安全的。后续如果升级 Harness 或修改 Factory，需要重新核对默认开关。

## 3. 文件系统与数据泄露风险

当前 [application.yml](../../src/main/resources/application.yml) 默认配置：

```yaml
agent.platform.sandbox.enabled: false
agent.platform.workspace: ${user.dir}/workspace
```

AgentScope Harness 在没有显式 filesystem 配置时，会使用本地 `LocalFilesystemSpec`。默认项目根目录是 `${user.dir}`，workspace 也是允许根目录之一。ROOTED 路径策略可以阻止路径跳出这些根目录，但不能阻止 Agent 读取根目录内的其他文件。

因此，在服务工作目录是项目或部署根目录时，Agent 可能读取：

- `workspace/platform-platform.db`
- `application.yml` 和其他配置文件
- 日志和临时文件
- 其他 Agent workspace
- 项目源码和脚本
- 部署目录内的密钥或环境文件

此外，默认本地 overlay 使用宿主机文件系统，并且可能实现 `AbstractSandboxFilesystem`，所以 Harness 会继续注册 `execute`。当前 Factory 设置了 `PermissionMode.BYPASS`，也没有额外的人审或拒绝策略。

这套配置适合可信的单机开发助手，不适合不可信用户共享同一个 JVM、同一个部署目录的多租户平台。

## 4. 工具目录、展示和启停现状

### 4.1 页面能看到什么

当前 `/platform/frontend/tools` 主要返回：

- `tools.yml` 中的 ToolSpec
- MCP 发现缓存中的工具
- 用户自定义 HTTP 工具
- Workflow 工具

实现见 [PlatformFrontendCompatibilityController.java](../../src/main/java/io/agent/platform/web/PlatformFrontendCompatibilityController.java)。

页面看不到 Harness 自动注入的文件、Shell、memory、session、subagent 和 Skill loader 工具。因此当前 Tools 目录不是实际运行时工具列表。

同样，运行事件中的 `capability_loaded` 只报告：

```text
definition.toolRefs().size()
definition.mcpRefs().size()
definition.skillRefs().size()
```

它没有读取最终 `HarnessAgent.getToolkit()` 的工具 schema，导致前端和审计数据都会低估工具范围。

### 4.2 页面启停是否生效

Tools 页面启停调用：

```text
PUT /platform/frontend/tools/bindings/{toolId}
```

但当前 [PlatformCompatibilityState.saveToolBinding](../../src/main/java/io/agent/platform/web/PlatformCompatibilityState.java) 只是将 `binding_status` 写入内存中的 `toolBindings` 并记录审计。

运行时装配主要检查：

```java
ToolSpec.enabled()
McpSpec.enabled()
SkillSpec.enabled()
```

没有读取 `binding_status`，也没有统一的运行时 deny gate。因此：

- 页面显示停用，不代表运行时不会注册；
- binding 状态不是可靠的 SQLite 运行时策略；
- 内置 Harness 工具完全没有逐工具启停入口；
- 风险确认目前也没有真正阻断工具调用；
- `toolPolicy` 接口目前更接近兼容层回显，不是完整的运行时策略写入。

### 4.3 当前 Agent 工具选择的边界

Agent 管理页保存的 `tool_scope.include` 主要影响平台显式工具和 MCP 工具引用。它不能关闭 Harness 自动工具。

因此，即使 Agent 页面显示：

```text
平台工具：0 个
```

模型仍可能获得文件、Shell、记忆、会话、Skill 和子 Agent 工具。

## 5. ReAct 轮数与执行上限

AgentScope Core 的 `ReActAgent.Builder` 暴露了 `maxIters(int)`，底层默认值为 `10`。平台当前没有显式调用 `.maxIters(...)`，因此依赖的是 AgentScope 的隐式默认值。

这存在几个问题：

- 默认值属于框架行为，升级依赖后可能变化；
- `maxIters` 限制的是 ReAct 推理轮，不等于总工具调用次数；
- Supervisor 的每个子调用会有自己的 ReAct 上限；
- Workflow 每个步骤调用的 Agent 也会单独拥有上限；
- 平台没有统一的总执行时间、最大工具调用次数、最大并发子 Agent 数量。

因此当前只能够说“底层默认大约 10 轮”，不能说平台已经完成了可治理的轮数限制。

## 6. Single Agent 与子 Agent 工具范围

### 6.1 Single Agent 默认可以创建子 Agent

当前 Factory 没有调用 `disableSubagents` 或 `disableDynamicSubagents`。AgentScope Harness 在 Agent 有模型和 filesystem 时会自动安装动态子 Agent Middleware。

Harness 还会始终加入一个 `general-purpose` 子 Agent 定义。因此即使平台 Agent 的编排模式是 `SINGLE`、没有配置业务子 Agent，仍可能出现：

```text
agent_spawn(agent_id="general-purpose")
```

Single Agent 还拥有 `write_file`，理论上可以写入 `subagents/*.md`，让动态子 Agent 目录增加新的 Agent 定义。虽然 `agent_generate` 当前没有主动开启，但这并不能阻止通过文件工具间接创建子 Agent。

### 6.2 子 Agent 会继承父级工具

通用子 Agent 工厂会复制父级 Toolkit：

```java
.toolkit(capturedParentToolkit.copy())
```

这会继承父 Agent 的显式 Java、HTTP、MCP 等工具；子 Agent 自己构建时还会再次根据 Harness 默认配置增加文件、Shell、记忆、会话等工具。

声明式子 Agent 还有一个更严重的问题：空工具列表会被解释成“不做过滤”。当前逻辑类似：

```java
if (allowlist == null || allowlist.isEmpty()) {
    return toolkit;
}
```

所以：

```yaml
toolRefs: []
```

实际效果不是“没有工具”，而是“继承父级全部显式工具”。当前 main 的 `researcher_analysis` 和 `writer_release_brief` 绑定都使用空 `toolRefs`，因此存在范围过宽问题。

叶子子 Agent 会阻止继续递归创建子 Agent，但不能解决父级工具继承和默认内置工具自动注入问题。

## 7. 风险等级总表

| 风险 | 等级 | 主要原因 |
|---|---|---|
| 宿主机 Shell 执行 | Critical | 沙箱关闭时仍可能注册 `execute` |
| 宿主机 Python 执行 | Critical | `ProcessBuilder` 直接启动 Python |
| 文件和配置读取 | High | 默认根目录包含 `${user.dir}` 和 workspace |
| 文件写入和修改 | High | 默认注册 `write_file`、`edit_file` |
| 子 Agent 工具继承 | High | 通用子 Agent复制父 Toolkit，空白 allowlist 等于全继承 |
| Session/Memory 跨 Agent 读取 | Medium-High | 具备历史会话和记忆检索能力 |
| URL Fetch / SSRF | High | web/MCP fetch 缺乏完整网络 allowlist |
| MCP 进程与远程连接 | Medium-High | Server 端具有独立进程、网络和环境风险 |
| 轮数和资源消耗 | Medium-High | 只有隐式 10 轮，无统一调用/并发/时间配额 |
| 工具启停失真 | High | 页面 binding 状态没有进入实际 Toolkit Policy |

## 8. 修复优先级

### P0：先阻断高危默认能力

1. 普通 `SINGLE` Agent 默认关闭文件工具、Shell、memory/session 和 subagent。
2. 多用户环境强制 Docker 沙箱或完全不提供本地文件系统。
3. 禁止宿主机 Python 直接执行；Python 工具必须进入受限沙箱。
4. 移除或重新设计 `PermissionMode.BYPASS`。
5. 禁止 `${user.dir}` 作为 Agent 可读根目录，只允许用户/Agent 专属 workspace。

### P1：建立统一运行时工具策略

1. 为每个 Agent 生成最终 Tool Manifest，包含内置、显式、MCP、Skill 和编排工具。
2. Tool Manifest 同时作为模型 schema、审计记录和前端展示来源。
3. 工具策略采用 deny-by-default，空列表必须表示“无工具”。
4. 将文件读取、文件写入、Shell、网络访问、子 Agent 拆成独立 capability。
5. 让 `binding_status`、`ToolSpec.enabled`、`McpSpec.enabled` 和 Agent tool scope 统一进入同一个运行时决策点。

### P1：修正子 Agent 边界

1. Single Agent 默认禁止 `agent_spawn`。
2. 只允许显式声明的子 Agent，不自动注册 `general-purpose`。
3. 子 Agent 显式继承工具，未声明的工具全部拒绝。
4. `toolRefs: []` 改为“无工具”，增加显式 `inherit_tools: true` 才允许继承。
5. 为每个用户、每个会话设置最大子 Agent 数、最大并发数、最大深度和总执行时间。

### P2：补齐可观测性与 UI

1. Tools 页面展示最终运行时工具，而不是只展示配置目录。
2. 明确展示工具来源、风险、是否自动注入、是否可写、是否可联网、是否可创建子 Agent。
3. 启停操作完成后重新构建或失效 Agent cache，确保下一次运行生效。
4. 运行事件增加 `runtime_tool_manifest`、`max_iters`、`tool_call_budget`、`subagent_budget`。
5. 对每次工具调用记录 Agent、用户、会话、工具来源、参数摘要、结果摘要和拒绝原因。

## 9. 验收标准

修复完成后至少应满足：

- Tools 页面显示的工具集合与模型实际收到的 tool schema 一致；
- 普通 Single Agent 不再默认看到 `execute`、`agent_spawn` 和跨会话工具；
- 停用工具后，实际模型请求中不再包含该工具 schema；
- `toolRefs: []` 的子 Agent 不继承任何父级显式工具；
- 用户不能读取其他用户 workspace、SQLite、配置或历史会话；
- Python 和 Shell 工具均运行在受限沙箱内；
- ReAct 轮数、总工具调用数、子 Agent 数量和总耗时均有硬上限；
- Playwright 能验证工具目录、Agent 工具选择、停用生效、Single Agent 禁止创建子 Agent等完整链路。

