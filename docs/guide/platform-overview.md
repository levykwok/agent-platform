# 平台概览

“平台概览”是登录后的默认页面，用于快速判断平台是否可用，并跳转到常用能力。

## 页面区域

- **快捷入口**：进入模型、工具、MCP、Skills、Agent 等管理页面。
- **基础设施状态**：查看平台服务及相关组件状态，可手动刷新。
- **业务域状态**：查看已注册业务域的概要信息。
- **顶部状态**：每 10 秒检查一次后端健康状态，并显示当前业务域。

## 侧栏当前开放入口

| 入口 | 主要用途 |
|---|---|
| 平台概览 | 健康状态与快捷导航 |
| 模型接入 | 供应商、模型、插槽和调用审计 |
| Tools 目录 | 平台工具的创建、测试和启停 |
| MCP 服务器 | 注册 MCP 服务、发现并测试工具 |
| Skills 中心 | 创建、上传、审批和维护 Skill |
| Agent 管理 | 组合 Agent 的全部运行策略 |
| 交互问答 | 面向业务问答和知识上下文 |
| Agent 工作台 | 面向 Agent 会话、执行过程和调试 |
| 记忆管理 | 长期记忆审核与维护 |
| 使用文档 | 在控制台内查看、搜索本手册 |

顶部显示“后端连接异常”时，页面中的读取和保存操作通常也会失败，应先处理服务连接，
不要重复提交配置。

## Skill 脚本沙箱

平台可使用 AgentScope 的 Docker 沙箱运行 Skill 包中的脚本。开启
`COMPANY_PLATFORM_SANDBOX_ENABLED=true` 后，每个会话在独立的 Docker 容器中执行，
默认镜像为 `python:3.12-slim`，网络关闭、内存限制为 512 MB、CPU 限制为 1 核，且不暴露
宿主机端口。Harness 会自动投影 Skill 文件并向 Agent 提供 `execute` 工具；未开启时，平台
不会降级为宿主机 shell 执行。

启动前需确认 `docker info` 成功；可通过 `COMPANY_PLATFORM_SANDBOX_IMAGE`、
`COMPANY_PLATFORM_SANDBOX_MEMORY_SIZE_BYTES` 和 `COMPANY_PLATFORM_SANDBOX_CPU_COUNT`
调整镜像与资源限制。
