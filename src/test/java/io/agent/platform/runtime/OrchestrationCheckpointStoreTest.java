/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agent.platform.control.PlatformStorageLayer;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrchestrationCheckpointStoreTest {

    @TempDir Path tempDir;

    @Test
    void expiredLeaseIsFencedAndAnotherInstanceContinuesFromCheckpoint() throws Exception {
        PlatformStorageLayer storage = storage(tempDir);
        OrchestrationCheckpointStore first =
                new OrchestrationCheckpointStore(storage, "instance-a", 5_000);
        OrchestrationCheckpointStore second =
                new OrchestrationCheckpointStore(storage, "instance-b", 5_000);
        first.initialize();
        second.initialize();

        first.register("run-1", Map.of("agent_id", "pipeline-agent"));
        assertTrue(first.acquire("run-1"));
        assertTrue(
                first.save(
                        "run-1",
                        "root::pipeline-agent::PIPELINE",
                        "STEP_COMMITTED",
                        Map.of("next_index", 1)));

        expire(storage, "run-1");
        assertEquals(ListHolder.RUN_1, second.claimRecoverable(10));

        assertFalse(
                first.save(
                        "run-1",
                        "root::pipeline-agent::PIPELINE",
                        "STALE_WRITE",
                        Map.of("next_index", 99)));
        assertTrue(
                second.save(
                        "run-1",
                        "root::pipeline-agent::PIPELINE",
                        "STEP_COMMITTED",
                        Map.of("next_index", 2)));
        OrchestrationCheckpointStore.Checkpoint checkpoint =
                second.load("run-1", "root::pipeline-agent::PIPELINE").orElseThrow();
        assertEquals(2, ((Number) checkpoint.payload().get("next_index")).intValue());
        assertTrue(second.terminal("run-1", "SUCCEEDED"));
        assertTrue(second.load("run-1", "root::pipeline-agent::PIPELINE").isEmpty());
    }

    @Test
    void cancellationCanOnlyReplaceAnActiveRunAndOwnerFinalizesIt() {
        PlatformStorageLayer storage = storage(tempDir);
        OrchestrationCheckpointStore store =
                new OrchestrationCheckpointStore(storage, "instance-a", 5_000);
        store.initialize();

        store.register("run-cancel", Map.of("agent_id", "supervisor-agent"));
        assertTrue(store.acquire("run-cancel"));
        assertTrue(store.requestCancel("run-cancel"));
        assertTrue(store.cancellationRequested("run-cancel"));
        assertTrue(store.terminal("run-cancel", "CANCELLED"));
        assertFalse(store.requestCancel("run-cancel"));
    }

    @Test
    void suspendedRunIsNotRecoveredUntilItIsExplicitlyResumed() {
        PlatformStorageLayer storage = storage(tempDir);
        OrchestrationCheckpointStore store =
                new OrchestrationCheckpointStore(storage, "instance-a", 5_000);
        store.initialize();

        store.register("run-wait", Map.of("runtime_kind", "workflow"));
        assertTrue(store.acquire("run-wait"));
        assertTrue(store.suspend("run-wait"));
        assertTrue(store.claimRecoverable(10).isEmpty());
        assertTrue(store.resume("run-wait"));
        assertTrue(
                store.save(
                        "run-wait",
                        "workflow:flow:v1:node:approval",
                        "NODE_COMPLETED",
                        Map.of("approved", true)));
        assertTrue(store.terminal("run-wait", "SUCCEEDED"));
    }

    private static PlatformStorageLayer storage(Path workspace) {
        return new PlatformStorageLayer(
                workspace.toString(),
                "sqlite",
                "jdbc:sqlite:" + workspace.resolve("orchestration.db"),
                "platform_config",
                "platform_",
                "");
    }

    private static void expire(PlatformStorageLayer storage, String runId) throws Exception {
        try (var connection = storage.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE platform_orchestration_runs SET lease_until=0 WHERE run_id=?")) {
            statement.setString(1, runId);
            statement.executeUpdate();
        }
    }

    private static final class ListHolder {
        private static final java.util.List<String> RUN_1 = java.util.List.of("run-1");
    }
}
