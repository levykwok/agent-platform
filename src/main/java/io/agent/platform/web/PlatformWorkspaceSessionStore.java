/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.web;

import io.agent.platform.control.PlatformStorageLayer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PlatformWorkspaceSessionStore {

    private static final String DEFAULT_AGENT_ID = "platform_knowledge_agent";
    private static final String SESSION_TABLE = "agent_sessions";
    private static final String MESSAGE_TABLE = "session_messages";
    private static final String CONTEXT_TABLE = "session_contexts";
    private static final String TASK_TABLE = "session_tasks";

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkspaceManager workspaceManager;
    private final String tablePrefix;
    private final String sessionsTable;
    private final String messagesTable;
    private final String contextTable;
    private final String tasksTable;
    private final PlatformStorageLayer storage;

    public PlatformWorkspaceSessionStore(PlatformStorageLayer storage) {
        this.storage = storage;
        this.tablePrefix = storage.sqliteSessionTablePrefix();
        this.sessionsTable = this.tablePrefix + SESSION_TABLE;
        this.messagesTable = this.tablePrefix + MESSAGE_TABLE;
        this.contextTable = this.tablePrefix + CONTEXT_TABLE;
        this.tasksTable = this.tablePrefix + TASK_TABLE;
        this.workspaceManager = new WorkspaceManager(storage.resolveWorkspace());
        if (this.storage.isSqliteEnabled()) {
            initSqliteSchema();
        }
    }

    public Map<String, Object> create(Map<String, Object> payload, String orgId) {
        String agentId = string(payload.get("agent_id"), DEFAULT_AGENT_ID);
        String sessionId =
                string(
                        payload.get("session_id"),
                        "sess_" + UUID.randomUUID().toString().replace("-", ""));
        String title = string(payload.get("title"), "新对话");
        String userId = string(payload.get("user_id"), "platform_admin");
        if (!storage.isSqliteEnabled()) {
            workspaceManager.updateSessionIndex(
                    runtimeContext(sessionId, userId), agentId, sessionId, title);
        } else {
            ensureSqliteReady();
            upsertSession(agentId, sessionId, title, userId, orgId);
        }
        return sessionRow(agentId, sessionId, title, Instant.now().toString(), orgId);
    }

    public List<Map<String, Object>> list(String agentId, String orgId) {
        String resolvedAgentId = string(agentId, DEFAULT_AGENT_ID);
        if (!storage.isSqliteEnabled()) {
            return listFromFiles(resolvedAgentId, orgId);
        }
        ensureSqliteReady();
        return listFromSqlite(resolvedAgentId, orgId);
    }

    public Map<String, Object> get(String agentId, String sessionId, String orgId) {
        String resolvedAgentId = string(agentId, DEFAULT_AGENT_ID);
        if (!storage.isSqliteEnabled()) {
            Map<String, Object> session = findFileSession(resolvedAgentId, sessionId, orgId);
            String contextPath = sessionContextPath(resolvedAgentId, sessionId);
            String logPath = sessionLogPath(resolvedAgentId, sessionId);
            String tasksPath = taskPath(resolvedAgentId, sessionId);
            String contextRaw = readRaw(contextPath);
            String logRaw = readRaw(logPath);
            String tasksRaw = readRaw(tasksPath);
            return map(
                    "session",
                    session,
                    "messages",
                    readMessagesFromLog(logRaw),
                    "context_entries",
                    parseJsonLines(contextRaw),
                    "log_entries",
                    parseJsonLines(logRaw),
                    "tasks",
                    parseJsonObject(tasksRaw),
                    "memory",
                    map("memory_md", readRaw(WorkspaceConstants.MEMORY_MD)),
                    "raw",
                    map("context", contextRaw, "log", logRaw, "tasks", tasksRaw),
                    "files",
                    map(
                            "session_index",
                            sessionStorePath(resolvedAgentId),
                            "context",
                            contextPath,
                            "log",
                            logPath,
                            "tasks",
                            tasksPath,
                            "memory",
                            WorkspaceConstants.MEMORY_MD));
        }
        ensureSqliteReady();
        Map<String, Object> session = findSession(resolvedAgentId, sessionId, orgId);
        String contextRaw = readContextFromSqlite(resolvedAgentId, sessionId);
        String tasksRaw = readTasksFromSqlite(resolvedAgentId, sessionId);
        String memoryRaw = readMemoryFromWorkspace();
        List<Map<String, Object>> messages = listMessagesFromSqlite(resolvedAgentId, sessionId);
        String rawLog = encodeLogFromMessages(messages);
        List<Map<String, Object>> logEntries = logEntriesFromMessages(messages);
        return map(
                "session",
                session,
                "messages",
                messages,
                "context_entries",
                parseJsonLines(contextRaw),
                "log_entries",
                logEntries,
                "tasks",
                parseJsonObject(tasksRaw),
                "memory",
                map("memory_md", memoryRaw),
                "raw",
                map("context", contextRaw, "log", rawLog, "tasks", tasksRaw),
                "files",
                map(
                        "session_index",
                        agentSessionStoragePath(resolvedAgentId),
                        "context",
                        sessionContextPath(resolvedAgentId, sessionId),
                        "log",
                        sessionLogPath(resolvedAgentId, sessionId),
                        "tasks",
                        taskPath(resolvedAgentId, sessionId),
                        "memory",
                        WorkspaceConstants.MEMORY_MD));
    }

    public void appendMessage(
            String agentId, String sessionId, String userId, String role, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String resolvedAgentId = string(agentId, DEFAULT_AGENT_ID);
        String resolvedUserId = string(userId, "platform_admin");
        String text = content == null ? "" : content;
        if (!storage.isSqliteEnabled()) {
            RuntimeContext rc = runtimeContext(sessionId, resolvedUserId);
            workspaceManager.updateSessionIndex(
                    rc, resolvedAgentId, sessionId, text.isBlank() ? "新对话" : text);
            String line = encodeMessageLine(role, text);
            if (line == null) {
                return;
            }
            workspaceManager.appendUtf8WorkspaceRelative(
                    rc, sessionLogPath(resolvedAgentId, sessionId), line);
            return;
        }
        ensureSqliteReady();
        upsertMessage(resolvedAgentId, sessionId, role, text, resolvedUserId);
    }

    public void delete(String agentId, String sessionId) {
        String resolvedAgentId = string(agentId, DEFAULT_AGENT_ID);
        if (!storage.isSqliteEnabled()) {
            String rel = sessionStorePath(resolvedAgentId);
            String json =
                    workspaceManager.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), rel);
            try {
                JsonNode root = mapper.readTree(json);
                if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode objectRoot
                        && objectRoot.path("sessions")
                                instanceof
                                com.fasterxml.jackson.databind.node.ObjectNode sessions) {
                    sessions.remove(sessionId);
                    workspaceManager.writeUtf8WorkspaceRelative(
                            RuntimeContext.empty(),
                            rel,
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(objectRoot));
                }
            } catch (Exception ignored) {
                // best effort
            }
            workspaceManager.writeUtf8WorkspaceRelative(
                    runtimeContext(sessionId, "platform_admin"),
                    sessionLogPath(resolvedAgentId, sessionId),
                    "");
            return;
        }
        ensureSqliteReady();
        deleteSessionFromSqlite(resolvedAgentId, sessionId);
    }

    private List<Map<String, Object>> listFromFiles(String agentId, String orgId) {
        String rel = sessionStorePath(agentId);
        String json = workspaceManager.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), rel);
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            JsonNode sessions = mapper.readTree(json).path("sessions");
            sessions.fields()
                    .forEachRemaining(
                            entry -> {
                                JsonNode item = entry.getValue();
                                rows.add(
                                        sessionRow(
                                                agentId,
                                                entry.getKey(),
                                                item.path("summary").asText("新对话"),
                                                item.path("updatedAt").asText(""),
                                                orgId));
                            });
        } catch (Exception ignored) {
            return List.of();
        }
        rows.sort(
                Comparator.comparing(
                                (Map<String, Object> row) ->
                                        String.valueOf(row.getOrDefault("updated_at", "")))
                        .reversed());
        return rows;
    }

    private List<Map<String, Object>> listFromSqlite(String agentId, String orgId) {
        String query =
                "SELECT session_id, title, created_at, updated_at, user_id, domain, org_id "
                        + "FROM "
                        + sessionsTable
                        + " WHERE agent_id = ? ORDER BY updated_at DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, agentId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(
                            sessionRow(
                                    agentId,
                                    rs.getString("session_id"),
                                    rs.getString("title"),
                                    rs.getString("updated_at"),
                                    rs.getString("org_id")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list sessions for " + agentId, e);
        }
    }

    private Map<String, Object> findSession(String agentId, String sessionId, String orgId) {
        String query =
                "SELECT session_id, title, created_at, updated_at, user_id, domain, org_id "
                        + "FROM "
                        + sessionsTable
                        + " WHERE agent_id = ? AND session_id = ? LIMIT 1";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, agentId);
            statement.setString(2, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return sessionRow(
                            agentId,
                            rs.getString("session_id"),
                            rs.getString("title"),
                            rs.getString("updated_at"),
                            rs.getString("org_id"));
                }
            }
        } catch (SQLException e) {
            if (isMissingTable(e)) {
                initSqliteSchema();
                return findSessionAfterSchemaRetry(agentId, sessionId, orgId);
            }
            throw new IllegalStateException(
                    "Failed to fetch session " + sessionId + " for " + agentId, e);
        }
        return sessionRow(agentId, sessionId, "新对话", "", orgId);
    }

    private Map<String, Object> findSessionAfterSchemaRetry(
            String agentId, String sessionId, String orgId) {
        String query =
                "SELECT session_id, title, created_at, updated_at, user_id, domain, org_id "
                        + "FROM "
                        + sessionsTable
                        + " WHERE agent_id = ? AND session_id = ? LIMIT 1";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, agentId);
            statement.setString(2, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return sessionRow(
                            agentId,
                            rs.getString("session_id"),
                            rs.getString("title"),
                            rs.getString("updated_at"),
                            rs.getString("org_id"));
                }
            }
        } catch (SQLException retryError) {
            throw new IllegalStateException(
                    "Failed to fetch session " + sessionId + " for " + agentId, retryError);
        }
        return sessionRow(agentId, sessionId, "新对话", "", orgId);
    }

    private Map<String, Object> findFileSession(String agentId, String sessionId, String orgId) {
        String rel = sessionStorePath(agentId);
        String json = workspaceManager.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), rel);
        try {
            JsonNode item = mapper.readTree(json).path("sessions").path(sessionId);
            if (!item.isMissingNode() && !item.isNull()) {
                return sessionRow(
                        agentId,
                        sessionId,
                        item.path("summary").asText("新对话"),
                        item.path("updatedAt").asText(""),
                        orgId);
            }
        } catch (Exception ignored) {
            // Fall through to a default shell so old or missing files never break the UI.
        }
        return sessionRow(agentId, sessionId, "新对话", "", orgId);
    }

    private List<Map<String, Object>> listMessagesFromSqlite(String agentId, String sessionId) {
        String query =
                "SELECT role, content, created_at FROM "
                        + messagesTable
                        + " WHERE agent_id = ? AND session_id = ? ORDER BY id ASC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, agentId);
            statement.setString(2, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(
                            map(
                                    "role",
                                    rs.getString("role"),
                                    "content",
                                    rs.getString("content"),
                                    "created_at",
                                    rs.getString("created_at")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fetch messages for session " + sessionId, e);
        }
    }

    private String readContextFromSqlite(String agentId, String sessionId) {
        String query =
                "SELECT content FROM "
                        + contextTable
                        + " WHERE agent_id = ? AND session_id = ? LIMIT 1";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, agentId);
            statement.setString(2, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString("content");
                    return val == null ? "" : val;
                }
            }
            return "";
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read context for session " + sessionId, e);
        }
    }

    private String readTasksFromSqlite(String agentId, String sessionId) {
        String query =
                "SELECT content FROM "
                        + tasksTable
                        + " WHERE agent_id = ? AND session_id = ? LIMIT 1";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, agentId);
            statement.setString(2, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString("content");
                    return val == null ? "" : val;
                }
            }
            return "";
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read tasks for session " + sessionId, e);
        }
    }

    private String readMemoryFromWorkspace() {
        String text =
                workspaceManager.readManagedWorkspaceFileUtf8(
                        RuntimeContext.empty(), WorkspaceConstants.MEMORY_MD);
        return text == null ? "" : text;
    }

    private void upsertSession(
            String agentId, String sessionId, String title, String userId, String orgId) {
        String now = Instant.now().toString();
        String sql =
                "INSERT INTO "
                        + sessionsTable
                        + " (agent_id, session_id, user_id, title, domain, org_id, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(agent_id,"
                        + " session_id) DO UPDATE SET title = excluded.title, updated_at ="
                        + " excluded.updated_at";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, agentId);
            statement.setString(2, sessionId);
            statement.setString(3, userId);
            statement.setString(4, title);
            statement.setString(5, "platform");
            statement.setString(6, orgId);
            statement.setString(7, now);
            statement.setString(8, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert session " + sessionId, e);
        }
    }

    private void upsertMessage(
            String agentId, String sessionId, String role, String text, String userId) {
        String now = Instant.now().toString();
        String sql =
                "INSERT INTO "
                        + messagesTable
                        + " (agent_id, session_id, user_id, role, content, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, agentId);
            statement.setString(2, sessionId);
            statement.setString(3, userId);
            statement.setString(4, role);
            statement.setString(5, text);
            statement.setString(6, now);
            statement.executeUpdate();
            upsertSession(agentId, sessionId, text.isBlank() ? "新对话" : text, userId, "platform");
            maybeEnsureContextExists(agentId, sessionId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append message for session " + sessionId, e);
        }
    }

    private void maybeEnsureContextExists(String agentId, String sessionId) {
        String query =
                "INSERT INTO "
                        + contextTable
                        + " (agent_id, session_id, content) VALUES (?, ?, ?) "
                        + "ON CONFLICT(agent_id, session_id) DO NOTHING";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, agentId);
            statement.setString(2, sessionId);
            statement.setString(3, "");
            statement.executeUpdate();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    private void deleteSessionFromSqlite(String agentId, String sessionId) {
        String deleteMessages =
                "DELETE FROM " + messagesTable + " WHERE agent_id = ? AND session_id = ?";
        String deleteContext =
                "DELETE FROM " + contextTable + " WHERE agent_id = ? AND session_id = ?";
        String deleteTasks = "DELETE FROM " + tasksTable + " WHERE agent_id = ? AND session_id = ?";
        String deleteSession =
                "DELETE FROM " + sessionsTable + " WHERE agent_id = ? AND session_id = ?";
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ms = connection.prepareStatement(deleteMessages);
                    PreparedStatement cs = connection.prepareStatement(deleteContext);
                    PreparedStatement ts = connection.prepareStatement(deleteTasks);
                    PreparedStatement ss = connection.prepareStatement(deleteSession)) {
                ms.setString(1, agentId);
                ms.setString(2, sessionId);
                cs.setString(1, agentId);
                cs.setString(2, sessionId);
                ts.setString(1, agentId);
                ts.setString(2, sessionId);
                ss.setString(1, agentId);
                ss.setString(2, sessionId);

                ms.executeUpdate();
                cs.executeUpdate();
                ts.executeUpdate();
                ss.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete session " + sessionId, e);
        }
    }

    private String encodeMessageLine(String role, String text) {
        try {
            return mapper.writeValueAsString(
                            map(
                                    "role",
                                    role,
                                    "content",
                                    text,
                                    "created_at",
                                    Instant.now().toString()))
                    + "\n";
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<Map<String, Object>> readMessagesFromLog(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(line);
                rows.add(
                        map(
                                "role",
                                node.path("role").asText("assistant"),
                                "content",
                                node.path("content").asText(""),
                                "created_at",
                                node.path("created_at").asText("")));
            } catch (Exception ignored) {
                // ignore malformed lines
            }
        }
        return rows;
    }

    private List<Map<String, Object>> logEntriesFromMessages(List<Map<String, Object>> messages) {
        return messages.stream().map(message -> map("raw", message, "type", "message")).toList();
    }

    private String encodeLogFromMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> message : messages) {
            try {
                out.append(mapper.writeValueAsString(message)).append("\n");
            } catch (JsonProcessingException ignored) {
                out.append("{}\n");
            }
        }
        return out.toString();
    }

    private List<Map<String, Object>> parseJsonLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(line);
                rows.add(map("raw", node, "type", node.path("type").asText("entry")));
            } catch (Exception ignored) {
                rows.add(map("raw", line));
            }
        }
        return rows;
    }

    private Object parseJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(text, Map.class);
        } catch (Exception ignored) {
            return text;
        }
    }

    private String readRaw(String relativePath) {
        String text =
                workspaceManager.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), relativePath);
        return text == null ? "" : text;
    }

    private RuntimeContext runtimeContext(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    private static String sessionStorePath(String agentId) {
        return WorkspaceConstants.AGENTS_DIR
                + "/"
                + agentId
                + "/"
                + WorkspaceConstants.SESSIONS_DIR
                + "/"
                + WorkspaceConstants.SESSIONS_STORE;
    }

    private static String sessionLogPath(String agentId, String sessionId) {
        return WorkspaceConstants.AGENTS_DIR
                + "/"
                + agentId
                + "/"
                + WorkspaceConstants.SESSIONS_DIR
                + "/"
                + sessionId
                + WorkspaceConstants.SESSION_LOG_EXT;
    }

    private static String sessionContextPath(String agentId, String sessionId) {
        return WorkspaceConstants.AGENTS_DIR
                + "/"
                + agentId
                + "/"
                + WorkspaceConstants.SESSIONS_DIR
                + "/"
                + sessionId
                + WorkspaceConstants.SESSION_CONTEXT_EXT;
    }

    private static String taskPath(String agentId, String sessionId) {
        return WorkspaceConstants.AGENTS_DIR
                + "/"
                + agentId
                + "/"
                + WorkspaceConstants.TASKS_DIR
                + "/"
                + sessionId
                + ".json";
    }

    private String agentSessionStoragePath(String agentId) {
        return storage.agentStateDirectory(agentId).toString();
    }

    private void initSqliteSchema() {
        String createSessions =
                "CREATE TABLE IF NOT EXISTS "
                        + sessionsTable
                        + " (\n"
                        + "    agent_id TEXT NOT NULL,\n"
                        + "    session_id TEXT NOT NULL,\n"
                        + "    user_id TEXT,\n"
                        + "    title TEXT NOT NULL,\n"
                        + "    domain TEXT NOT NULL,\n"
                        + "    org_id TEXT,\n"
                        + "    created_at TEXT NOT NULL,\n"
                        + "    updated_at TEXT NOT NULL,\n"
                        + "    PRIMARY KEY (agent_id, session_id)\n"
                        + ")";
        String createMessages =
                "CREATE TABLE IF NOT EXISTS "
                        + messagesTable
                        + " (\n"
                        + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                        + "    agent_id TEXT NOT NULL,\n"
                        + "    session_id TEXT NOT NULL,\n"
                        + "    user_id TEXT,\n"
                        + "    role TEXT NOT NULL,\n"
                        + "    content TEXT NOT NULL,\n"
                        + "    created_at TEXT NOT NULL,\n"
                        + "    FOREIGN KEY (agent_id, session_id)\n"
                        + "        REFERENCES "
                        + sessionsTable
                        + " (agent_id, session_id) ON DELETE CASCADE"
                        + ")";
        String createContext =
                "CREATE TABLE IF NOT EXISTS "
                        + contextTable
                        + " (\n"
                        + "    agent_id TEXT NOT NULL,\n"
                        + "    session_id TEXT NOT NULL,\n"
                        + "    content TEXT NOT NULL,\n"
                        + "    PRIMARY KEY(agent_id, session_id)\n"
                        + ")";
        String createTasks =
                "CREATE TABLE IF NOT EXISTS "
                        + tasksTable
                        + " (\n"
                        + "    agent_id TEXT NOT NULL,\n"
                        + "    session_id TEXT NOT NULL,\n"
                        + "    content TEXT NOT NULL,\n"
                        + "    PRIMARY KEY(agent_id, session_id)\n"
                        + ")";
        try (Connection connection = storage.connection();
                Statement statement = connection.createStatement()) {
            statement.execute(createSessions);
            statement.execute(createMessages);
            statement.execute(createContext);
            statement.execute(createTasks);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to init sqlite session store", e);
        }
    }

    private void ensureSqliteReady() {
        if (storage.isSqliteEnabled()) {
            initSqliteSchema();
        }
    }

    private static Map<String, Object> sessionRow(
            String agentId, String sessionId, String title, String updatedAt, String orgId) {
        String now = Instant.now().toString();
        return map(
                "session_id",
                sessionId,
                "id",
                sessionId,
                "agent_id",
                agentId,
                "title",
                title == null || title.isBlank() ? "新对话" : title,
                "domain",
                "platform",
                "org_id",
                orgId,
                "created_at",
                now,
                "updated_at",
                updatedAt == null || updatedAt.isBlank() ? now : updatedAt);
    }

    private static String string(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static boolean isMissingTable(SQLException error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("no such table")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
