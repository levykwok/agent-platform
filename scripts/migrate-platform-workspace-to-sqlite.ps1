param(
    [string]$Workspace = "",
    [string]$DbPath = "",
    [switch]$KeepOriginals
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Workspace -or -not $Workspace.Trim()) {
    $Workspace = Join-Path $RepoRoot "workspace"
}
$Workspace = [System.IO.Path]::GetFullPath($Workspace)
if (-not $DbPath -or -not $DbPath.Trim()) {
    $DbPath = Join-Path $Workspace "platform-platform.db"
}
$DbPath = [System.IO.Path]::GetFullPath($DbPath)

if (-not (Test-Path $Workspace)) {
    throw "Workspace does not exist: $Workspace"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BackupDir = Join-Path $Workspace "backup\pre-sqlite-$stamp"
New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null

$python = @'
import json
import os
import shutil
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

workspace = Path(sys.argv[1]).resolve()
db_path = Path(sys.argv[2]).resolve()
backup_dir = Path(sys.argv[3]).resolve()
keep_originals = sys.argv[4].lower() == "true"

now = datetime.now(timezone.utc).isoformat()
db_path.parent.mkdir(parents=True, exist_ok=True)
backup_dir.mkdir(parents=True, exist_ok=True)

CONFIG_FILES = [
    "models.yml",
    "providers.yml",
    "agents.yml",
    "tools.yml",
    "mcps.yml",
    "skills.yml",
]

def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")

def backup_file(path: Path):
    if not path.exists():
        return
    rel = path.relative_to(workspace)
    target = backup_dir / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)
    if not keep_originals:
        path.unlink()

def load_json(path: Path):
    if not path.exists():
        return None
    return json.loads(read_text(path))

