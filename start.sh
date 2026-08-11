#!/usr/bin/env bash
# Dev-style start for Linux/macOS (mirrors start-platform.ps1).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$REPO_ROOT"
FRONTEND_DIR="$REPO_ROOT/frontend/live-console"
WORKSPACE_DIR="$REPO_ROOT/workspace"
DEMO_MCP_SCRIPT="$BACKEND_DIR/mcp-servers/platform-demo/server.mjs"
LOG_DIR="$REPO_ROOT/logs/platform"
RUNTIME_STATE_DIR="$REPO_ROOT/.run"
PID_FILE="$RUNTIME_STATE_DIR/platform-pids.txt"

SKIP_INSTALL=0
SKIP_FRONTEND_INSTALL=0
BACKEND_ONLY=0
FRONTEND_ONLY=0
NO_STOP_EXISTING=0
SKIP_DEMO_MCP=0
STOP=0
SKIP_AGENTSCOPE_BOOTSTRAP=0
FORCE_MAVEN=0
WORKSPACE=""
AGENTSCOPE_ARCHIVE=""
AGENTSCOPE_ARCHIVE_URL=""
MAVEN_REPOSITORY="${HOME}/.m2/repository"
BACKEND_PORT=8080
FRONTEND_PORT=5173
DEMO_MCP_HTTP_PORT=8765
DEMO_MCP_SSE_PORT=8766
REVISION="2.0.0-SNAPSHOT"
PERSISTENCE_MODE="sqlite"
SQLITE_URL=""
BACKEND_JAR=""

export NO_COLOR=1
export FORCE_COLOR=0
export NODE_DISABLE_COLORS=1

usage() {
  cat <<'EOF'
Usage: ./start.sh [options]

  --stop                         Stop tracked processes / listeners
  --backend-only                 Start backend (+ demo MCP) only
  --frontend-only                Start frontend only
  --skip-install                 Skip AgentScope artifact check
  --skip-frontend-install        Skip npm install
  --skip-demo-mcp                Do not start demo MCP servers
  --no-stop-existing             Do not free occupied ports first
  --skip-agentscope-bootstrap    Fail if AgentScope jars are missing
  --force-maven                  Use mvn spring-boot:run even if a JAR exists
  --jar PATH                     Run this Spring Boot JAR (skips Maven)
  --workspace DIR                Platform workspace (default: ./workspace)
  --maven-repository DIR         Local Maven repo (default: ~/.m2/repository)
  --agentscope-archive PATH      Zip to install into local Maven repo
  --agentscope-archive-url URL   Download zip then install
  --backend-port N               Default 8080
  --frontend-port N              Default 5173
  --demo-mcp-http-port N         Default 8765
  --demo-mcp-sse-port N          Default 8766
  --revision VER                 AgentScope revision (default 2.0.0-SNAPSHOT)
  --persistence-mode MODE        sqlite|file (default sqlite)
  --sqlite-url URL               Optional SQLite JDBC URL
  -h, --help                     Show this help

Backend mode:
  If target/agent-platform-*.jar exists (or --jar is set), run with java -jar.
  Otherwise require Maven and use mvn spring-boot:run.
EOF
}

