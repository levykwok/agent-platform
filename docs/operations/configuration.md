# 配置与数据

## 默认位置

使用 `start-platform.ps1` 时，默认工作区为：

```text
workspace
```

Spring Boot 单独启动时，`application.yml` 的默认回退值是当前进程目录下的
`workspace`。推荐显式设置 `AGENT_PLATFORM_WORKSPACE`，避免从不同目录启动时读到
不同数据。

主要配置文件：

| 文件 | 内容 |
|---|---|
| `providers.yml` | 模型供应商 |
| `models.yml` | 模型目录 |
| `agents.yml` | Agent 定义 |
| `tools.yml` | 平台工具 |
| `mcps.yml` | MCP 服务 |
| `skills.yml` | Skills |

默认持久化模式为 SQLite，数据库位于工作区的 `platform-platform.db`。也可以通过
启动参数选择文件模式。

## 常用环境变量

| 变量 | 用途 |
|---|---|
| `AGENT_PLATFORM_WORKSPACE` | 平台工作区 |
| `AGENT_PLATFORM_PERSISTENCE_MODE` | `sqlite` 或 `file` |
| `AGENT_PLATFORM_SQLITE_URL` | 自定义 SQLite JDBC URL |
| `AGENT_PLATFORM_MODELS_CONFIG` | 模型配置位置 |
| `AGENT_PLATFORM_PROVIDERS_CONFIG` | 供应商配置位置 |
| `AGENT_PLATFORM_AGENTS_CONFIG` | Agent 配置位置 |
| `AGENT_PLATFORM_TOOLS_CONFIG` | Tools 配置位置 |
| `AGENT_PLATFORM_MCPS_CONFIG` | MCP 配置位置 |
| `AGENT_PLATFORM_SKILLS_CONFIG` | Skills 配置位置 |

## 备份建议

备份前应停止写入，至少保存整个工作区，而不是只复制 YAML。SQLite、会话、Skill 包、
MCP 发现缓存和运行产物都可能位于工作区内。

密钥不应以明文写入文档或提交到 Git。界面中的 Secret Ref 应指向部署环境认可的密钥
引用方式。
