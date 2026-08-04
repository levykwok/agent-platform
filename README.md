# Company Agent Platform

这是公司智能体平台独立仓库。它使用已发布/已安装的 AgentScope Maven 包，不包含 AgentScope 框架源码：

- `src/main/java/com/company/platform`：平台后端
- `frontend/live-console`：Vue 管理控制台
- `docs`：平台用户文档
- `doc/company-platform`：设计与实现文档
- `pom.xml`：外部 AgentScope 依赖（`agentscope-harness`、`agentscope-extensions-rag-simple`）

## 当前已提供
- Spring Boot 启动入口：`com.company.platform.PlatformApplication`
- 默认运行端口：8080
- 默认 workspace：`${user.dir}/workspace`
- 可覆盖运行目录环境变量：`COMPANY_PLATFORM_WORKSPACE`

## 运行方式
```bash
mvn clean package -DskipTests
java -jar target/company-platform-*.jar
```

如果使用 AgentScope 源码仓库进行本地开发，先在 AgentScope 仓库安装依赖包：

```bash
mvn -pl agentscope-harness,agentscope-extensions/agentscope-extensions-rag/agentscope-extensions-rag-simple -am -DskipTests install
```

也可以直接使用已经发布的 AgentScope 版本，修改 `pom.xml` 中的 `agentscope.version`。

启动完整开发环境：

```powershell
.\start-platform.ps1
```

平台业务代码建议放到：
- `com.company.platform.agent`：Agent 组装与编排
- `com.company.platform.web`：HTTP/API 控制层
- `com.company.platform.service`：业务服务
- `com.company.platform.config`：配置与 Bean 组装