find_boot_jar() {
  local jar=""
  local candidate
  if [[ -n "$BACKEND_JAR" ]]; then
    if [[ ! -f "$BACKEND_JAR" ]]; then
      echo "JAR not found: $BACKEND_JAR" >&2
      exit 1
    fi
    echo "$BACKEND_JAR"
    return 0
  fi
  for candidate in "$REPO_ROOT"/target/agent-platform-*.jar; do
    [[ -f "$candidate" ]] || continue
    [[ "$candidate" == *-plain.jar ]] && continue
    jar="$candidate"
  done
  if [[ -n "$jar" ]]; then
    echo "$jar"
  fi
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

# Prefer JAVA_HOME when set; require major version >= 17 (class file 61).
resolve_java() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
  elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
  else
    echo "Required command not found: java (install JDK 17+ or set JAVA_HOME)" >&2
    exit 1
  fi

  local version_line major
  version_line="$("$JAVA_BIN" -version 2>&1 | head -n 1)"
  if [[ "$version_line" =~ \"([0-9]+)(\.[0-9]+)*\" ]]; then
    major="${BASH_REMATCH[1]}"
    # Old scheme: 1.8.0_xxx -> treat as 8
    if [[ "$major" == "1" && "$version_line" =~ \"1\.([0-9]+) ]]; then
      major="${BASH_REMATCH[1]}"
    fi
  else
    echo "[platform] Unable to parse Java version from: $version_line" >&2
    exit 1
  fi

  if [[ "$major" -lt 17 ]]; then
    echo "[platform] Need Java 17+, but got: $version_line" >&2
    echo "[platform] Using: $JAVA_BIN" >&2
    echo "[platform] Set JAVA_HOME to a JDK 17 install, e.g.:" >&2
    echo "  export JAVA_HOME=\$HOME/apps/jdk-17.0.x" >&2
    echo "  export PATH=\"\$JAVA_HOME/bin:\$PATH\"" >&2
    exit 1
  fi
  echo "[platform] Java: $JAVA_BIN ($version_line)"
}

stop_port_listener() {
  local port="$1"
  local label="$2"
  local pids=""

  if command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  elif command -v fuser >/dev/null 2>&1; then
    echo "[platform] Stop existing $label listener on port ${port} (fuser)"
    fuser -k "${port}/tcp" 2>/dev/null || true
    return 0
  else
    echo "[platform] Warn: install lsof or fuser to auto-free port $port ($label)" >&2
    return 0
  fi

  if [[ -z "$pids" ]]; then
    return 0
  fi
  for pid in $pids; do
    if [[ -n "$pid" && "$pid" != "$$" ]]; then
      echo "[platform] Stop existing $label listener on port ${port}: PID $pid"
      kill "$pid" 2>/dev/null || true
    fi
  done
}

stop_tracked_processes() {
  if [[ ! -f "$PID_FILE" ]]; then
    return 0
  fi
  while IFS='|' read -r title pid; do
    [[ -z "${pid:-}" ]] && continue
    if kill -0 "$pid" 2>/dev/null; then
      echo "[platform] Stop tracked process: PID $pid ($title)"
      # Kill process group when started with setsid
      kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
    fi
  done < "$PID_FILE"
  rm -f "$PID_FILE"
}

stop_platform_services() {
  stop_tracked_processes
  stop_port_listener "$BACKEND_PORT" "backend"
  stop_port_listener "$FRONTEND_PORT" "frontend"
  stop_port_listener "$DEMO_MCP_HTTP_PORT" "demo MCP streamable-http"
  stop_port_listener "$DEMO_MCP_SSE_PORT" "demo MCP sse"
  echo "[platform] Stop command completed."
}

start_platform_process() {
  local title="$1"
  local workdir="$2"
  local log_name="$3"
  shift 3
  local log_path="$LOG_DIR/$log_name"
  local err_path="$LOG_DIR/${log_name%.log}.err.log"
  local pid

  echo "[platform] Start: $title"
  echo "[platform]   log: $log_path"
  echo "[platform]   err: $err_path"

  # Prefer a new session so ./start.sh --stop can kill the whole tree.
  if command -v setsid >/dev/null 2>&1; then
    (
      cd "$workdir"
      exec setsid "$@"
    ) >"$log_path" 2>"$err_path" &
  else
    (
      cd "$workdir"
      exec "$@"
    ) >"$log_path" 2>"$err_path" &
  fi
  pid=$!
  echo "${title}|${pid}" >> "$PID_FILE"
}

artifact_jar() {
  local group_path="$1"
  local artifact_id="$2"
  local version="$3"
  echo "$MAVEN_REPOSITORY/$group_path/$artifact_id/$version/$artifact_id-$version.jar"
}

agentscope_installed() {
  [[ -f "$(artifact_jar io/agentscope agentscope-harness "$REVISION")" ]] \
    && [[ -f "$(artifact_jar io/agentscope agentscope-extensions-rag-simple "$REVISION")" ]]
}

bootstrap_agentscope() {
  local archive="$AGENTSCOPE_ARCHIVE"
  local temp_root
  temp_root="$(mktemp -d "${TMPDIR:-/tmp}/agent-platform-agentscope.XXXXXX")"
  trap 'rm -rf "$temp_root"' RETURN

  if [[ -n "$AGENTSCOPE_ARCHIVE_URL" ]]; then
    archive="$temp_root/agentscope-maven-repo.zip"
    echo "[agentscope] Download: $AGENTSCOPE_ARCHIVE_URL"
    require_cmd curl
    curl -fsSL "$AGENTSCOPE_ARCHIVE_URL" -o "$archive"
  fi

  if [[ -z "$archive" || ! -f "$archive" ]]; then
    echo "AgentScope Maven repository archive not found: ${archive:-<empty>}" >&2
    exit 1
  fi

  require_cmd unzip
  local extract_root="$temp_root/extract"
  mkdir -p "$extract_root"
  unzip -q "$archive" -d "$extract_root"

  local io_root="$extract_root/io"
  if [[ ! -d "$io_root/agentscope" ]]; then
    local nested
    nested="$(find "$extract_root" -type d -name agentscope -path '*/io/agentscope' 2>/dev/null | head -n 1 || true)"
    if [[ -n "$nested" ]]; then
      io_root="$(cd "$(dirname "$nested")" && pwd)"
    fi
  fi

  if [[ ! -d "$io_root/agentscope" ]]; then
    echo "Archive does not contain an io/agentscope Maven repository tree." >&2
    exit 1
  fi

  mkdir -p "$MAVEN_REPOSITORY/io/agentscope"
  cp -R "$io_root/agentscope/." "$MAVEN_REPOSITORY/io/agentscope/"

  if ! agentscope_installed; then
    echo "AgentScope archive installed, but required artifacts for revision $REVISION are missing." >&2
    exit 1
  fi
  echo "[agentscope] Installed revision $REVISION into: $MAVEN_REPOSITORY"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --stop) STOP=1 ;;
    --backend-only) BACKEND_ONLY=1 ;;
    --frontend-only) FRONTEND_ONLY=1 ;;
    --skip-install) SKIP_INSTALL=1 ;;
    --skip-frontend-install) SKIP_FRONTEND_INSTALL=1 ;;
    --skip-demo-mcp) SKIP_DEMO_MCP=1 ;;
    --no-stop-existing) NO_STOP_EXISTING=1 ;;
    --skip-agentscope-bootstrap) SKIP_AGENTSCOPE_BOOTSTRAP=1 ;;
    --force-maven) FORCE_MAVEN=1 ;;
    --jar) BACKEND_JAR="$2"; shift ;;
    --workspace) WORKSPACE="$2"; shift ;;
    --maven-repository) MAVEN_REPOSITORY="$2"; shift ;;
    --agentscope-archive) AGENTSCOPE_ARCHIVE="$2"; shift ;;
    --agentscope-archive-url) AGENTSCOPE_ARCHIVE_URL="$2"; shift ;;
    --backend-port) BACKEND_PORT="$2"; shift ;;
    --frontend-port) FRONTEND_PORT="$2"; shift ;;
    --demo-mcp-http-port) DEMO_MCP_HTTP_PORT="$2"; shift ;;
    --demo-mcp-sse-port) DEMO_MCP_SSE_PORT="$2"; shift ;;
    --revision) REVISION="$2"; shift ;;
    --persistence-mode) PERSISTENCE_MODE="$2"; shift ;;
    --sqlite-url) SQLITE_URL="$2"; shift ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

