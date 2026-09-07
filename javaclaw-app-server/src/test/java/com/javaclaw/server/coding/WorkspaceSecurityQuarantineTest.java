package com.javaclaw.server.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.nativehost.ffm.WindowsSandbox;
import com.javaclaw.server.persistence.WorkspaceSecurityRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSecurityQuarantineTest {
    @TempDir
    Path directory;

    @Test
    void 可信恢复失败持久锁定且重启和交叠根登记均不能绕过() throws Exception {
        try (var fixture = new CodingTestFixture(directory)) {
            var security = fixture.core.workspaceSecurity();
            var evidence =
                    Files.createDirectory(directory.resolve("private-evidence")).toRealPath();
            var failure = new IOException("command cleanup");
            failure.addSuppressed(new WindowsSandbox.AclRestorationException("restore", null, Optional.of(evidence)));
            assertTrue(
                    security.quarantine(fixture.workspace.id(), Optional.of(fixture.turn.id()), fixture.root, failure));
            assertTrue(
                    security.quarantine(fixture.workspace.id(), Optional.of(fixture.turn.id()), fixture.root, failure));
            var reloaded = new WorkspaceSecurityRepository(fixture.database, fixture.json, fixture.clock);
            assertEquals(1, reloaded.events().size());
            assertEquals(
                    List.of(evidence), reloaded.events().getFirst().evidence().directories());
            assertThrows(SecurityException.class, () -> reloaded.requireUnlocked(fixture.workspace.id(), fixture.root));
            var nested = Files.createDirectory(fixture.root.resolve("nested"));
            var other = fixture.core.createWorkspace(
                    fixture.identity("workspace/create", "overlap", Map.of()), "nested", nested);
            assertThrows(SecurityException.class, () -> reloaded.requireUnlocked(other.id(), nested));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.platform.bindTool(
                            fixture.request(fixture.turn, "command_run", command(), "late"),
                            fixture.permission,
                            new CancellationSource()));
            assertThrows(SecurityException.class, () -> fixture.createTurn("locked"));
        }
    }

    @Test
    void 普通目标退出73及相似错误文本不构成隔离恢复事件() throws Exception {
        try (var fixture = new CodingTestFixture(directory)) {
            assertFalse(fixture.core
                    .workspaceSecurity()
                    .quarantine(
                            fixture.workspace.id(),
                            Optional.of(fixture.turn.id()),
                            fixture.root,
                            new IOException("Windows Sandbox permission restoration failed: exit 73")));
            assertTrue(fixture.core.workspaceSecurity().events().isEmpty());
            fixture.core.workspaceSecurity().requireUnlocked(fixture.workspace.id(), fixture.root);
        }
    }

    @Test
    void PTY异步清理失败必须先建立持久锁再完成资源owner() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            var request = new CodingContracts.TerminalOpen(command(), 120, 40);
            fixture.terminals.execute("terminal_open", fixture.invocation("pty-security", "terminal_open", request));
            var failure = new WindowsSandbox.AclRestorationException("native cleanup", null);
            sandbox.session.exit.completeExceptionally(failure);
            sandbox.session.finishOutput();
            assertThrows(Exception.class, () -> fixture.terminals.finish(fixture.base.turn.id()));
            assertEquals(1, fixture.base.core.workspaceSecurity().events().size());
            assertThrows(
                    SecurityException.class,
                    () -> fixture.base
                            .core
                            .workspaceSecurity()
                            .requireUnlocked(fixture.base.workspace.id(), fixture.base.root));
        }
    }

    @Test
    void batch资源释放时聚合的恢复异常在方法返回前锁定Workspace() throws Exception {
        try (var fixture = new CodingLifecycleFixture(directory, new ControlledCodingSandbox())) {
            fixture.toolchains.beforeRelease = () -> {
                throw new java.io.UncheckedIOException(new WindowsSandbox.AclRestorationException("restore", null));
            };
            var invocation = fixture.invocation("batch-security", "command_run", command());
            assertThrows(java.io.UncheckedIOException.class, () -> fixture.processes.run(invocation));
            assertEquals(1, fixture.base.core.workspaceSecurity().events().size());
            assertThrows(
                    SecurityException.class,
                    () -> fixture.base
                            .core
                            .workspaceSecurity()
                            .requireUnlocked(fixture.base.workspace.id(), fixture.base.root));
        }
    }

    private static CodingContracts.CommandRun command() {
        return new CodingContracts.CommandRun(List.of("java", "Main"), ".", 30, 1024);
    }
}
