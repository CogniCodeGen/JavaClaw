package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.ManagedWorktreeArtifactKind;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.protocol.CanonicalJson;

/** Patch、Backup 与 cleanup 的事务和文件副作用协调器。 */
final class ManagedWorktreeArtifactService {
    private static final String PATCH_MEDIA_TYPE = "application/vnd.javaclaw.git-patch";

    private final H2Transactions transactions;
    private final WorkspaceRepository workspaces = new WorkspaceRepository();
    private final TurnRepository turns = new TurnRepository();
    private final ManagedWorktreeRepository worktrees = new ManagedWorktreeRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final ManagedGitWorktreeRunner git;
    private final AttachmentService attachments;
    private final ManagedWorktreePaths paths;
    private final CanonicalJson json;
    private final Clock clock;
    private final CleanupIsolationObserver cleanupObserver;

    ManagedWorktreeArtifactService(
            H2Database database,
            AttachmentService attachments,
            ManagedGitWorktreeRunner git,
            ManagedWorktreePaths paths,
            CanonicalJson json,
            Clock clock,
            CleanupIsolationObserver cleanupObserver) {
        transactions = new H2Transactions(database);
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.git = Objects.requireNonNull(git, "git");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cleanupObserver = Objects.requireNonNull(cleanupObserver, "cleanupObserver");
    }

    ManagedWorktreeArtifact exportPatch(CommandIdentity identity, WorktreeId id) {
        return capture(identity, id, ManagedWorktreeArtifactKind.PATCH);
    }

    ManagedWorktreeArtifact backup(CommandIdentity identity, WorktreeId id) {
        return capture(identity, id, ManagedWorktreeArtifactKind.BACKUP);
    }

