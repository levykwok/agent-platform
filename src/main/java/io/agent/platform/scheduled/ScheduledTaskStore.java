/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.scheduled;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.platform.control.PlatformStorageLayer;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Persistence adapter for scheduled tasks, execution history, and in-app notifications. */
@Component
public class ScheduledTaskStore {

    private static final String TASKS_TABLE = "platform_scheduled_tasks";
    private static final String RUNS_TABLE = "platform_scheduled_task_runs";
    private static final String NOTIFICATIONS_TABLE = "platform_user_notifications";

    private final PlatformStorageLayer storage;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    private final Map<String, Map<String, Object>> fileTasks = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> fileRuns = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> fileNotifications = new LinkedHashMap<>();

    public ScheduledTaskStore(PlatformStorageLayer storage) {
        this.storage = storage;
        this.file = storage.resolveWorkspace("cache", "scheduled-tasks.json");
    }

    @PostConstruct
    void initialize() {
        if (storage.isSqliteEnabled()) {
            storage.initializeSqliteSchema(
                    "CREATE TABLE IF NOT EXISTS "
                            + TASKS_TABLE
                            + " (task_id TEXT PRIMARY KEY, org_id TEXT NOT NULL, user_id TEXT NOT NULL,"
                            + " agent_id TEXT NOT NULL, session_id TEXT NOT NULL, name TEXT NOT NULL,"
                            + " prompt TEXT NOT NULL, cron_expression TEXT NOT NULL, timezone TEXT NOT NULL,"
                            + " status TEXT NOT NULL, next_run_at TEXT, last_run_at TEXT, lease_until TEXT,"
                            + " created_at TEXT NOT NULL, updated_at TEXT NOT NULL, payload TEXT NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS idx_platform_scheduled_tasks_due ON "
                            + TASKS_TABLE
                            + " (status, next_run_at, lease_until)",
                    "CREATE INDEX IF NOT EXISTS idx_platform_scheduled_tasks_owner ON "
                            + TASKS_TABLE
                            + " (org_id, user_id, updated_at)",
                    "CREATE TABLE IF NOT EXISTS "
                            + RUNS_TABLE
                            + " (run_id TEXT PRIMARY KEY, task_id TEXT NOT NULL, org_id TEXT NOT NULL,"
                            + " user_id TEXT NOT NULL, scheduled_at TEXT, started_at TEXT NOT NULL,"
                            + " finished_at TEXT, status TEXT NOT NULL, response_text TEXT, error_message TEXT,"
                            + " payload TEXT NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS idx_platform_scheduled_task_runs_task ON "
                            + RUNS_TABLE
                            + " (task_id, started_at DESC)",
                    "CREATE TABLE IF NOT EXISTS "
                            + NOTIFICATIONS_TABLE
                            + " (notification_id TEXT PRIMARY KEY, org_id TEXT NOT NULL, user_id TEXT NOT NULL,"
                            + " type TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, task_id TEXT,"
                            + " run_id TEXT, read_at TEXT, created_at TEXT NOT NULL, payload TEXT NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS idx_platform_user_notifications_owner ON "
                            + NOTIFICATIONS_TABLE
                            + " (org_id, user_id, read_at, created_at DESC)");
        } else {
            loadFile();
        }
    }

    public synchronized void createTask(Map<String, Object> task) {
        if (storage.isSqliteEnabled()) {
            writeTaskSqlite(task);
            return;
        }
        fileTasks.put(string(task.get("task_id")), new LinkedHashMap<>(task));
        persistFile();
    }

    public synchronized void updateTask(Map<String, Object> task) {
        createTask(task);
    }

