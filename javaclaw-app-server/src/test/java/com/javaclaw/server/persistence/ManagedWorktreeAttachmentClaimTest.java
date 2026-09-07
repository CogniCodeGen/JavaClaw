package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedWorktreeAttachmentClaimTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String CLIENT_MEDIA_TYPE = "application/client-declared";

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private H2Transactions transactions;
    private ManagedWorktreeRepository repository;
    private CoreCommandService core;
    private AttachmentService attachments;
    private Workspace owner;
    private Workspace foreign;
    private int sequence;

    @BeforeEach
    void initializeDataV6() {
        json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        transactions = new H2Transactions(database);
        repository = new ManagedWorktreeRepository();
        core = new CoreCommandService(database, json, clock);
        attachments = new AttachmentService(database, json, clock);
        owner = workspace("owner");
        foreign = workspace("foreign");
    }

    @Test
    void workspaceClaim提供权威Mime而不信任Worktree中的声明() throws Exception {
        AttachmentMetadata metadata =
                store(AttachmentScope.workspace(owner.id()), "workspace", "text/x-diff", "workspace patch");
        ManagedWorktree worktree = persist(owner, reference(metadata));

        AttachmentRef restored = transactions
                .execute(
                        connection -> repository.find(connection, worktree.id()).orElseThrow())
                .backup()
                .orElseThrow();

        assertEquals("text/x-diff", restored.mediaType());
        assertEquals(metadata.sizeBytes(), restored.sizeBytes());
    }

    @Test
    void globalClaim可恢复安装级Backup的权威Mime() throws Exception {
        AttachmentMetadata metadata =
                store(AttachmentScope.global(), "global", "application/x-git-patch", "global patch");
        ManagedWorktree worktree = persist(owner, reference(metadata));

        AttachmentRef restored = transactions
                .execute(connection -> repository
                        .findByChild(connection, worktree.childThreadId())
                        .orElseThrow())
                .backup()
                .orElseThrow();

        assertEquals("application/x-git-patch", restored.mediaType());
        assertEquals(metadata.sizeBytes(), restored.sizeBytes());
    }

    @Test
    void attachment没有任何Claim时读取Worktree失败关闭() throws Exception {
        AttachmentMetadata metadata = store(AttachmentScope.global(), "unclaimed", "text/plain", "unclaimed patch");
        ManagedWorktree worktree = persist(owner, reference(metadata));
        deleteClaim("CORE.ATTACHMENT_GLOBAL_CLAIM", metadata.digest());

        PersistenceException failure = assertThrows(
                PersistenceException.class,
                () -> transactions.execute(connection -> repository.list(connection, owner.id(), true)));

        assertEquals(PersistenceException.Kind.INTERNAL, failure.kind());
    }

    @Test
    void 其他Workspace的Claim不能被当前Worktree借用() throws Exception {
        AttachmentMetadata metadata =
                store(AttachmentScope.workspace(foreign.id()), "foreign", "application/x-foreign", "foreign patch");
        ManagedWorktree worktree = persist(owner, reference(metadata));

        PersistenceException failure = assertThrows(
                PersistenceException.class,
                () -> transactions.execute(connection -> repository.find(connection, worktree.id())));

        assertEquals(PersistenceException.Kind.INTERNAL, failure.kind());
    }

    private Workspace workspace(String name) {
        return core.createWorkspace(
                identity("workspace/create", name), name, temporaryDirectory.resolve("workspace-" + name));
    }

    private ManagedWorktree persist(Workspace workspace, AttachmentRef backup) throws Exception {
        ConversationThread parent = core.createThread(
                identity("thread/create", "parent"),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Parent");
        ConversationThread child = core.createThread(
                identity("thread/create", "child"),
                workspace.id(),
                Optional.of(parent.id()),
                ThreadExecutionIntent.ISOLATED_WRITE,
                "Child");
        ManagedWorktree worktree = new ManagedWorktree(
                new WorktreeId(UUID.randomUUID()),
                workspace.id(),
                parent.id(),
                child.id(),
                temporaryDirectory.resolve("managed-" + sequence),
                "a".repeat(40),
                ManagedWorktreeState.READY,
                1,
                Optional.of(backup),
                NOW,
                NOW);
        transactions.execute(connection -> {
            repository.insert(connection, worktree);
            return null;
        });
        return worktree;
    }

    private AttachmentMetadata store(AttachmentScope scope, String key, String mediaType, String content) {
        return attachments.store(
                scope, identity("attachment/internal/store", key), mediaType, content.getBytes(StandardCharsets.UTF_8));
    }

    private AttachmentRef reference(AttachmentMetadata metadata) {
        return new AttachmentRef(metadata.digest(), CLIENT_MEDIA_TYPE, "backup.patch", metadata.sizeBytes());
    }

    private void deleteClaim(String table, String digest) throws Exception {
        transactions.execute(connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM " + table + " WHERE ATTACHMENT_DIGEST = ?")) {
                statement.setString(1, digest);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private CommandIdentity identity(String method, String key) {
        String uniqueKey = key + "-" + sequence++;
        return new CommandIdentity(
                method, uniqueKey, 0, json.encode(Map.of("key", uniqueKey)).sha256());
    }
}
