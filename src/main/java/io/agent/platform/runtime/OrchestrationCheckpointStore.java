/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.platform.control.PlatformStorageLayer;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Durable run leases and safe-point checkpoints shared by every platform instance. */
@Component
public class OrchestrationCheckpointStore {

    static final String RUNS_TABLE = "platform_orchestration_runs";
    static final String CHECKPOINTS_TABLE = "platform_orchestration_checkpoints";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final PlatformStorageLayer storage;
    private final String instanceId;
    private final long leaseMs;
    private final Map<String, MutableRun> memoryRuns = new ConcurrentHashMap<>();
    private final Map<String, Checkpoint> memoryCheckpoints = new ConcurrentHashMap<>();

    public OrchestrationCheckpointStore(
            PlatformStorageLayer storage,
            @Value("${agent.platform.orchestration.instance-id:}") String configuredInstanceId,
            @Value("${agent.platform.orchestration.lease-ms:30000}") long leaseMs) {
        this.storage = storage;
        this.instanceId =
                configuredInstanceId == null || configuredInstanceId.isBlank()
                        ? "instance_" + UUID.randomUUID().toString().replace("-", "")
                        : configuredInstanceId.strip();
        this.leaseMs = Math.max(5_000L, leaseMs);
    }

    @PostConstruct
    public void initialize() {
        storage.initializeSqliteSchema(
                "CREATE TABLE IF NOT EXISTS "
                        + RUNS_TABLE
                        + " (run_id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, lease_until INTEGER NOT NULL,"
                        + " status TEXT NOT NULL, request_json TEXT NOT NULL, version INTEGER NOT NULL,"
                        + " updated_at TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_platform_orchestration_runs_recovery ON "
                        + RUNS_TABLE
                        + " (status, lease_until)",
                "CREATE TABLE IF NOT EXISTS "
                        + CHECKPOINTS_TABLE
                        + " (run_id TEXT NOT NULL, scope_key TEXT NOT NULL, phase TEXT NOT NULL,"
                        + " payload TEXT NOT NULL, version INTEGER NOT NULL, updated_at TEXT NOT NULL,"
                        + " PRIMARY KEY (run_id, scope_key))");
    }

    public String instanceId() {
        return instanceId;
    }

    public long leaseMs() {
        return leaseMs;
    }

    public void register(String runId, Map<String, Object> request) {
        if (blank(runId)) return;
        long now = System.currentTimeMillis();
        if (!storage.isSqliteEnabled()) {
            memoryRuns.putIfAbsent(
                    runId,
                    new MutableRun(
                            runId,
                            instanceId,
                            now + leaseMs,
                            "RUNNING",
                            immutable(request),
                            1));
            return;
        }
        String sql =
                "INSERT INTO "
                        + RUNS_TABLE
                        + " (run_id, owner_id, lease_until, status, request_json, version, updated_at)"
                        + " VALUES (?, ?, ?, 'RUNNING', ?, 1, ?)"
                        + " ON CONFLICT(run_id) DO NOTHING";
        execute(
                sql,
                statement -> {
                    statement.setString(1, runId);
                    statement.setString(2, instanceId);
                    statement.setLong(3, now + leaseMs);
                    statement.setString(4, json(request));
                    statement.setString(5, Instant.now().toString());
                });
    }

    public boolean acquire(String runId) {
        if (blank(runId)) return false;
        long now = System.currentTimeMillis();
        if (!storage.isSqliteEnabled()) {
            MutableRun run = memoryRuns.get(runId);
            if (run == null || !run.recoverable(now, instanceId)) return false;
            synchronized (run) {
                if (!run.recoverable(now, instanceId)) return false;
                run.ownerId = instanceId;
                run.leaseUntil = now + leaseMs;
                run.status = "RUNNING";
                run.version++;
                return true;
            }
        }
        String sql =
                "UPDATE "
                        + RUNS_TABLE
                        + " SET owner_id=?, lease_until=?, status='RUNNING', version=version+1, updated_at=?"
                        + " WHERE run_id=? AND status IN ('RUNNING','RECOVERING')"
                        + " AND (owner_id=? OR lease_until<?)";
        return execute(
                        sql,
                        statement -> {
                            statement.setString(1, instanceId);
                            statement.setLong(2, now + leaseMs);
                            statement.setString(3, Instant.now().toString());
                            statement.setString(4, runId);
                            statement.setString(5, instanceId);
                            statement.setLong(6, now);
                        })
                == 1;
    }