    public Map<String, Object> findTask(String taskId, String userId, String orgId) {
        if (storage.isSqliteEnabled()) {
            String sql =
                    "SELECT * FROM "
                            + TASKS_TABLE
                            + " WHERE task_id = ? AND user_id = ? AND org_id = ? LIMIT 1";
            try (Connection connection = storage.connection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, taskId);
                statement.setString(2, userId);
                statement.setString(3, orgId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? taskFromResult(result) : null;
                }
            } catch (SQLException error) {
                throw failure("读取定时任务失败", error);
            }
        }
        synchronized (this) {
            Map<String, Object> row = fileTasks.get(taskId);
            return owned(row, userId, orgId) ? new LinkedHashMap<>(row) : null;
        }
    }

    public List<Map<String, Object>> listTasks(String userId, String orgId) {
        if (storage.isSqliteEnabled()) {
            String sql =
                    "SELECT * FROM "
                            + TASKS_TABLE
                            + " WHERE user_id = ? AND org_id = ? ORDER BY created_at DESC";
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Connection connection = storage.connection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, userId);
                statement.setString(2, orgId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(taskFromResult(result));
                    }
                }
                return rows;
            } catch (SQLException error) {
                throw failure("读取定时任务列表失败", error);
            }
        }
        synchronized (this) {
            return fileTasks.values().stream()
                    .filter(row -> owned(row, userId, orgId))
                    .<Map<String, Object>>map(row -> new LinkedHashMap<>(row))
                    .sorted(Comparator.comparing(row -> string(row.get("created_at")), Comparator.reverseOrder()))
                    .toList();
        }
    }

    /** Atomically leases due tasks so multiple scheduler threads do not execute the same row. */
    public List<Map<String, Object>> claimDueTasks(Instant now, int limit, long leaseMs) {
        String nowText = now.toString();
        String leaseUntil = now.plusMillis(Math.max(1000L, leaseMs)).toString();
        int safeLimit = Math.max(1, Math.min(200, limit));
        if (!storage.isSqliteEnabled()) {
            synchronized (this) {
                List<Map<String, Object>> claimed = new ArrayList<>();
                fileTasks.values().stream()
                        .filter(row -> "ACTIVE".equals(row.get("status")))
                        .filter(row -> due(row, nowText))
                        .filter(row -> !dueLease(row, nowText))
                        .sorted(Comparator.comparing(row -> string(row.get("next_run_at"))))
                        .limit(safeLimit)
                        .forEach(
                                row -> {
                                    row.put("lease_until", leaseUntil);
                                    row.put("updated_at", nowText);
                                    claimed.add(new LinkedHashMap<>(row));
                                });
                if (!claimed.isEmpty()) persistFile();
                return claimed;
            }
        }
        List<Map<String, Object>> claimed = new ArrayList<>();
        String query =
                "SELECT * FROM "
                        + TASKS_TABLE
                        + " WHERE status = 'ACTIVE' AND next_run_at IS NOT NULL"
                        + " AND next_run_at <= ? AND (lease_until IS NULL OR lease_until = '' OR lease_until <= ?)"
                        + " ORDER BY next_run_at LIMIT "
                        + safeLimit;
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement(query)) {
                select.setString(1, nowText);
                select.setString(2, nowText);
                try (ResultSet result = select.executeQuery()) {
                    List<Map<String, Object>> candidates = new ArrayList<>();
                    while (result.next()) candidates.add(taskFromResult(result));
                    String update =
                            "UPDATE "
                                    + TASKS_TABLE
                                    + " SET lease_until = ?, updated_at = ? WHERE task_id = ?"
                                    + " AND status = 'ACTIVE' AND (lease_until IS NULL OR lease_until = '' OR lease_until <= ?)";
                    try (PreparedStatement statement = connection.prepareStatement(update)) {
                        for (Map<String, Object> row : candidates) {
                            statement.setString(1, leaseUntil);
                            statement.setString(2, nowText);
                            statement.setString(3, string(row.get("task_id")));
                            statement.setString(4, nowText);
                            if (statement.executeUpdate() == 1) {
                                row.put("lease_until", leaseUntil);
                                claimed.add(row);
                            }
                        }
                    }
                }
            }
            connection.commit();
            connection.setAutoCommit(true);
            return claimed;
        } catch (SQLException error) {
            throw failure("领取到期定时任务失败", error);
        }
    }

    public void deleteTask(String taskId, String userId, String orgId) {
        if (storage.isSqliteEnabled()) {
            try (Connection connection = storage.connection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "DELETE FROM "
                                            + TASKS_TABLE
                                            + " WHERE task_id = ? AND user_id = ? AND org_id = ?")) {
                statement.setString(1, taskId);
                statement.setString(2, userId);
                statement.setString(3, orgId);
                statement.executeUpdate();
                return;
            } catch (SQLException error) {
                throw failure("删除定时任务失败", error);
            }
        }
        synchronized (this) {
            Map<String, Object> row = fileTasks.get(taskId);
            if (owned(row, userId, orgId)) {
                fileTasks.remove(taskId);
                persistFile();
            }
        }
    }

    public synchronized void createRun(Map<String, Object> run) {
        if (!storage.isSqliteEnabled()) {
            fileRuns.put(string(run.get("run_id")), new LinkedHashMap<>(run));
            persistFile();
            return;
        }
        String sql =
                "INSERT INTO "
                        + RUNS_TABLE
                        + " (run_id, task_id, org_id, user_id, scheduled_at, started_at, finished_at,"
                        + " status, response_text, error_message, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, string(run.get("run_id")));
            statement.setString(2, string(run.get("task_id")));
            statement.setString(3, string(run.get("org_id")));
            statement.setString(4, string(run.get("user_id")));
            statement.setString(5, nullable(run.get("scheduled_at")));
            statement.setString(6, string(run.get("started_at")));
            statement.setString(7, nullable(run.get("finished_at")));
            statement.setString(8, string(run.get("status")));
            statement.setString(9, nullable(run.get("response_text")));
            statement.setString(10, nullable(run.get("error_message")));
            statement.setString(11, json(run));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw failure("写入定时任务执行记录失败", error);
        }
    }

    public synchronized void updateRun(Map<String, Object> run) {
        if (!storage.isSqliteEnabled()) {
            fileRuns.put(string(run.get("run_id")), new LinkedHashMap<>(run));
            persistFile();
            return;
        }
        String sql =
                "UPDATE "
                        + RUNS_TABLE
                        + " SET finished_at = ?, status = ?, response_text = ?, error_message = ?, payload = ?"
                        + " WHERE run_id = ?";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nullable(run.get("finished_at")));
            statement.setString(2, string(run.get("status")));
            statement.setString(3, nullable(run.get("response_text")));
            statement.setString(4, nullable(run.get("error_message")));
            statement.setString(5, json(run));
            statement.setString(6, string(run.get("run_id")));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw failure("更新定时任务执行记录失败", error);
        }
    }

    public List<Map<String, Object>> listRuns(String taskId, String userId, String orgId, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        if (!storage.isSqliteEnabled()) {
            synchronized (this) {
                return fileRuns.values().stream()
                        .filter(row -> taskId.equals(row.get("task_id")))
                        .filter(row -> owned(row, userId, orgId))
                        .sorted(Comparator.comparing(row -> string(row.get("started_at")), Comparator.reverseOrder()))
                        .limit(safeLimit)
                        .<Map<String, Object>>map(row -> new LinkedHashMap<>(row))
                        .toList();
            }
        }
        String sql =
                "SELECT * FROM "
                        + RUNS_TABLE
                        + " WHERE task_id = ? AND user_id = ? AND org_id = ? ORDER BY started_at DESC LIMIT "
                        + safeLimit;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            statement.setString(2, userId);
            statement.setString(3, orgId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(runFromResult(result));
            }
            return rows;
        } catch (SQLException error) {
            throw failure("读取定时任务执行记录失败", error);
        }
    }

    public synchronized void createNotification(Map<String, Object> notification) {
        if (!storage.isSqliteEnabled()) {
            fileNotifications.put(string(notification.get("notification_id")), new LinkedHashMap<>(notification));
            persistFile();
            return;
        }
        String sql =
                "INSERT INTO "
                        + NOTIFICATIONS_TABLE
                        + " (notification_id, org_id, user_id, type, title, body, task_id, run_id, read_at, created_at, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, string(notification.get("notification_id")));
            statement.setString(2, string(notification.get("org_id")));
            statement.setString(3, string(notification.get("user_id")));
            statement.setString(4, string(notification.get("type")));
            statement.setString(5, string(notification.get("title")));
            statement.setString(6, string(notification.get("body")));
            statement.setString(7, nullable(notification.get("task_id")));
            statement.setString(8, nullable(notification.get("run_id")));
            statement.setString(9, nullable(notification.get("read_at")));
            statement.setString(10, string(notification.get("created_at")));
            statement.setString(11, json(notification));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw failure("写入站内通知失败", error);
        }
    }

    public List<Map<String, Object>> listNotifications(
            String userId, String orgId, boolean unreadOnly, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        if (!storage.isSqliteEnabled()) {
            synchronized (this) {
                return fileNotifications.values().stream()
                        .filter(row -> owned(row, userId, orgId))
                        .filter(row -> !unreadOnly || string(row.get("read_at")).isBlank())
                        .sorted(Comparator.comparing(row -> string(row.get("created_at")), Comparator.reverseOrder()))
                        .limit(safeLimit)
                        .<Map<String, Object>>map(row -> new LinkedHashMap<>(row))
                        .toList();
            }
        }
        String sql =
                "SELECT * FROM "
                        + NOTIFICATIONS_TABLE
                        + " WHERE user_id = ? AND org_id = "
                        + "?"
                        + (unreadOnly ? " AND (read_at IS NULL OR read_at = '')" : "")
                        + " ORDER BY created_at DESC LIMIT "
                        + safeLimit;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, orgId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(notificationFromResult(result));
            }
            return rows;
        } catch (SQLException error) {
            throw failure("读取站内通知失败", error);
        }
    }

    public int unreadCount(String userId, String orgId) {
        if (!storage.isSqliteEnabled()) {
            return listNotifications(userId, orgId, true, 2000).size();
        }
        String sql =
                "SELECT COUNT(*) FROM "
                        + NOTIFICATIONS_TABLE
                        + " WHERE user_id = ? AND org_id = ? AND (read_at IS NULL OR read_at = '')";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, orgId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException error) {
            throw failure("读取未读通知数量失败", error);
        }
    }

    public void markNotificationRead(String notificationId, String userId, String orgId) {
        String now = Instant.now().toString();
        if (!storage.isSqliteEnabled()) {
            synchronized (this) {
                Map<String, Object> row = fileNotifications.get(notificationId);
                if (owned(row, userId, orgId)) {
                    row.put("read_at", now);
                    persistFile();
                }
            }
            return;
        }
        updateReadAt(notificationId, userId, orgId, now);
    }

    public void markAllNotificationsRead(String userId, String orgId) {
        String now = Instant.now().toString();
        if (!storage.isSqliteEnabled()) {
            synchronized (this) {
                fileNotifications.values().stream()
                        .filter(row -> owned(row, userId, orgId))
                        .forEach(row -> row.put("read_at", now));
                persistFile();
            }
            return;
        }
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + NOTIFICATIONS_TABLE
                                        + " SET read_at = ? WHERE user_id = ? AND org_id = ?"
                                        + " AND (read_at IS NULL OR read_at = '')")) {
            statement.setString(1, now);
            statement.setString(2, userId);
            statement.setString(3, orgId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw failure("标记通知已读失败", error);
        }
    }

    private void writeTaskSqlite(Map<String, Object> task) {
        String sql =
                "INSERT INTO "
                        + TASKS_TABLE
                        + " (task_id, org_id, user_id, agent_id, session_id, name, prompt, cron_expression, timezone,"
                        + " status, next_run_at, last_run_at, lease_until, created_at, updated_at, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(task_id) DO UPDATE SET org_id=excluded.org_id, user_id=excluded.user_id,"
                        + " agent_id=excluded.agent_id, session_id=excluded.session_id, name=excluded.name, prompt=excluded.prompt,"
                        + " cron_expression=excluded.cron_expression, timezone=excluded.timezone, status=excluded.status,"
                        + " next_run_at=excluded.next_run_at, last_run_at=excluded.last_run_at, lease_until=excluded.lease_until,"
                        + " updated_at=excluded.updated_at, payload=excluded.payload";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, string(task.get("task_id")));
            statement.setString(2, string(task.get("org_id")));
            statement.setString(3, string(task.get("user_id")));
            statement.setString(4, string(task.get("agent_id")));
            statement.setString(5, string(task.get("session_id")));
            statement.setString(6, string(task.get("name")));
            statement.setString(7, string(task.get("prompt")));
            statement.setString(8, string(task.get("cron_expression")));
            statement.setString(9, string(task.get("timezone")));
            statement.setString(10, string(task.get("status")));
            statement.setString(11, nullable(task.get("next_run_at")));
            statement.setString(12, nullable(task.get("last_run_at")));
            statement.setString(13, nullable(task.get("lease_until")));
            statement.setString(14, string(task.get("created_at")));
            statement.setString(15, string(task.get("updated_at")));
            statement.setString(16, json(task));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw failure("保存定时任务失败", error);
        }
    }

    private Map<String, Object> taskFromResult(ResultSet result) throws SQLException {
        Map<String, Object> row = mapFromJson(result.getString("payload"));
        row.put("task_id", result.getString("task_id"));
        row.put("org_id", result.getString("org_id"));
        row.put("user_id", result.getString("user_id"));
        row.put("agent_id", result.getString("agent_id"));
        row.put("session_id", result.getString("session_id"));
        row.put("name", result.getString("name"));
        row.put("prompt", result.getString("prompt"));
        row.put("cron_expression", result.getString("cron_expression"));
        row.put("timezone", result.getString("timezone"));
        row.put("status", result.getString("status"));
        row.put("next_run_at", result.getString("next_run_at"));
        row.put("last_run_at", result.getString("last_run_at"));
        row.put("lease_until", result.getString("lease_until"));
        row.put("created_at", result.getString("created_at"));
        row.put("updated_at", result.getString("updated_at"));
        return row;
    }

    private Map<String, Object> runFromResult(ResultSet result) throws SQLException {
        Map<String, Object> row = mapFromJson(result.getString("payload"));
        row.put("run_id", result.getString("run_id"));
        row.put("task_id", result.getString("task_id"));
        row.put("org_id", result.getString("org_id"));
        row.put("user_id", result.getString("user_id"));
        row.put("scheduled_at", result.getString("scheduled_at"));
        row.put("started_at", result.getString("started_at"));
        row.put("finished_at", result.getString("finished_at"));
        row.put("status", result.getString("status"));
        row.put("response_text", result.getString("response_text"));
        row.put("error_message", result.getString("error_message"));
        return row;
    }

    private Map<String, Object> notificationFromResult(ResultSet result) throws SQLException {
        Map<String, Object> row = mapFromJson(result.getString("payload"));
        row.put("notification_id", result.getString("notification_id"));
        row.put("org_id", result.getString("org_id"));
        row.put("user_id", result.getString("user_id"));
        row.put("type", result.getString("type"));
        row.put("title", result.getString("title"));
        row.put("body", result.getString("body"));
        row.put("task_id", result.getString("task_id"));
        row.put("run_id", result.getString("run_id"));
        row.put("read_at", result.getString("read_at"));
        row.put("created_at", result.getString("created_at"));
        return row;
    }

    private void updateReadAt(String id, String userId, String orgId, String readAt) {
        try (Connection connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + NOTIFICATIONS_TABLE
                                        + " SET read_at = ? WHERE notification_id = ? AND user_id = ? AND org_id = ?")) {
            statement.setString(1, readAt);
            statement.setString(2, id);
            statement.setString(3, userId);
            statement.setString(4, orgId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw failure("标记通知已读失败", error);
        }
    }

    private void loadFile() {
        synchronized (this) {
            try {
                if (!Files.exists(file)) return;
                Map<String, Object> root =
                        mapper.readValue(Files.readString(file), new TypeReference<>() {});
                loadRows(root.get("tasks"), fileTasks);
                loadRows(root.get("runs"), fileRuns);
                loadRows(root.get("notifications"), fileNotifications);
            } catch (IOException error) {
                throw new IllegalStateException("读取定时任务文件存储失败: " + file, error);
            }
        }
    }

    private void persistFile() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temp.toFile(), map("tasks", fileTasks, "runs", fileRuns, "notifications", fileNotifications));
            Files.move(
                    temp,
                    file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveError) {
            try {
                Files.move(
                        file.resolveSibling(file.getFileName() + ".tmp"),
                        file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) {
                throw new IllegalStateException("写入定时任务文件存储失败: " + file, error);
            }
        }
    }

    private void loadRows(Object value, Map<String, Map<String, Object>> target) {
        if (!(value instanceof List<?> rows)) return;
        for (Object valueRow : rows) {
            if (!(valueRow instanceof Map<?, ?> raw)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            raw.forEach((key, item) -> row.put(String.valueOf(key), item));
            String id = string(row.get("task_id"));
            if (target == fileRuns) id = string(row.get("run_id"));
            if (target == fileNotifications) id = string(row.get("notification_id"));
            if (!id.isBlank()) target.put(id, row);
        }
    }

    private boolean due(Map<String, Object> row, String now) {
        String next = string(row.get("next_run_at"));
        return !next.isBlank() && next.compareTo(now) <= 0;
    }

    private boolean dueLease(Map<String, Object> row, String now) {
        String lease = string(row.get("lease_until"));
        return !lease.isBlank() && lease.compareTo(now) > 0;
    }

    private boolean owned(Map<String, Object> row, String userId, String orgId) {
        return row != null
                && userId.equals(string(row.get("user_id")))
                && orgId.equals(string(row.get("org_id")));
    }

    private String json(Map<String, Object> row) {
        try {
            return mapper.writeValueAsString(row);
        } catch (IOException error) {
            throw new IllegalStateException("序列化定时任务数据失败", error);
        }
    }

    private Map<String, Object> mapFromJson(String value) {
        try {
            return value == null || value.isBlank()
                    ? new LinkedHashMap<>()
                    : mapper.readValue(value, new TypeReference<>() {});
        } catch (IOException error) {
            return new LinkedHashMap<>();
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nullable(Object value) {
        String text = string(value);
        return text.isBlank() ? null : text;
    }

    private static IllegalStateException failure(String message, SQLException error) {
        return new IllegalStateException(message, error);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