    ManagedWorktree cleanup(CommandIdentity identity, WorktreeId id) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        WorktreeId checkedId = Objects.requireNonNull(id, "worktreeId");
        synchronized (ManagedWorktreePolicy.resourceLock(checkedId)) {
            Optional<ManagedWorktree> recovered = recover(checked, ManagedWorktree.class);
            if (recovered.isPresent()) {
                return recovered.orElseThrow();
            }
            ManagedWorktree intent = cleanupIntent(checked, find(checkedId));
            Workspace workspace = requireGitWorkspace(intent.workspaceId());
            boolean verified = intent.revision() == Math.addExact(checked.expectedRevision(), 2);
            Path isolationRoot = isolateForCleanupSafely(workspace, intent);
            if (!verified && !isPresent(workspace, isolationRoot)) {
                PersistenceException failure = new PersistenceException("Worktree 在 Backup 校验前已经消失");
                markCleanupFailure(intent, ManagedWorktreeState.UNKNOWN_OUTCOME, failure);
                throw failure;
            }
            if (!verified) {
                verifyForCleanup(workspace, intent, isolationRoot);
                intent = markCleanupVerified(checked, intent);
            }
            if (isPresent(workspace, isolationRoot)) {
                verifyForCleanup(workspace, intent, isolationRoot);
                deleteForCleanup(workspace, intent, isolationRoot);
            }
            return finishCleanup(checked, intent);
        }
    }

    private ManagedWorktreeArtifact capture(CommandIdentity identity, WorktreeId id, ManagedWorktreeArtifactKind kind) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        WorktreeId checkedId = Objects.requireNonNull(id, "worktreeId");
        synchronized (ManagedWorktreePolicy.resourceLock(checkedId)) {
            Optional<ManagedWorktreeArtifact> recovered = recover(checked, ManagedWorktreeArtifact.class);
            if (recovered.isPresent()) {
                return recovered.orElseThrow();
            }
            ManagedWorktree current = find(checkedId);
            ManagedWorktreePolicy.requireRevision(checked, current);
            ManagedWorktreePolicy.requireCapturable(current);
            byte[] patch = patch(current);
            AttachmentRef attachment = createPatchAttachment(checked, current, kind, patch);
            if (kind == ManagedWorktreeArtifactKind.BACKUP) {
                verifyStableBackup(current, patch, attachment);
            }
            ManagedWorktreeArtifact artifact = new ManagedWorktreeArtifact(
                    current.id(), current.revision(), kind, attachment, current.baseCommit(), now());
            return persistArtifact(checked, current, artifact);
        }
    }

    private ManagedWorktreeArtifact persistArtifact(
            CommandIdentity identity, ManagedWorktree current, ManagedWorktreeArtifact artifact) {
        return execute(connection -> {
            Optional<IdempotencyRepository.StoredCommand> stored =
                    idempotency.find(connection, identity.idempotencyKey());
            if (stored.isPresent()) {
                return recover(identity, stored.orElseThrow(), ManagedWorktreeArtifact.class);
            }
            ManagedWorktree locked = worktrees.lock(connection, current.id());
            ManagedWorktreePolicy.requireRevision(identity, locked);
            if (artifact.kind() == ManagedWorktreeArtifactKind.BACKUP) {
                worktrees.transition(connection, locked, locked.state(), Optional.of(artifact.attachment()), now());
            }
            idempotency.insert(connection, identity, json.encode(artifact), now());
            return artifact;
        });
    }

    private ManagedWorktree cleanupIntent(CommandIdentity identity, ManagedWorktree current) {
        if (current.state() == ManagedWorktreeState.CLEANING
                && (current.revision() == Math.addExact(identity.expectedRevision(), 1)
                        || current.revision() == Math.addExact(identity.expectedRevision(), 2))) {
            return current;
        }
        ManagedWorktreePolicy.requireRevision(identity, current);
        ManagedWorktreePolicy.requireCleanupState(current);
        if (current.backup().isEmpty()) {
            throw PersistenceException.invalidRequest("cleanup 前必须先创建可验证 Backup");
        }
        return execute(connection -> {
            ManagedWorktree locked = worktrees.lock(connection, current.id());
            ManagedWorktreePolicy.requireRevision(identity, locked);
            if (turns.findActiveByThread(connection, locked.childThreadId()).isPresent()) {
                throw PersistenceException.invalidRequest("子 Thread 仍有活动 Turn，拒绝 cleanup");
            }
            return worktrees.transition(connection, locked, ManagedWorktreeState.CLEANING, locked.backup(), now());
        });
    }

    private ManagedWorktree markCleanupVerified(CommandIdentity identity, ManagedWorktree intent) {
        return execute(connection -> {
            ManagedWorktree current = worktrees.lock(connection, intent.id());
            boolean expected = current.state() == ManagedWorktreeState.CLEANING
                    && current.revision() == Math.addExact(identity.expectedRevision(), 1);
            if (!expected) {
                throw PersistenceException.revisionConflict("cleanup 校验期间 revision 已改变");
            }
            return worktrees.transition(connection, current, ManagedWorktreeState.CLEANING, current.backup(), now());
        });
    }

    private Path isolateForCleanup(Workspace workspace, ManagedWorktree intent) {
        Path source = intent.executionRoot();
        Path isolationRoot = paths.cleanupIsolation(intent);
        boolean sourceRegistered = git.isRegistered(workspace.root(), source);
        boolean isolationRegistered = git.isRegistered(workspace.root(), isolationRoot);
        if (isolationRegistered) {
            requireIsolated(source, isolationRoot);
            return isolationRoot;
        }
        if (!sourceRegistered) {
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(isolationRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new PersistenceException("Worktree cleanup 隔离状态不可确定");
            }
            return isolationRoot;
        }
        if (Files.exists(isolationRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceException("Worktree cleanup 隔离目录已被占用");
        }
        git.move(workspace.root(), source, isolationRoot);
        requireIsolated(source, isolationRoot);
        return isolationRoot;
    }

    private Path isolateForCleanupSafely(Workspace workspace, ManagedWorktree intent) {
        try {
            Path isolationRoot = isolateForCleanup(workspace, intent);
            cleanupObserver.isolated(intent, isolationRoot);
            return isolationRoot;
        } catch (RuntimeException failure) {
            markCleanupFailure(intent, ManagedWorktreeState.UNKNOWN_OUTCOME, failure);
            throw failure;
        }
    }

    private void requireIsolated(Path source, Path isolationRoot) {
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(isolationRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceException("Git 未完整隔离 Managed Worktree");
        }
    }

    private boolean isPresent(Workspace workspace, Path root) {
        return Files.exists(root, LinkOption.NOFOLLOW_LINKS) || git.isRegistered(workspace.root(), root);
    }

    private void verifyForCleanup(Workspace workspace, ManagedWorktree intent, Path isolationRoot) {
        try {
            verifyCurrentBackup(intent, isolationRoot);
        } catch (RuntimeException failure) {
            ManagedWorktreeState state = restoreAfterVerificationFailure(workspace, intent, isolationRoot, failure)
                    ? ManagedWorktreeState.FAILED
                    : ManagedWorktreeState.UNKNOWN_OUTCOME;
            markCleanupFailure(intent, state, failure);
            throw failure;
        }
    }

    private boolean restoreAfterVerificationFailure(
            Workspace workspace, ManagedWorktree intent, Path isolationRoot, RuntimeException original) {
        try {
            if (!git.isRegistered(workspace.root(), isolationRoot)) {
                return false;
            }
            if (Files.exists(intent.executionRoot(), LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            git.move(workspace.root(), isolationRoot, intent.executionRoot());
            return Files.isDirectory(intent.executionRoot(), LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(isolationRoot, LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException restoreFailure) {
            original.addSuppressed(restoreFailure);
            return false;
        }
    }

    private void deleteForCleanup(Workspace workspace, ManagedWorktree intent, Path isolationRoot) {
        try {
            cleanupDirectory(workspace, intent, isolationRoot);
        } catch (RuntimeException failure) {
            markCleanupFailure(intent, ManagedWorktreeState.UNKNOWN_OUTCOME, failure);
            throw failure;
        }
    }

    private void cleanupDirectory(Workspace workspace, ManagedWorktree intent, Path isolationRoot) {
        paths.requireManaged(intent);
        paths.requireCleanupIsolation(intent, isolationRoot);
        if (git.isRegistered(workspace.root(), isolationRoot)) {
            git.cleanup(workspace.root(), isolationRoot);
        }
        if (Files.exists(isolationRoot, LinkOption.NOFOLLOW_LINKS)
                || git.isRegistered(workspace.root(), isolationRoot)
                || Files.exists(intent.executionRoot(), LinkOption.NOFOLLOW_LINKS)
                || git.isRegistered(workspace.root(), intent.executionRoot())) {
            throw new PersistenceException("Git 未完整清理 Managed Worktree");
        }
    }

    private void markCleanupFailure(ManagedWorktree intent, ManagedWorktreeState state, RuntimeException original) {
        try {
            execute(connection -> {
                ManagedWorktree current = worktrees.lock(connection, intent.id());
                if (current.state() == ManagedWorktreeState.CLEANING) {
                    worktrees.transition(connection, current, state, current.backup(), now());
                }
                return null;
            });
        } catch (RuntimeException transitionFailure) {
            original.addSuppressed(transitionFailure);
        }
    }

    private ManagedWorktree finishCleanup(CommandIdentity identity, ManagedWorktree intent) {
        return execute(connection -> {
            Optional<IdempotencyRepository.StoredCommand> stored =
                    idempotency.find(connection, identity.idempotencyKey());
            if (stored.isPresent()) {
                return recover(identity, stored.orElseThrow(), ManagedWorktree.class);
            }
            ManagedWorktree current = worktrees.lock(connection, intent.id());
            boolean intended = current.state() == ManagedWorktreeState.CLEANING
                    && current.revision() == Math.addExact(identity.expectedRevision(), 2);
            if (!intended) {
                throw PersistenceException.revisionConflict("cleanup 意图 revision 已改变");
            }
            ManagedWorktree cleaned =
                    worktrees.transition(connection, current, ManagedWorktreeState.CLEANED, current.backup(), now());
            idempotency.insert(connection, identity, json.encode(cleaned), now());
            return cleaned;
        });
    }

    private AttachmentRef createPatchAttachment(
            CommandIdentity identity, ManagedWorktree current, ManagedWorktreeArtifactKind kind, byte[] patch) {
        String digest = ManagedWorktreePolicy.sha256(patch);
        ArtifactFingerprint fingerprint = new ArtifactFingerprint(current.id(), current.revision(), kind, digest);
        CommandIdentity attachmentIdentity = new CommandIdentity(
                "attachment/internal/store",
                identity.idempotencyKey() + ":attachment",
                0,
                json.encode(fingerprint).sha256());
        AttachmentMetadata metadata = attachments.store(
                AttachmentScope.workspace(current.workspaceId()), attachmentIdentity, PATCH_MEDIA_TYPE, patch);
        String filename = "worktree-" + current.id() + "-" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".patch";
        return new AttachmentRef(metadata.digest(), metadata.mediaType(), filename, metadata.sizeBytes());
    }

    private void verifyStableBackup(ManagedWorktree current, byte[] patch, AttachmentRef attachment) {
        AttachmentContent stored =
                attachments.read(AttachmentScope.workspace(current.workspaceId()), attachment.digest());
        if (!java.security.MessageDigest.isEqual(patch, stored.content())) {
            throw new PersistenceException("Backup Attachment 回读校验失败");
        }
        if (!java.security.MessageDigest.isEqual(patch, patch(current))) {
            throw PersistenceException.revisionConflict("Worktree 在 Backup 生成期间发生变化，请重试");
        }
    }

    private void verifyCurrentBackup(ManagedWorktree current, Path isolationRoot) {
        AttachmentRef backup =
                current.backup().orElseThrow(() -> PersistenceException.invalidRequest("cleanup 前必须先创建可验证 Backup"));
        AttachmentContent stored = attachments.read(AttachmentScope.workspace(current.workspaceId()), backup.digest());
        if (!java.security.MessageDigest.isEqual(stored.content(), patch(current, isolationRoot))) {
            throw PersistenceException.revisionConflict("Worktree 已在 Backup 后变化，拒绝 cleanup");
        }
    }

    private byte[] patch(ManagedWorktree current) {
        return patch(current, current.executionRoot());
    }

    private byte[] patch(ManagedWorktree current, Path executionRoot) {
        paths.requireManaged(current);
        if (!executionRoot.equals(current.executionRoot())) {
            paths.requireCleanupIsolation(current, executionRoot);
        }
        Workspace workspace = requireGitWorkspace(current.workspaceId());
        if (!git.isRegistered(workspace.root(), executionRoot)) {
            throw new PersistenceException("Managed Worktree 未在 Git 中注册");
        }
        return git.patch(workspace.root(), executionRoot, paths.scratch(current), current.baseCommit());
    }

    private Workspace requireGitWorkspace(WorkspaceId id) {
        Workspace workspace = execute(connection -> workspaces
                .find(connection, id)
                .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在")));
        if (!Files.exists(workspace.root().resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            throw PersistenceException.invalidRequest("Workspace 不是 Git checkout");
        }
        return workspace;
    }

    private ManagedWorktree find(WorktreeId id) {
        return execute(connection -> worktrees
                .find(connection, Objects.requireNonNull(id, "worktreeId"))
                .orElseThrow(() -> PersistenceException.invalidRequest("Managed Worktree 不存在")));
    }

    private <T> Optional<T> recover(CommandIdentity identity, Class<T> type) {
        return execute(connection ->
                idempotency.find(connection, identity.idempotencyKey()).map(stored -> recover(identity, stored, type)));
    }

    private <T> T recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored, Class<T> type) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同 Worktree 命令使用");
        }
        return json.decode(stored.response(), type);
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Managed Worktree Artifact 事务失败", failure);
        }
    }

    private record ArtifactFingerprint(
            WorktreeId worktreeId, long sourceRevision, ManagedWorktreeArtifactKind kind, String patchDigest) {}

    @FunctionalInterface
    interface CleanupIsolationObserver {
        void isolated(ManagedWorktree worktree, Path isolationRoot);
    }
}