    public boolean renew(String runId) {
        if (blank(runId)) return false;
        long now = System.currentTimeMillis();
        if (!storage.isSqliteEnabled()) {
            MutableRun run = memoryRuns.get(runId);
            if (run == null || !instanceId.equals(run.ownerId) || !"RUNNING".equals(run.status)) {
                return false;
            }
            run.leaseUntil = now + leaseMs;
            return true;
        }
        String sql =
                "UPDATE "
                        + RUNS_TABLE
                        + " SET lease_until=?, updated_at=? WHERE run_id=? AND owner_id=? AND status='RUNNING'";
        return execute(
                        sql,
                        statement -> {
                            statement.setLong(1, now + leaseMs);
                            statement.setString(2, Instant.now().toString());
                            statement.setString(3, runId);
                            statement.setString(4, instanceId);
                        })
                == 1;
    }

    public boolean save(String runId, String scopeKey, String phase, Map<String, Object> payload) {
        if (blank(runId) || blank(scopeKey)) return false;
        if (!storage.isSqliteEnabled()) {
            MutableRun run = memoryRuns.get(runId);
            long now = System.currentTimeMillis();
            if (run == null) return false;
            synchronized (run) {
                if (!instanceId.equals(run.ownerId)
                        || !"RUNNING".equals(run.status)
                        || run.leaseUntil < now) {
                    return false;
                }
            }
            String key = checkpointKey(runId, scopeKey);
            memoryCheckpoints.compute(
                    key,
                    (ignored, existing) ->
                            new Checkpoint(
                                    runId,
                                    scopeKey,
                                    safe(phase),
                                    immutable(payload),
                                    existing == null ? 1 : existing.version() + 1,
                                    Instant.now().toString()));
            return true;
        }
        String sql =
                "INSERT INTO "
                        + CHECKPOINTS_TABLE
                        + " (run_id, scope_key, phase, payload, version, updated_at)"
                        + " SELECT ?, ?, ?, ?, 1, ? FROM "
                        + RUNS_TABLE
                        + " WHERE run_id=? AND owner_id=? AND status='RUNNING' AND lease_until>=?"
                        + " ON CONFLICT(run_id, scope_key) DO UPDATE SET phase=excluded.phase,"
                        + " payload=excluded.payload, version="
                        + CHECKPOINTS_TABLE
                        + ".version+1, updated_at=excluded.updated_at";
        return execute(
                sql,
                statement -> {
                    statement.setString(1, runId);
                    statement.setString(2, scopeKey);
                    statement.setString(3, safe(phase));
                    statement.setString(4, json(payload));
                    statement.setString(5, Instant.now().toString());
                    statement.setString(6, runId);
                    statement.setString(7, instanceId);
                    statement.setLong(8, System.currentTimeMillis());
                })
                == 1;
    }

