/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.company.platform.control.PlatformStorageLayer;
import com.company.platform.control.SkillSpec;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Executes a Skill-declared smoke test in the same isolated Docker boundary as agent Skills. */
@Component
public class SkillSandboxSmokeTestService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;

    private final PlatformStorageLayer storage;
    private final Environment environment;

    public SkillSandboxSmokeTestService(PlatformStorageLayer storage, Environment environment) {
        this.storage = storage;
        this.environment = environment;
    }

    public Map<String, Object> execute(SkillSpec spec) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("test_mode", "docker_smoke");
        if (!sandboxEnabled()) {
            result.put("ok", false);
            result.put("stage", "sandbox");
            result.put(
                    "error",
                    "Docker sandbox is disabled. Set COMPANY_PLATFORM_SANDBOX_ENABLED=true.");
            return result;
        }
        if (!"filesystem".equals(spec.type()) && !"platform".equals(spec.type())) {
            result.put("ok", false);
            result.put("stage", "metadata");
            result.put("error", "Only filesystem Skills can declare a Docker smoke test.");
            return result;
        }

        Path skillRoot = storage.skillDirectory(spec.skillId()).toAbsolutePath().normalize();
        if (!skillRoot.startsWith(storage.skillsRoot().toAbsolutePath().normalize())) {
            result.put("ok", false);
            result.put("stage", "metadata");
            result.put("error", "Skill is outside the platform-managed Skills directory.");
            return result;
        }
        SmokeTest smokeTest = readSmokeTest(skillRoot.resolve("skill.yaml"));
        if (smokeTest.command().isBlank()) {
            result.put("ok", true);
            result.put("stage", "load");
            result.put("smoke_test", "not_configured");
            result.put("message", "Skill has no smoke_test.command; only its files were checked.");
            return result;
        }

        String command =
                smokeTest.command().replace("{skill_root}", "/workspace/skills/" + spec.skillId());
        if (command.contains("\n") || command.contains("\r")) {
            result.put("ok", false);
            result.put("stage", "metadata");
            result.put("error", "smoke_test.command must be a single shell command.");
            return result;
        }

        long started = System.nanoTime();
        try (Sandbox sandbox =
                new DockerSandboxClient()
                        .create(workspaceSpec(), new NoopSnapshotSpec(), options())) {
            sandbox.start();
            ExecResult execution = sandbox.exec(null, command, smokeTest.timeoutSeconds());
            result.put("ok", execution.ok());
            result.put("stage", "docker_smoke");
            result.put("command", command);
            result.put("timeout_seconds", smokeTest.timeoutSeconds());
            result.put("exit_code", execution.exitCode());
            result.put("stdout", execution.stdout());
            result.put("stderr", execution.stderr());
            result.put("truncated", execution.truncated());
        } catch (Exception e) {
            result.put("ok", false);
            result.put("stage", "docker_smoke");
            result.put("command", command);
            result.put(
                    "error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        result.put("duration_ms", (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private WorkspaceSpec workspaceSpec() {
        WorkspaceSpec workspace = new WorkspaceSpec();
        workspace.setRoot("/workspace");
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(storage.workspace().toString());
        projection.setIncludeRoots(List.of("skills"));
        workspace.getEntries().put("__skill_smoke_projection__", projection);
        return workspace;
    }

    private DockerSandboxClientOptions options() {
        return new DockerSandboxClientOptions()
                .image(
                        environment.getProperty(
                                "company.platform.sandbox.image", "python:3.12-slim"))
                .workspaceRoot("/workspace")
                .memorySizeBytes(
                        environment.getProperty(
                                "company.platform.sandbox.memory-size-bytes",
                                Long.class,
                                512L * 1024 * 1024))
                .cpuCount(
                        environment.getProperty(
                                "company.platform.sandbox.cpu-count", Long.class, 1L))
                .network("none")
                .additionalRunArgs(
                        "--cap-drop=ALL", "--security-opt=no-new-privileges", "--pids-limit=64");
    }

    private boolean sandboxEnabled() {
        return environment.getProperty("company.platform.sandbox.enabled", Boolean.class, false);
    }

    private SmokeTest readSmokeTest(Path skillYaml) {
        if (!Files.isRegularFile(skillYaml)) {
            return new SmokeTest("", DEFAULT_TIMEOUT_SECONDS);
        }
        try {
            boolean smokeSection = false;
            String command = "";
            int timeout = DEFAULT_TIMEOUT_SECONDS;
            for (String line : Files.readAllLines(skillYaml)) {
                String trimmed = line.trim();
                if (trimmed.equals("smoke_test:")) {
                    smokeSection = true;
                    continue;
                }
                if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                    smokeSection = false;
                }
                if (!smokeSection) {
                    continue;
                }
                if (trimmed.startsWith("command:")) {
                    command = yamlValue(trimmed.substring("command:".length()));
                } else if (trimmed.startsWith("timeout_seconds:")) {
                    try {
                        timeout =
                                Integer.parseInt(
                                        yamlValue(trimmed.substring("timeout_seconds:".length())));
                    } catch (NumberFormatException ignored) {
                        timeout = DEFAULT_TIMEOUT_SECONDS;
                    }
                }
            }
            return new SmokeTest(command, Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, timeout)));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read Skill smoke_test metadata: " + skillYaml, e);
        }
    }

    private static String yamlValue(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                        || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record SmokeTest(String command, int timeoutSeconds) {}
}