if [[ "$STOP" -eq 1 ]]; then
  stop_platform_services
  exit 0
fi

resolve_java

BOOT_JAR=""
USE_JAR=0
if [[ "$FRONTEND_ONLY" -eq 0 ]]; then
  BOOT_JAR="$(find_boot_jar || true)"
  if [[ "$FORCE_MAVEN" -eq 0 && -n "$BOOT_JAR" ]]; then
    USE_JAR=1
  fi
  if [[ "$USE_JAR" -eq 0 ]]; then
    if ! command -v mvn >/dev/null 2>&1; then
      echo "Required command not found: mvn" >&2
      echo "No packaged JAR under target/ either." >&2
      echo "Install Maven, or copy a built agent-platform-*.jar into target/ and re-run." >&2
      echo "Example (Ubuntu/Debian): sudo apt update && sudo apt install -y maven" >&2
      exit 1
    fi
  fi
fi

if [[ -n "$WORKSPACE" ]]; then
  WORKSPACE_DIR="$(cd "$WORKSPACE" 2>/dev/null && pwd || true)"
  if [[ -z "$WORKSPACE_DIR" ]]; then
    mkdir -p "$WORKSPACE"
    WORKSPACE_DIR="$(cd "$WORKSPACE" && pwd)"
  fi
fi
MAVEN_REPOSITORY="$(mkdir -p "$MAVEN_REPOSITORY" && cd "$MAVEN_REPOSITORY" && pwd)"