    public Optional<Checkpoint> load(String runId, String scopeKey) {
        if (blank(runId) || blank(scopeKey)) return Optional.empty();
        if (!storage.isSqliteEnabled()) {
            return Optional.ofNullable(memoryCheckpoints.get(checkpointKey(runId, scopeKey)));
        }
        String sql =
                "SELECT phase, payload, version, updated_at FROM "
                        + CHECKPOINTS_TABLE
                        + " WHERE run_id=? AND scope_key=?";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, scopeKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(
                        new Checkpoint(
                                runId,
                                scopeKey,
                                rows.getString("phase"),
                                map(rows.getString("payload")),
                                rows.getLong("version"),
                                rows.getString("updated_at")));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Load orchestration checkpoint failed", error);
        }
    }

    public void clear(String runId) {
        if (blank(runId)) return;
        if (!storage.isSqliteEnabled()) {
            memoryCheckpoints.keySet().removeIf(key -> key.startsWith(runId + "\u0000"));
            return;
        }
        execute(
                "DELETE FROM " + CHECKPOINTS_TABLE + " WHERE run_id=?",
                statement -> statement.setString(1, runId));
    }

    public boolean requestCancel(String runId) {
        if (blank(runId)) return false;
        if (!storage.isSqliteEnabled()) {
            MutableRun run = memoryRuns.get(runId);
            if (run == null) return false;
            synchronized (run) {
                if (!("RUNNING".equals(run.status) || "RECOVERING".equals(run.status))) {
                    return false;
                }
                run.status = "CANCEL_REQUESTED";
                run.version++;
                return true;
            }
        }
        return execute(
                        "UPDATE "
                                + RUNS_TABLE
                                + " SET status='CANCEL_REQUESTED', version=version+1, updated_at=?"
                                + " WHERE run_id=? AND status IN ('RUNNING','RECOVERING')",
                        statement -> {
                            statement.setString(1, Instant.now().toString());
                            statement.setString(2, runId);
                        })
                == 1;
    }

    public boolean cancellationRequested(String runId) {
        return "CANCEL_REQUESTED".equals(status(runId));
    }

    public boolean terminal(String runId, String status) {
        if (blank(runId)) return false;
        String terminalStatus = safe(status).toUpperCase();
        boolean updated;
        if (!storage.isSqliteEnabled()) {
            MutableRun run = memoryRuns.get(runId);
            if (run == null) return false;
            synchronized (run) {
                if (!instanceId.equals(run.ownerId)
                        || !("RUNNING".equals(run.status)
                                || "CANCEL_REQUESTED".equals(run.status))) {
                    return false;
                }
                run.status = terminalStatus;
                run.leaseUntil = 0;
                run.version++;
                updated = true;
            }
        } else {
            updated =
                    execute(
                                    "UPDATE "
                                            + RUNS_TABLE
                                            + " SET status=?, lease_until=0, version=version+1, updated_at=?"
                                            + " WHERE run_id=? AND owner_id=?"
                                            + " AND status IN ('RUNNING','CANCEL_REQUESTED')",
                                    statement -> {
                                        statement.setString(1, terminalStatus);
                                        statement.setString(2, Instant.now().toString());
                                        statement.setString(3, runId);
                                        statement.setString(4, instanceId);
                                    })
                            == 1;
        }
        if (updated) clear(runId);
        return updated;
    }

    public Optional<Map<String, Object>> request(String runId) {
        if (!storage.isSqliteEnabled()) {
            MutableRun run = memoryRuns.get(runId);
            return run == null ? Optional.empty() : Optional.of(run.request);
        }
        String sql = "SELECT request_json FROM " + RUNS_TABLE + " WHERE run_id=?";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? Optional.of(map(rows.getString("request_json")))
                        : Optional.empty();
            }
        } catch (Exception error) {
            throw new IllegalStateException("Load orchestration request failed", error);
        }
    }

    public List<String> claimRecoverable(int limit) {
        long now = System.currentTimeMillis();
        List<String> candidates = new ArrayList<>();
        if (!storage.isSqliteEnabled()) {
            memoryRuns.values().stream()
                    .filter(run -> run.recoverable(now, instanceId) && run.leaseUntil < now)
                    .limit(Math.max(1, limit))
                    .forEach(run -> candidates.add(run.runId));
        } else {
            String sql =
                    "SELECT run_id FROM "
                            + RUNS_TABLE
                            + " WHERE status IN ('RUNNING','RECOVERING') AND lease_until<?"
                            + " ORDER BY lease_until LIMIT ?";
            try (Connection connection = storage.connection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, now);
                statement.setInt(2, Math.max(1, limit));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) candidates.add(rows.getString("run_id"));
                }
            } catch (Exception error) {
                throw new IllegalStateException("Scan recoverable orchestration runs failed", error);
            }
        }
        return candidates.stream().filter(this::acquire).toList();
    }

    public List<String> claimAbandonedCancellations(int limit) {
        long now = System.currentTimeMillis();
        List<String> candidates = new ArrayList<>();
        if (!storage.isSqliteEnabled()) {
            memoryRuns.values().stream()
                    .filter(
                            run ->
                                    "CANCEL_REQUESTED".equals(run.status)
                                            && run.leaseUntil < now)
                    .limit(Math.max(1, limit))
                    .forEach(run -> candidates.add(run.runId));
            return candidates.stream()
                    .filter(
                            runId -> {
                                MutableRun run = memoryRuns.get(runId);
                                synchronized (run) {
                                    if (!"CANCEL_REQUESTED".equals(run.status)
                                            || run.leaseUntil >= System.currentTimeMillis()) {
                                        return false;
                                    }
                                    run.ownerId = instanceId;
                                    run.leaseUntil = System.currentTimeMillis() + leaseMs;
                                    run.version++;
                                    return true;
                                }
                            })
                    .toList();
        }
        String select =
                "SELECT run_id FROM "
                        + RUNS_TABLE
                        + " WHERE status='CANCEL_REQUESTED' AND lease_until<?"
                        + " ORDER BY lease_until LIMIT ?";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setLong(1, now);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) candidates.add(rows.getString("run_id"));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Scan abandoned cancellations failed", error);
        }
        return candidates.stream()
                .filter(
                        runId ->
                                execute(
                                                "UPDATE "
                                                        + RUNS_TABLE
                                                        + " SET owner_id=?, lease_until=?, version=version+1, updated_at=?"
                                                        + " WHERE run_id=? AND status='CANCEL_REQUESTED' AND lease_until<?",
                                                statement -> {
                                                    long claimedAt = System.currentTimeMillis();
                                                    statement.setString(1, instanceId);
                                                    statement.setLong(2, claimedAt + leaseMs);
                                                    statement.setString(3, Instant.now().toString());
                                                    statement.setString(4, runId);
                                                    statement.setLong(5, claimedAt);
                                                })
                                        == 1)
                .toList();
    }

    private String status(String runId) {
        if (!storage.isSqliteEnabled()) {
            MutableRun run = memoryRuns.get(runId);
            return run == null ? "" : run.status;
        }
        String sql = "SELECT status FROM " + RUNS_TABLE + " WHERE run_id=?";
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString("status") : "";
            }
        } catch (Exception error) {
            throw new IllegalStateException("Read orchestration status failed", error);
        }
    }

    private int execute(String sql, SqlBinder binder) {
        try (Connection connection = storage.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("Persist orchestration state failed", error);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Serialize orchestration state failed", error);
        }
    }

    private static Map<String, Object> map(String value) {
        try {
            return value == null || value.isBlank()
                    ? Map.of()
                    : immutable(JSON.readValue(value, MAP_TYPE));
        } catch (Exception error) {
            throw new IllegalArgumentException("Deserialize orchestration state failed", error);
        }
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static String checkpointKey(String runId, String scopeKey) {
        return runId + "\u0000" + scopeKey;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws Exception;
    }

    public record Checkpoint(
            String runId,
            String scopeKey,
            String phase,
            Map<String, Object> payload,
            long version,
            String updatedAt) {}

    private static final class MutableRun {
        private final String runId;
        private String ownerId;
        private long leaseUntil;
        private String status;
        private final Map<String, Object> request;
        private long version;

        private MutableRun(
                String runId,
                String ownerId,
                long leaseUntil,
                String status,
                Map<String, Object> request,
                long version) {
            this.runId = runId;
            this.ownerId = ownerId;
            this.leaseUntil = leaseUntil;
            this.status = status;
            this.request = request;
            this.version = version;
        }

        private boolean recoverable(long now, String requestingInstanceId) {
            return ("RUNNING".equals(status) || "RECOVERING".equals(status))
                    && (ownerId.equals(requestingInstanceId) || leaseUntil < now);
        }
    }
}
