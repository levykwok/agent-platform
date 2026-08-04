# Skills 管理

## 结论

AgentScope 的 Skill 是运行时能力包，支持 classpath、filesystem、Git、Nacos、数据库等 repository。但 Skill 的版本、发布、测试、权限、审计，需要平台管理层补齐。

## 平台模块功能设计

本模块负责把团队经验、操作规范、分析流程沉淀成可被 Agent 运行时加载的 Skill。

核心功能：

```text
1. Skill 注册：支持 classpath、filesystem、local，后续支持 git/db/nacos。
2. Skill 内容解析：解析 SKILL.md frontmatter、description、instructions、resources。
3. Skill 元数据管理：维护 domain、tags、适用场景、风险等级、描述。
4. Skill 版本管理：支持 draft、published、disabled、deprecated。
5. Skill 测试：校验格式、依赖、脚本、安全、样例输入输出。
6. Skill 发布：测试通过后发布为可绑定版本。
7. Skill 依赖管理：声明依赖 builtin tool、MCP tool、模型、脚本、平台服务。
8. Agent 绑定：控制 Agent 可见或必需的 Skill 集合。
9. 运行时加载：转换成 AgentSkillRepository 注入 HarnessAgent。
10. 调用追溯：记录 AgentRun 中加载或使用的 Skill。
```

输入资源：

```text
SkillSpec
SKILL.md
references/scripts/examples
Agent skillRefs
依赖工具和 MCP 状态
```

输出资源：

```text
AgentSkillRepository
Skill 版本记录
Skill 测试报告
Skill 绑定关系
Skill 调用记录
```

## AgentScope 对应实现

关键概念：

```text
AgentSkillRepository
ClasspathSkillRepository
FileSystemSkillRepository
load_skill_through_path
Skill runtime
workspace/skills/<skill-name>/SKILL.md
```

AgentScope 文档还支持：

```text
GitSkillRepository
Nacos skill repository
MySQL/PostgreSQL skill repository
```

## 当前平台已实现

```text
SkillRegistry
YamlSkillRegistry
SkillSpec
AgentCapabilityAssembler.buildSkillRepositories(...)
PlatformConfigStore
```

落盘：

```text
workspace/skills.yml
```

## 运行时调用链路

```text
AgentDefinition.skillRefs()
  -> SkillRegistry.find(skillRef)
  -> SkillSpec
  -> ClasspathSkillRepository / FileSystemSkillRepository
  -> HarnessAgent.builder().skillRepositories(repos)
  -> Agent 推理时按需 load_skill_through_path
```

## AgentScope 能解决什么

```text
Skill 目录解析
Skill repository 加载
Skill 按需加载到上下文
Skill 资源读取
```

## AgentScope 不解决什么

```text
Skill 平台侧元数据
Skill 版本状态
Skill 发布审核
Skill 测试报告
Skill 依赖校验
Skill 风险等级
Skill 绑定影响范围
```

## 还缺什么

```text
Skill 管理页面
Skill 版本表
Skill 测试流程
Skill 发布/停用/废弃
Skill 资源包上传
Skill 调用记录关联
Skill 权限和审计
```

## 推荐下一步

```text
P0: Skill 管理页面接 YamlSkillRegistry
P1: SkillSpec 增加 version/status/riskLevel/domain
P2: 加 SkillTestService，至少校验 SKILL.md/frontmatter/location
P3: 后续切 DB 时实现 DbSkillRegistry 或 SkillRepository 适配
```

## 接口调用设计

### Skill 注册

```http
POST /platform/frontend/skills
Content-Type: application/json

{
  "skill_id": "alarm-analysis",
  "type": "filesystem",
  "location": "workspace/skills/alarm-analysis",
  "source": "manual",
  "scope": "platform",
  "writable": true,
  "description": "告警分析流程和输出规范",
  "enabled": true
}
```

### Skill 列表

```http
GET /platform/frontend/skills?keyword=alarm&enabled=true
```

### Skill 测试建议

```http
POST /platform/frontend/skills/{skillId}/test
Content-Type: application/json

{
  "sample_input": {
    "query": "数据库连接失败告警怎么分析"
  },
  "test_type": "sample"
}
```

### Agent 绑定 Skill

```http
PUT /platform/frontend/agents/{agentId}/skill-refs
Content-Type: application/json

{
  "skill_refs": ["alarm-analysis", "incident-report"]
}
```

### 运行时加载链路

```text
AgentDefinition.skillRefs()
  -> SkillRegistry.find(skillId)
  -> SkillSpec
  -> AgentCapabilityAssembler.buildSkillRepositories(...)
  -> HarnessAgent.builder().skillRepositories(repos)
  -> Agent 推理时调用 load_skill_through_path
```

### Java 伪代码

```java
List<AgentSkillRepository> repos = capabilityAssembler.buildSkillRepositories(definition);
HarnessAgent.builder().skillRepositories(repos);
```

### Skill 文件结构建议

```text
skills/alarm-analysis/
  SKILL.md
  references/
  scripts/
  examples/
```

### DB 化边界

当前 YAML：

```text
skills.yml
```

后续 DB：

```text
skill
skill_version
skill_dependency
skill_test_result
```

运行时仍然输出 AgentScope 需要的：

```text
AgentSkillRepository
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

## 2026-07-09 更新：Skill 运行机制修正

AgentScope 的 Skill 不是普通平台附件。它通过 `load_skill_through_path` 工具让模型按需读取 `SKILL.md`、examples、scripts 等资源。

关键理解：

```text
1. 平台负责 Skill 包上传、校验、预览、发布、版本和 Agent 绑定。
2. AgentScope 负责 SkillRepository 和 runtime skill load 工具。
3. Skill 文件需要落到 AgentScope 可访问的 filesystem/workspace/cache 中，模型再通过工具读取。
4. load_skill_through_path 是工具调用，不是平台内部透明加载；这是 AgentScope 的设计选择。
```

平台侧要求：

```text
1. Skill 上传应支持 zip 和文件夹。
2. 上传后应进入 package 队列，发布后才变成可绑定 Skill。
3. Skill 详情应支持 GitHub 风格文件树预览、SKILL.md 渲染、脚本列表、examples 列表。
4. Agent 绑定 Skill 应在 Agent 配置页完成；Skill 页面只显示被哪些 Agent 引用。
5. Skill 创建/编辑不应让普通用户填写 filesystem/className/repository path 这类底层字段。
```

缺口：

```text
1. Skill package 队列和发布记录应完整进入 SQLite。
2. Skill 测试只有在包含脚本或声明 test command 时才展示。
3. Skill 调用过程需要在 Agent 事件流里展示：load_skill、list_files、read_file、execute 等。
4. Skill cache 路径是运行时文件，不应暴露给普通用户。
```
