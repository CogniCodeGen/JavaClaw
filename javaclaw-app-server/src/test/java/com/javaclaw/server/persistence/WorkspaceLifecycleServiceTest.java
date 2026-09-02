package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLifecycleServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 重命名与归档只修改登记且不触碰用户目录() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path marker = Files.writeString(root.resolve("user-file.txt"), "keep");
        CoreCommandService service = service();
        Workspace created = service.createWorkspace(identity("workspace/create", "create", 0), "Before", root);

        Workspace renamed = service.renameWorkspace(
                identity("workspace/rename", "rename", created.revision()), created.id(), "After");
        Workspace archived =
                service.archiveWorkspace(identity("workspace/archive", "archive", renamed.revision()), renamed.id());

        assertEquals("After", archived.name());
        assertEquals(created.root(), archived.root());
        assertEquals(WorkspaceLifecycle.ARCHIVED, archived.lifecycle());
        assertEquals(3, archived.revision());
        assertTrue(Files.exists(marker));
        assertThrows(
                PersistenceException.class,
                () -> service.createThread(
                        identity("thread/create", "thread", 0),
                        archived.id(),
                        Optional.empty(),
                        com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                        "blocked"));
        assertEquals(archived, service().findWorkspace(archived.id()).orElseThrow());
    }

    @Test
    void Workspace更新使用幂等回执并拒绝旧revision() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace-revision"));
        CoreCommandService service = service();
        Workspace created = service.createWorkspace(identity("workspace/create", "create-2", 0), "One", root);
        CommandIdentity rename = identity("workspace/rename", "rename-2", 1);

        Workspace result = service.renameWorkspace(rename, created.id(), "Two");

        assertEquals(result, service.renameWorkspace(rename, created.id(), "Two"));
        assertThrows(
                PersistenceException.class,
                () -> service.archiveWorkspace(identity("workspace/archive", "archive-stale", 1), created.id()));
    }

    private CoreCommandService service() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        return new CoreCommandService(database, new CanonicalJson(), CLOCK);
    }

    private static CommandIdentity identity(String method, String key, long revision) {
        return new CommandIdentity(method, key, revision, "a".repeat(64));
    }
}
