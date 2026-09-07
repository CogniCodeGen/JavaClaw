package com.javaclaw.server.turn;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadExecutionScopeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private H2Database database;
    private CoreCommandService core;
    private Workspace workspace;
    private ConversationThread rootThread;

    @BeforeEach
    void 初始化权威Thread数据() throws Exception {
        json = new CanonicalJson();
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, CLOCK);
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        workspace = core.createWorkspace(identity("workspace/create"), "Workspace", root);
        rootThread = core.createThread(
                identity("thread/create/root"),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Root");
    }

    @Test
    void Workspace与只读Thread共用项目根但写权不同() {
        ConversationThread readOnly = core.createThread(
                identity("thread/create/read-only"),
                workspace.id(),
                Optional.of(rootThread.id()),
                ThreadExecutionIntent.READ_ONLY,
                "Read only");

        ThreadExecutionScope writable = ThreadExecutionScope.resolve(core, null, rootThread.id());
        ThreadExecutionScope readonly = ThreadExecutionScope.resolve(core, null, readOnly.id());

        assertEquals(workspace.root(), writable.root());
        assertTrue(writable.writable());
        assertFalse(writable.isolatedWrite());
        assertEquals(workspace.root(), readonly.root());
        assertFalse(readonly.writable());
        assertFalse(readonly.isolatedWrite());
    }

    @Test
    void 执行根总是规范绝对路径() {
        ThreadExecutionScope scope =
                new ThreadExecutionScope(rootThread, workspace, Path.of("nested/../execution"), true);

        assertTrue(scope.root().isAbsolute());
        assertFalse(scope.root().toString().contains(".."));
        assertThrows(
                NullPointerException.class,
                () -> new ThreadExecutionScope(
                        (com.javaclaw.api.ConversationThread) null, workspace, workspace.root(), true));
        assertThrows(
                NullPointerException.class, () -> new ThreadExecutionScope(rootThread, null, workspace.root(), true));
        assertThrows(NullPointerException.class, () -> new ThreadExecutionScope(rootThread, workspace, null, true));
    }

    @Test
    void 不存在Thread与未建立Worktree的隔离写Thread均被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> ThreadExecutionScope.resolve(core, null, ThreadId.random()));

        ConversationThread isolated = core.createThread(
                identity("thread/create/isolated"),
                workspace.id(),
                Optional.of(rootThread.id()),
                ThreadExecutionIntent.ISOLATED_WRITE,
                "Isolated");
        AttachmentService attachments = new AttachmentService(database, json, CLOCK);
        ManagedWorktreeService worktrees =
                new ManagedWorktreeService(database, attachments, json, CLOCK, new UnusedSandbox());

        assertTrue(new ThreadExecutionScope(isolated, workspace, workspace.root(), true).isolatedWrite());
        assertThrows(PersistenceException.class, () -> ThreadExecutionScope.resolve(core, worktrees, isolated.id()));
    }

    private CommandIdentity identity(String method) {
        return new CommandIdentity(
                method,
                UUID.randomUUID().toString(),
                0,
                json.encode(Map.of(method, true)).sha256());
    }

    private static final class UnusedSandbox implements SandboxExecutor {
        @Override
        public SandboxResult execute(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new AssertionError("test must not execute a process");
        }

        @Override
        public SandboxSession open(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new AssertionError("test must not open a process");
        }
    }
}