conn = sqlite3.connect(str(db_path))
try:
    cur = conn.cursor()
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_config (
            config_key TEXT PRIMARY KEY,
            content TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_domains (
            domain_id TEXT PRIMARY KEY,
            payload TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_skill_packages (
            package_id TEXT PRIMARY KEY,
            payload TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_model_slots (
            slot_key TEXT PRIMARY KEY,
            payload TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_model_aliases (
            alias_id TEXT PRIMARY KEY,
            payload TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_mcp_discovered_tools (
            server_id TEXT PRIMARY KEY,
            probe_payload TEXT NOT NULL,
            tools_payload TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_agent_sessions (
            agent_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            user_id TEXT,
            title TEXT NOT NULL,
            domain TEXT NOT NULL,
            org_id TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (agent_id, session_id)
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_session_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            agent_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            user_id TEXT,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_session_contexts (
            agent_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            content TEXT NOT NULL,
            PRIMARY KEY(agent_id, session_id)
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_session_tasks (
            agent_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            content TEXT NOT NULL,
            PRIMARY KEY(agent_id, session_id)
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS platform_migration_history (
            migration_key TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            target TEXT NOT NULL,
            status TEXT NOT NULL,
            message TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )

    migrated = []
    for name in CONFIG_FILES:
        path = workspace / name
        if not path.exists():
            continue
        content = read_text(path)
        if not content.strip():
            continue
        cur.execute(
            """
            INSERT INTO platform_config(config_key, content, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(config_key) DO UPDATE SET
                content = excluded.content,
                updated_at = excluded.updated_at
            """,
            (name, content, now),
        )
        backup_file(path)
        migrated.append(f"platform_config:{name}")

    domains = load_json(workspace / "cache" / "domains.json")
    if isinstance(domains, dict):
        for row in domains.get("domains", []):
            key = str(row.get("domain") or row.get("domain_id") or "").strip()
            if key:
                cur.execute(
                    """
                    INSERT INTO platform_domains(domain_id, payload, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(domain_id) DO UPDATE SET
                        payload = excluded.payload,
                        updated_at = excluded.updated_at
                    """,
                    (key, json.dumps(row, ensure_ascii=False), now),
                )
        backup_file(workspace / "cache" / "domains.json")
        migrated.append("platform_domains")

    packages = load_json(workspace / "cache" / "skill-packages.json")
    if isinstance(packages, dict):
        for row in packages.get("packages", []):
            key = str(row.get("id") or row.get("package_id") or "").strip()
            if key:
                cur.execute(
                    """
                    INSERT INTO platform_skill_packages(package_id, payload, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(package_id) DO UPDATE SET
                        payload = excluded.payload,
                        updated_at = excluded.updated_at
                    """,
                    (key, json.dumps(row, ensure_ascii=False), now),
                )
        backup_file(workspace / "cache" / "skill-packages.json")
        migrated.append("platform_skill_packages")

    slots = load_json(workspace / "cache" / "model-slots.json")
    if isinstance(slots, dict):
        for row in slots.get("slot_bindings", []):
            key = str(row.get("slot_key") or "").strip()
            if key:
                cur.execute(
                    """
                    INSERT INTO platform_model_slots(slot_key, payload, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(slot_key) DO UPDATE SET
                        payload = excluded.payload,
                        updated_at = excluded.updated_at
                    """,
                    (key, json.dumps(row, ensure_ascii=False), now),
                )
        for row in slots.get("aliases", []):
            key = str(row.get("id") or row.get("alias_id") or "").strip()
            if key:
                cur.execute(
                    """
                    INSERT INTO platform_model_aliases(alias_id, payload, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(alias_id) DO UPDATE SET
                        payload = excluded.payload,
                        updated_at = excluded.updated_at
                    """,
                    (key, json.dumps(row, ensure_ascii=False), now),
                )
        backup_file(workspace / "cache" / "model-slots.json")
        migrated.append("platform_model_slots/model_aliases")

    discovery = load_json(workspace / "cache" / "mcp-discovery.json")
    if isinstance(discovery, dict):
        servers = discovery.get("servers", {})
        if isinstance(servers, dict):
            for server_id, payload in servers.items():
                if not isinstance(payload, dict):
                    continue
                cur.execute(
                    """
                    INSERT INTO platform_mcp_discovered_tools(
                        server_id, probe_payload, tools_payload, updated_at
                    )
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(server_id) DO UPDATE SET
                        probe_payload = excluded.probe_payload,
                        tools_payload = excluded.tools_payload,
                        updated_at = excluded.updated_at
                    """,
                    (
                        str(server_id),
                        json.dumps(payload.get("probe", {}), ensure_ascii=False),
                        json.dumps(payload.get("tools", []), ensure_ascii=False),
                        now,
                    ),
                )
        backup_file(workspace / "cache" / "mcp-discovery.json")
        migrated.append("platform_mcp_discovered_tools")

    agents_root = workspace / "agents"
    if agents_root.exists():
        for sessions_file in agents_root.glob("*/sessions/sessions.json"):
            agent_id = sessions_file.parent.parent.name
            try:
                root = json.loads(read_text(sessions_file))
            except Exception:
                continue
            sessions = root.get("sessions", {})
            if not isinstance(sessions, dict):
                continue
            for session_id, item in sessions.items():
                if not isinstance(item, dict):
                    item = {}
                title = item.get("summary") or "New chat"
                updated_at = item.get("updatedAt") or now
                created_at = item.get("createdAt") or updated_at
                user_id = item.get("userId") or "platform_admin"
                cur.execute(
                    """
                    INSERT INTO platform_agent_sessions(
                        agent_id, session_id, user_id, title, domain, org_id, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(agent_id, session_id) DO UPDATE SET
                        title = excluded.title,
                        updated_at = excluded.updated_at
                    """,
                    (agent_id, session_id, user_id, title, "platform", "platform", created_at, updated_at),
                )
                log_path = sessions_file.parent / f"{session_id}.jsonl"
                if log_path.exists():
                    cur.execute(
                        "DELETE FROM platform_session_messages WHERE agent_id = ? AND session_id = ?",
                        (agent_id, session_id),
                    )
                    for line in read_text(log_path).splitlines():
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            message = json.loads(line)
                        except Exception:
                            continue
                        cur.execute(
                            """
                            INSERT INTO platform_session_messages(
                                agent_id, session_id, user_id, role, content, created_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                            (
                                agent_id,
                                session_id,
                                user_id,
                                str(message.get("role") or "assistant"),
                                str(message.get("content") or ""),
                                str(message.get("created_at") or updated_at),
                            ),
                        )
                context_path = sessions_file.parent / f"{session_id}.context.jsonl"
                if context_path.exists():
                    cur.execute(
                        """
                        INSERT INTO platform_session_contexts(agent_id, session_id, content)
                        VALUES (?, ?, ?)
                        ON CONFLICT(agent_id, session_id) DO UPDATE SET content = excluded.content
                        """,
                        (agent_id, session_id, read_text(context_path)),
                    )
                task_path = sessions_file.parent.parent / "tasks" / f"{session_id}.json"
                if task_path.exists():
                    cur.execute(
                        """
                        INSERT INTO platform_session_tasks(agent_id, session_id, content)
                        VALUES (?, ?, ?)
                        ON CONFLICT(agent_id, session_id) DO UPDATE SET content = excluded.content
                        """,
                        (agent_id, session_id, read_text(task_path)),
                    )
        migrated.append("platform_agent_sessions/session_messages/session_contexts/session_tasks")

    cur.execute(
        """
        INSERT INTO platform_migration_history(
            migration_key, source, target, status, message, created_at, updated_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(migration_key) DO UPDATE SET
            status = excluded.status,
            message = excluded.message,
            updated_at = excluded.updated_at
        """,
        (
            "workspace-to-sqlite-" + now,
            str(workspace),
            str(db_path),
            "completed",
            ", ".join(migrated),
            now,
            now,
        ),
    )

    conn.commit()
finally:
    conn.close()

print("SQLite:", db_path)
print("Backup:", backup_dir)
for item in migrated:
    print("Migrated:", item)
'@

$temp = Join-Path $env:TEMP "platform-workspace-to-sqlite-$stamp.py"
Set-Content -Path $temp -Value $python -Encoding UTF8
try {
    python $temp $Workspace $DbPath $BackupDir ([string]$KeepOriginals.IsPresent)
} finally {
    Remove-Item $temp -Force -ErrorAction SilentlyContinue
}
