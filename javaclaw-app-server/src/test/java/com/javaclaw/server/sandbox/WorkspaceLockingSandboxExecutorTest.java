package com.javaclaw.server.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;
import com.javaclaw.server.persistence.H2Persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLockingSandboxExecutorTest {
    @TempDir
    Path temporary;

    @Test
    void aclRestorationSentinelPermanentlyLocksTheOwningWorkspace() throws Exception {
        try (H2Persistence store = new H2Persistence(temporary.resolve("data"))) {
            Path workspaceRoot = temporary.resolve("workspace");
            java.nio.file.Files.createDirectories(workspaceRoot);
            var workspace = store.workspaces().create("Workspace", workspaceRoot, "create-workspace");
            SandboxExecutor failed = command -> new SandboxResult(
                    73,
                    "",
                    WorkspaceLockingSandboxExecutor.ACL_FAILURE + " access denied",
                    false,
                    false,
                    Duration.ofMillis(1),
                    "windows-appcontainer");
            WorkspaceLockingSandboxExecutor executor = new WorkspaceLockingSandboxExecutor(failed, store.workspaces());
            SandboxPolicy policy = new SandboxPolicy(
                    SandboxMode.WORKSPACE_WRITE,
                    Set.of(workspaceRoot),
                    Set.of(workspaceRoot),
                    Set.of(),
                    NetworkPolicy.disabled(),
                    Set.of(),
                    Duration.ofSeconds(1),
                    1024);

            executor.execute(new SandboxCommand("command", List.of("ignored"), workspaceRoot, Map.of(), policy));

            var locked = store.workspaces().find(workspace.id()).orElseThrow();
            assertTrue(locked.locked());
            assertTrue(locked.lockReason().contains("ACL restoration failed"));
        }
    }
}
