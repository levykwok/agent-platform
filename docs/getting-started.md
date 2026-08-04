# 快速开始

## 环境要求

- JDK 17 或更高版本
- Maven
- Node.js 与 npm
- Windows PowerShell（使用仓库一键启动脚本时）

## 一键启动

在仓库根目录执行：

```powershell
.\start-platform.ps1
```

脚本默认启动：

- 平台后端：`http://localhost:8080`
- 前端开发服务器：`http://localhost:5173`
- 演示 MCP HTTP 服务：端口 `8765`
- 演示 MCP SSE 服务：端口 `8766`

浏览器访问：

```text
http://localhost:5173/platform/live
```

首次启动会安装或构建所需依赖，耗时通常比后续启动长。运行日志写入
`logs/platform/`。

## 常用启动方式

```powershell
# 不启动演示 MCP
.\start-platform.ps1 -SkipDemoMcp

# 只启动后端
.\start-platform.ps1 -BackendOnly

# 指定后端和前端端口
.\start-platform.ps1 -BackendPort 8081 -FrontendPort 5174

# 使用文件持久化（默认使用 SQLite）
.\start-platform.ps1 -PersistenceMode file

# 指定独立工作区
.\start-platform.ps1 -Workspace "D:\agent-platform-data"
```

## 判断启动是否成功

进入控制台后，顶部状态应显示“平台运行正常”。若显示“后端连接异常”：

1. 查看 `logs/platform/` 下的后端错误日志；
2. 确认后端端口没有被其他程序占用；
3. 确认前端代理的目标端口与 `-BackendPort` 一致；
4. 检查 JDK、Maven 和 Node.js 是否可以在当前终端直接执行。

## 停止平台

关闭脚本启动的进程，或终止对应的 Java、Node.js 和演示 MCP 进程。再次运行脚本时，
默认会处理它所使用端口上的既有监听进程；如需保留既有进程，可加
`-NoStopExisting`。