mkdir -p "$WORKSPACE_DIR" "$LOG_DIR" "$RUNTIME_STATE_DIR"
: > "$PID_FILE"

export AGENT_PLATFORM_WORKSPACE="$WORKSPACE_DIR"
export AGENT_PLATFORM_PERSISTENCE_MODE="$(echo "$PERSISTENCE_MODE" | tr '[:upper:]' '[:lower:]')"
if [[ -n "$SQLITE_URL" ]]; then
  export AGENT_PLATFORM_SQLITE_URL="$SQLITE_URL"
fi

if [[ "$BACKEND_ONLY" -eq 0 ]]; then
  require_cmd node
  require_cmd npm
  if [[ ! -d "$FRONTEND_DIR" ]]; then
    echo "Frontend directory not found: $FRONTEND_DIR" >&2
    exit 1
  fi
fi

if [[ "$FRONTEND_ONLY" -eq 0 && "$SKIP_DEMO_MCP" -eq 0 ]]; then
  require_cmd node
  if [[ ! -f "$DEMO_MCP_SCRIPT" ]]; then
    echo "Demo MCP server script not found: $DEMO_MCP_SCRIPT" >&2
    exit 1
  fi
fi

if [[ "$NO_STOP_EXISTING" -eq 0 ]]; then
  if [[ "$FRONTEND_ONLY" -eq 0 ]]; then
    stop_port_listener "$BACKEND_PORT" "backend"
  fi
  if [[ "$BACKEND_ONLY" -eq 0 ]]; then
    stop_port_listener "$FRONTEND_PORT" "frontend"
  fi
  if [[ "$FRONTEND_ONLY" -eq 0 && "$SKIP_DEMO_MCP" -eq 0 ]]; then
    stop_port_listener "$DEMO_MCP_HTTP_PORT" "demo MCP streamable-http"
    stop_port_listener "$DEMO_MCP_SSE_PORT" "demo MCP sse"
  fi
fi

# AgentScope bootstrap is only needed when compiling/running via Maven.
if [[ "$FRONTEND_ONLY" -eq 0 && "$USE_JAR" -eq 0 && "$SKIP_INSTALL" -eq 0 ]]; then
  if ! agentscope_installed; then
    if [[ "$SKIP_AGENTSCOPE_BOOTSTRAP" -eq 1 ]]; then
      echo "AgentScope Maven artifacts are missing in $MAVEN_REPOSITORY and bootstrap was disabled." >&2
      exit 1
    fi
    if [[ -z "$AGENTSCOPE_ARCHIVE" && -z "$AGENTSCOPE_ARCHIVE_URL" ]]; then
      echo "AgentScope Maven artifacts are missing in $MAVEN_REPOSITORY." >&2
      echo "Provide --agentscope-archive or --agentscope-archive-url, or install the artifacts first." >&2
      exit 1
    fi
    echo "[platform] Bootstrapping AgentScope Maven artifacts..."
    bootstrap_agentscope
  else
    echo "[platform] AgentScope artifacts already installed; skipping bootstrap."
  fi
fi

if [[ "$BACKEND_ONLY" -eq 0 && "$SKIP_FRONTEND_INSTALL" -eq 0 ]]; then
  if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
    echo "[platform] Installing frontend dependencies..."
    (cd "$FRONTEND_DIR" && npm install)
  fi
