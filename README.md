# Agent Platform

这是智能体平台独立仓库。它使用外部 AgentScope Maven 包，不包含 AgentScope 框架源码：

- `src/main/java/io/agent/platform`：平台后端
- `frontend/live-console`：Vue 管理控制台
- `docs`：平台用户文档
- `doc/agent-platform`：设计与实现文档
- `pom.xml`：外部 AgentScope 依赖（`agentscope-harness`、`agentscope-extensions-rag-simple`）

## 当前已提供
- Spring Boot 启动入口：`io.agent.platform.PlatformApplication`
- 默认运行端口：8080
- 默认 workspace：`${user.dir}/workspace`
- 可覆盖运行目录环境变量：`AGENT_PLATFORM_WORKSPACE`

## 运行方式
```bash
mvn clean package -DskipTests
java -jar target/agent-platform-*.jar
```

如果使用 AgentScope 源码仓库进行本地开发，先在 AgentScope 仓库安装依赖包：

```bash
mvn -pl agentscope-harness,agentscope-extensions/agentscope-extensions-rag/agentscope-extensions-rag-simple -am -DskipTests install
```

生产/演示环境不需要 AgentScope 源码。可以从平台项目的 Release 下载 Maven 依赖归档：

```powershell
$url = "https://github.com/levykwok/agent-platform/releases/latest/download/agentscope-maven-repo-2.0.0-SNAPSHOT.zip"
.\scripts\bootstrap-agentscope.ps1 -ArchiveUrl $url
mvn clean package -DskipTests
```

也可以让启动脚本在依赖缺失时自动下载并安装：

```powershell
.\start-platform.ps1 -AgentScopeArchiveUrl $url
```

维护者可用下面的命令生成 Release 附件。执行前需要先把对应版本的 AgentScope 包安装到本机 Maven 仓库：

```powershell
.\scripts\package-release.ps1 -Revision 2.0.0-SNAPSHOT -Clean
```

输出包括平台运行包和 `agentscope-maven-repo-2.0.0-SNAPSHOT.zip`。后者上传到项目 Release 即可，不依赖相邻的 AgentScope 源码目录。

启动完整开发环境：

```powershell
.\start-platform.ps1
```

平台业务代码建议放到：
- `io.agent.platform.agent`：Agent 组装与编排
- `io.agent.platform.web`：HTTP/API 控制层
- `io.agent.platform.service`：业务服务
- `io.agent.platform.config`：配置与 Bean 组装
