/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformStorageLayerTest {

    @TempDir Path tempDir;

    @Test
    void sqliteConnectionsUseConcurrentDurableRuntimePragmas() throws Exception {
        Path database = tempDir.resolve("platform.db");
        PlatformStorageLayer storage =
                new PlatformStorageLayer(
                        tempDir.toString(),
                        "sqlite",
                        "jdbc:sqlite:" + database,
                        "platform_config",
                        "platform_",
                        "");

        try (Connection connection = storage.connection();
                Statement statement = connection.createStatement()) {
            assertEquals("wal", pragma(statement, "journal_mode"));
            assertEquals("1", pragma(statement, "synchronous"));
            assertEquals("5000", pragma(statement, "busy_timeout"));
        }
    }

    private static String pragma(Statement statement, String name) throws Exception {
        try (ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            return result.next() ? result.getString(1) : "";
        }
    }
}
