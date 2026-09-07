package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedWorktreeBindingResolverTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CoreCommandService core;
    private ManagedWorktreeBindingResolver resolver;
    private Workspace workspace;
    private ConversationThread parent;
    private ConversationThread child;
    private int sequence;

    @BeforeEach
    void initializeBindings() throws Exception {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, new CanonicalJson(), Clock.fixed(NOW, ZoneOffset.UTC));
        resolver = new ManagedWorktreeBindingResolver(database);
        workspace = createWorkspace("primary");
        parent = createRoot(workspace, "parent");
        child = createChild(workspace, parent, "child");
    }

    @Test
    void require拒绝缺失资源并接受活动Workspace直接父子Thread() {
        assertThrows(PersistenceException.class, () -> resolver.require(WorkspaceId.random(), parent.id(), child.id()));
        assertThrows(PersistenceException.class, () -> resolver.require(workspace.id(), ThreadId.random(), child.id()));
        assertThrows(
                PersistenceException.class, () -> resolver.require(workspace.id(), parent.id(), ThreadId.random()));

        ManagedWorktreeBindingResolver.Binding binding = resolver.require(workspace.id(), parent.id(), child.id());
        assertEquals(workspace, binding.workspace());
        assertEquals(parent, binding.parent());
        assertEquals(child, binding.child());
    }

    @Test
    void require逐层拒绝归档跨Workspace与非直接父子关系() throws Exception {
        Workspace foreign = createWorkspace("foreign");
        ConversationThread foreignParent = createRoot(foreign, "foreign-parent");
        ConversationThread foreignChild = createChild(foreign, foreignParent, "foreign-child");
        ConversationThread unrelated = createRoot(workspace, "unrelated");

        assertThrows(
                PersistenceException.class, () -> resolver.require(workspace.id(), foreignParent.id(), child.id()));
        assertThrows(
                PersistenceException.class, () -> resolver.require(workspace.id(), parent.id(), foreignChild.id()));
        assertThrows(PersistenceException.class, () -> resolver.require(workspace.id(), parent.id(), unrelated.id()));

        workspace = core.archiveWorkspace(identity("workspace/archive", workspace.revision()), workspace.id());
        assertThrows(PersistenceException.class, () -> resolver.require(workspace.id(), parent.id(), child.id()));
    }

    @Test
    void requireSame逐字段拒绝漂移并拒绝已清理绑定() {
        ManagedWorktreeBindingResolver.Binding binding = resolver.require(workspace.id(), parent.id(), child.id());
        Path destination =
                temporaryDirectory.resolve("managed-root").toAbsolutePath().normalize();
        ManagedWorktree valid = worktree(binding, destination, ManagedWorktreeState.READY, Optional.empty());

        ManagedWorktreeBindingResolver.requireSame(valid, binding, destination);
        assertRejected(
                worktree(binding, destination, ManagedWorktreeState.READY, Optional.empty()),
                new ManagedWorktreeBindingResolver.Binding(createWorkspaceUnchecked("other"), parent, child),
                destination);
        assertRejected(withParent(valid, ThreadId.random()), binding, destination);
        assertRejected(withChild(valid, ThreadId.random()), binding, destination);
        assertRejected(valid, binding, destination.resolve("other"));
        assertRejected(
                worktree(binding, destination, ManagedWorktreeState.CLEANED, Optional.of(backup())),
                binding,
                destination);
    }

    private void assertRejected(
            ManagedWorktree worktree, ManagedWorktreeBindingResolver.Binding binding, Path destination) {
        assertThrows(
                PersistenceException.class,
                () -> ManagedWorktreeBindingResolver.requireSame(worktree, binding, destination));
    }

    private Workspace createWorkspace(String name) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve(name));
        return core.createWorkspace(identity("workspace/create", 0), name, root);
    }

    private Workspace createWorkspaceUnchecked(String name) {
        try {
            return createWorkspace(name);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private ConversationThread createRoot(Workspace owner, String title) {
        return core.createThread(
                identity("thread/create", 0), owner.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, title);
    }

    private ConversationThread createChild(Workspace owner, ConversationThread ownerParent, String title) {
        return core.createThread(
                identity("thread/create", 0),
                owner.id(),
                Optional.of(ownerParent.id()),
                ThreadExecutionIntent.ISOLATED_WRITE,
                title);
    }

    private CommandIdentity identity(String method, long revision) {
        sequence++;
        return new CommandIdentity(method, "binding-" + sequence, revision, "%064x".formatted(sequence));
    }

    private static ManagedWorktree worktree(
            ManagedWorktreeBindingResolver.Binding binding,
            Path destination,
            ManagedWorktreeState state,
            Optional<AttachmentRef> backup) {
        return new ManagedWorktree(
                new WorktreeId(UUID.randomUUID()),
                binding.workspace().id(),
                binding.parent().id(),
                binding.child().id(),
                destination,
                "a".repeat(40),
                state,
                1,
                backup,
                NOW,
                NOW);
    }

    private static ManagedWorktree withParent(ManagedWorktree source, ThreadId parentId) {
        return new ManagedWorktree(
                source.id(),
                source.workspaceId(),
                parentId,
                source.childThreadId(),
                source.executionRoot(),
                source.baseCommit(),
                source.state(),
                source.revision(),
                source.backup(),
                source.createdAt(),
                source.updatedAt());
    }

    private static ManagedWorktree withChild(ManagedWorktree source, ThreadId childId) {
        return new ManagedWorktree(
                source.id(),
                source.workspaceId(),
                source.parentThreadId(),
                childId,
                source.executionRoot(),
                source.baseCommit(),
                source.state(),
                source.revision(),
                source.backup(),
                source.createdAt(),
                source.updatedAt());
    }

    private static AttachmentRef backup() {
        return new AttachmentRef("b".repeat(64), "application/octet-stream", "backup.patch", 0);
    }
}
