# 详细设计模块与 AgentScope 实现对照 - 分模块索引

本目录按详细设计模块拆分，每个模块一个文件。每个文件参考 `../agentscope-memory.md` 的写法，重点说明：AgentScope 链路、平台接入方式、接口调用设计、缺口和落地优先级。

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

## 模块文件

- [模型统一接入](./01-model-unified-access.md)
- [对外统一 API 网关](./02-api-gateway.md)
- [智能体管理](./03-agent-management.md)
- [智能体编排](./04-agent-orchestration.md)
- [MCP Server 管理](./05-mcp-management.md)
- [Skills 管理](./06-skill-management.md)
- [会话管理](./07-session-management.md)
- [记忆管理](./08-memory-management.md)
- [多模态内容呈现](./09-multimodal-rendering.md)
- [多模态人机交互](./10-multimodal-interaction.md)
- [运行时](./11-runtime.md)
- [AI 模型监控工具](./12-model-monitoring.md)
- [用户和权限管理](./13-user-permission.md)
- [安全设计与操作日志](./14-security-audit.md)
- [运维智能体](./15-ops-agent.md)
- [监盘智能体](./16-monitoring-agent.md)
- [RAG 知识检索](./17-rag.md)

## 分层原则

```text
平台管理层：我们做
平台持久化层：我们做，当前 YAML，后续 DB
运行时适配层：我们做薄封装
Agent 执行层：优先调用 AgentScope Harness
```

## 每个模块文档的固定内容

```text
结论
平台模块功能设计
AgentScope 对应实现
当前平台已实现
运行时/管理侧调用链路
接口调用设计
HTTP 请求/响应示例
Java 调用点
DB 化边界
还缺什么
推荐下一步
```


## 2026-07-09 更新：基于当前代码集成后的总体修正

经过对 AgentScope Harness 实际链路的接入验证，本目录中的模块差距需要按以下边界重新理解。

```text
AgentScope 负责：运行时 Agent 构建、模型调用、工具/MCP/Skill 注入、Workspace、AgentState、memory 文件链路、流式事件。
平台负责：资产管理、配置持久化、UI、权限、审计、SQLite 主数据、供应商管理、发布流程、可观测性和治理。
```

关键修正：

```text
1. 不要把 HarnessAgent 理解成业务 Agent 定义。HarnessAgent 是 AgentScope 的运行时 Agent 容器。
2. 平台的 AgentDefinition 才是业务可管理对象；运行时再转换为 HarnessAgent.Builder。
3. toolRefs、mcpRefs、skillRefs 属于平台 AgentDefinition 绑定关系；真正注入点在 runtime/HarnessAgent 构建阶段。
4. MCP 工具发现是否预拉、是否持久化，由平台控制；HarnessAgent 运行时只需要拿到可注册的 MCP server config。
5. memory 存在两层：平台 SQLite 长期记忆治理层，以及 AgentScope workspace 的 MEMORY.md / memory/YYYY-MM-DD.md 运行文件层。
6. AgentScope 的 memory 文件必须按 user namespace 隔离，当前实际路径是 workspace/{agentId}/{org}_{user}/MEMORY.md。
7. 自动记忆抽取 prompt 可以通过 MemoryConfig.flushPrompt 外部覆盖，不需要改 AgentScope 源码。
8. 模型接入不能只依赖 AgentScope 内置 provider。平台需要把 openai-compatible、http_chat、ollama 等配置转换为 AgentScope 可调用 Model。
9. 当前平台已切向 SQLite 单路主存储；YAML/classpath 更适合作为初始化模板或备份来源，不应作为运行期 fallback 主链路。
10. UI 上“只展示”和“可操作”的模块要区分，例如 episodic index 当前只展示状态，不应暴露未完整实现的维护操作。
```

落地优先级修正：

```text
P0：模型配置可运行、AgentDefinition 保存/发布、工具/MCP/Skill 注入、会话和 memory 路径一致。
P1：memory 治理闭环、MCP 工具发现缓存、Agent 运行过程可观测、平台工具测试。
P2：RAG、跨会话 episodic index、权限审计、多租户隔离策略完善。
```
