# Linux / macOS 开发环境运行

把服务器当作开发机用：直接起后端 + Vite 前端，不需要 Nginx。

宿主机 **glibc < 2.27**（例如 CentOS 7 / glibc 2.17）时，官方 Node 二进制跑不起来，请直接看下面的 [Docker](#docker旧-glibc-机器推荐) 方案。

## Docker（旧 glibc 机器推荐）

容器自带新系统库，宿主机不用装 Node / 不用折腾 conda。

### 前置

- 已安装 Docker，以及 `docker compose`（或旧版 `docker-compose`）
- 仓库里已有可执行包：`target/agent-platform-0.1.0-SNAPSHOT.jar`（可在开发机打好后拷上去）

### 启动

```bash
cd agent-platform
docker compose up -d --build
# 旧命令：docker-compose up -d --build
```

### 访问

- 前端：`http://<主机>:5173/platform/live?domain=platform&org_id=platform&user_id=platform_admin`
- 后端：`http://<主机>:8080`

### 常用命令

```bash
docker compose logs -f          # 看日志
docker compose ps               # 状态
docker compose down             # 停止并删除容器
```

数据在宿主机 `./workspace`（已挂载进后端容器）。

可选向量库：

```bash
AGENT_PLATFORM_QDRANT_ENABLED=true docker compose --profile rag up -d --build
```

---

## 依赖（非 Docker / 直接跑宿主机）

最少需要：

- JDK 17+（`java -version`）
- Node.js 18+（含 npm）
- `lsof` 或 `fuser`（脚本释放端口用）

后端有两种跑法：

1. **有现成 JAR**（推荐，服务器不用装 Maven）：把 `target/agent-platform-*.jar` 拷到机器上即可
2. **没有 JAR**：需要安装 Maven，并用 AgentScope 依赖归档做一次 bootstrap

## 准备代码

```bash
git clone <仓库地址> agent-platform
cd agent-platform
chmod +x start.sh stop.sh
sed -i 's/\r$//' start.sh stop.sh   # 若从 Windows 拷过来，先去掉 CRLF
```

## 启动（推荐：带 JAR）

在开发机打好包：

```bash
mvn clean package -DskipTests
```

把 `target/agent-platform-*.jar`（不要 `*-plain.jar`）拷到服务器的 `target/`，然后：

```bash
./start.sh
# 或显式指定：
./start.sh --jar target/agent-platform-0.1.0-SNAPSHOT.jar
```

有 JAR 时不需要 `--agentscope-archive-url`，也不需要 `mvn`。

## 启动（服务器上用 Maven 编译运行）

```bash
# Ubuntu / Debian 示例
sudo apt update
sudo apt install -y openjdk-17-jdk maven nodejs npm

./start.sh --agentscope-archive-url \
  https://github.com/levykwok/agent-platform/releases/download/v0.1.0-SNAPSHOT/agentscope-maven-repo-2.0.0-SNAPSHOT.zip
```

（注意：这个 Release 是 prerelease，`/latest/` 会 404，要用上面带 tag 的地址。）

或本地 zip：

```bash
./start.sh --agentscope-archive /path/to/agentscope-maven-repo-2.0.0-SNAPSHOT.zip
```

依赖已经装好后：

```bash
./start.sh
```

脚本会：

1. 默认占用端口时先停掉旧进程（8080 / 5173 / 8765 / 8766）
2. 有 JAR 用 `java -jar`；否则检查 AgentScope 依赖并用 `mvn spring-boot:run`
3. 前端没有 `node_modules` 时执行 `npm install`
4. 后台拉起 demo MCP、后端、前端（`npm run dev`）
5. 日志写到 `logs/platform/`

## 访问

- 后端：`http://<主机>:8080`
- 前端：`http://<主机>:5173/platform/live?domain=platform&org_id=platform&user_id=platform_admin`

前端 Vite 会把 `/platform/*` 代理到本机 8080。

## 停止

```bash
./stop.sh
# 或
./start.sh --stop
```

## 常用参数

| 参数 | 说明 |
|---|---|
| `--jar PATH` | 指定后端 JAR（跳过 Maven） |
| `--force-maven` | 即使有 JAR 也走 `mvn spring-boot:run` |
| `--backend-only` | 只起后端（和 demo MCP） |
| `--frontend-only` | 只起前端 |
| `--skip-demo-mcp` | 不起 demo MCP |
| `--workspace DIR` | 工作区，默认 `./workspace` |
| `--backend-port N` | 默认 8080 |
| `--frontend-port N` | 默认 5173 |
| `--persistence-mode sqlite\|file` | 默认 sqlite |

完整列表：`./start.sh --help`

## 可选：Qdrant

需要向量检索时再开，不是启动必需：

```bash
docker compose -f docker-compose.qdrant.yml up -d
```

## 配置与数据

工作区默认是仓库下的 `workspace/`。建议显式设置：

```bash
export AGENT_PLATFORM_WORKSPACE=/path/to/workspace
./start.sh --workspace "$AGENT_PLATFORM_WORKSPACE"
```

备份时拷整个工作区（含 SQLite 与 skills），不要只拷 YAML。更多变量见 [configuration.md](./configuration.md)。