fi

if [[ "$FRONTEND_ONLY" -eq 0 ]]; then
  if [[ "$SKIP_DEMO_MCP" -eq 0 ]]; then
    start_platform_process \
      "Demo MCP streamable-http :$DEMO_MCP_HTTP_PORT" \
      "$BACKEND_DIR" \
      "mcp-streamable-http.log" \
      node "mcp-servers/platform-demo/server.mjs" --transport streamable-http --port "$DEMO_MCP_HTTP_PORT"
    start_platform_process \
      "Demo MCP sse :$DEMO_MCP_SSE_PORT" \
      "$BACKEND_DIR" \
      "mcp-sse.log" \
      node "mcp-servers/platform-demo/server.mjs" --transport sse --port "$DEMO_MCP_SSE_PORT"
  fi

  if [[ "$USE_JAR" -eq 1 ]]; then
    echo "[platform] Backend mode: java -jar ($BOOT_JAR)"
    start_platform_process \
      "Agent Platform Backend :$BACKEND_PORT" \
      "$BACKEND_DIR" \
      "backend.log" \
      "$JAVA_BIN" "-Dserver.port=$BACKEND_PORT" -jar "$BOOT_JAR"
  else
    echo "[platform] Backend mode: mvn spring-boot:run"
    backend_args=(
      -Dmaven.repo.local="$MAVEN_REPOSITORY"
      -DskipTests
      -DAGENT_PLATFORM_PERSISTENCE_MODE="$AGENT_PLATFORM_PERSISTENCE_MODE"
      "-Dspring-boot.run.arguments=--server.port=$BACKEND_PORT"
      spring-boot:run
    )
    if [[ "$AGENT_PLATFORM_PERSISTENCE_MODE" == "sqlite" && -n "${AGENT_PLATFORM_SQLITE_URL:-}" ]]; then
      backend_args=(
        -Dmaven.repo.local="$MAVEN_REPOSITORY"
        -DskipTests
        -DAGENT_PLATFORM_PERSISTENCE_MODE="$AGENT_PLATFORM_PERSISTENCE_MODE"
        -DAGENT_PLATFORM_SQLITE_URL="$AGENT_PLATFORM_SQLITE_URL"
        "-Dspring-boot.run.arguments=--server.port=$BACKEND_PORT"
        spring-boot:run
      )
    fi
    start_platform_process \
      "Agent Platform Backend :$BACKEND_PORT" \
      "$BACKEND_DIR" \
      "backend.log" \
      env "JAVA_HOME=${JAVA_HOME:-}" "PATH=$(dirname "$JAVA_BIN"):$PATH" mvn "${backend_args[@]}"
  fi
fi

if [[ "$BACKEND_ONLY" -eq 0 ]]; then
  start_platform_process \
    "Agent Platform Frontend :$FRONTEND_PORT" \
    "$FRONTEND_DIR" \
    "frontend.log" \
    npm run dev -- --host 0.0.0.0 --port "$FRONTEND_PORT"
fi

echo
echo "[platform] Startup commands dispatched."
echo "[platform] Workspace: $WORKSPACE_DIR"
echo "[platform] PersistenceMode: $AGENT_PLATFORM_PERSISTENCE_MODE"
if [[ "$AGENT_PLATFORM_PERSISTENCE_MODE" == "sqlite" && -n "${AGENT_PLATFORM_SQLITE_URL:-}" ]]; then
  echo "[platform] SQLite URL: $AGENT_PLATFORM_SQLITE_URL"
fi
echo "[platform] Logs:     $LOG_DIR"
if [[ "$FRONTEND_ONLY" -eq 0 ]]; then
  echo "[platform] Backend:  http://127.0.0.1:$BACKEND_PORT"
fi
if [[ "$BACKEND_ONLY" -eq 0 ]]; then
  echo "[platform] Frontend: http://127.0.0.1:$FRONTEND_PORT/platform/live?domain=platform&org_id=platform&user_id=platform_admin"
fi
