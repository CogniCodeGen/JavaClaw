package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.core.api.ThreadId;
import com.javaclaw.server.collaboration.WorktreeRecoveryUseCases.CleanupRequest;
import com.javaclaw.server.collaboration.WorktreeRepository;

/** H2 authority for managed Git worktree recovery and cleanup. */
public final class H2WorktreeRepository implements WorktreeRepository {
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();

    /** 绑定 App Server 持有的共享 H2Database；不另建连接工厂，数据库生命周期由装配层统一管理。 */
    public H2WorktreeRepository(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<WorktreeRecord> listRequiringCleanup() {
        return database.query(connection -> {
            ArrayList<WorktreeRecord> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM worktrees WHERE cleanup_required = TRUE
                    ORDER BY created_at, worktree_id
                    """);
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public Optional<WorktreeRecord> findByChild(ThreadId childThreadId) {
        Objects.requireNonNull(childThreadId, "childThreadId");
        return database.query(connection -> find(connection, childThreadId));
    }

    @Override
    public List<WorktreeRecord> listByWorkspace(String workspaceId, int limit) {
        if (limit < 1 || limit > 256) {
            throw new IllegalArgumentException("worktree limit must be 1–256");
        }
        return database.query(connection -> {
            var result = new ArrayList<WorktreeRecord>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM worktrees WHERE workspace_id = ? ORDER BY updated_at DESC, worktree_id LIMIT ?
                    """)) {
                query.setString(1, required(workspaceId, "workspaceId", 80));
                query.setInt(2, limit);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(read(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public WorktreeRecord create(WorktreeDraft draft) {
        Objects.requireNonNull(draft, "draft");
        String id = required(draft.id(), "worktreeId", 80);
        String baseline = required(draft.baselineCommit(), "baselineCommit", 128);
        Path path =
                Objects.requireNonNull(draft.path(), "path").toAbsolutePath().normalize();
        long now = System.currentTimeMillis();
        return database.transaction(connection -> {
            if (find(connection, draft.childThreadId()).isPresent()) {
                throw new IllegalStateException(
                        "child thread already owns a managed worktree: " + draft.childThreadId());
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO worktrees(worktree_id, workspace_id, parent_thread_id,
                        child_thread_id, path, baseline_hash, state, cleanup_required,
                        details, revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', TRUE, '', 1, ?, ?)
                    """)) {
                insert.setString(1, id);
                insert.setString(2, required(draft.workspaceId(), "workspaceId", 80));
                insert.setString(
                        3,
                        Objects.requireNonNull(draft.parentThreadId(), "parentThreadId")
                                .value());
                insert.setString(
                        4,
                        Objects.requireNonNull(draft.childThreadId(), "childThreadId")
                                .value());
                insert.setString(5, path.toString());
                insert.setString(6, baseline);
                insert.setLong(7, now);
                insert.setLong(8, now);
                insert.executeUpdate();
            }
            return find(connection, draft.childThreadId()).orElseThrow();
        });
    }

    @Override
    public WorktreeRecord setState(
            String id, State state, boolean cleanupRequired, String details, long expectedRevision) {
        String worktreeId = required(id, "worktreeId", 80);
        Objects.requireNonNull(state, "state");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        String safeDetails = details == null ? "" : details;
        if (safeDetails.length() > 100_000) {
            throw new IllegalArgumentException("worktree details exceed 100000 characters");
        }
        return database.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE worktrees SET state = ?, cleanup_required = ?, details = ?,
                        revision = revision + 1, updated_at = ?
                    WHERE worktree_id = ? AND revision = ?
                    """)) {
                update.setString(1, state.name());
                update.setBoolean(2, cleanupRequired);
                update.setString(3, safeDetails);
                update.setLong(4, System.currentTimeMillis());
                update.setString(5, worktreeId);
                update.setLong(6, expectedRevision);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("worktree revision conflict or missing record: " + worktreeId);
                }
            }
            try (PreparedStatement query =
                    connection.prepareStatement("SELECT * FROM worktrees WHERE worktree_id = ?")) {
                query.setString(1, worktreeId);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) {
                        throw new NoSuchElementException("worktree not found: " + worktreeId);
                    }
                    return read(row);
                }
            }
        });
    }

    @Override
    public Optional<WorktreeRecord> replayCleanup(CleanupRequest request) {
        return database.query(connection -> idempotency.replay(
                connection, "worktree/cleanup", request.key(), cleanupHash(request), WorktreeRecord.class));
    }

    @Override
    public Optional<WorktreeRecord> pendingCleanup(CleanupRequest request) {
        return database.query(connection -> idempotency.replay(
                connection, "worktree/cleanup/intent", request.key(), cleanupHash(request), WorktreeRecord.class));
    }

    @Override
    public WorktreeRecord beginCleanup(CleanupRequest request, String backupSha256) {
        if (backupSha256 != null && !backupSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid cleanup backup hash");
        }
        return database.transaction(connection -> {
            var pending = idempotency.replay(
                    connection, "worktree/cleanup/intent", request.key(), cleanupHash(request), WorktreeRecord.class);
            if (pending.isPresent()) {
                return pending.get();
            }
            WorktreeRecord current = lockCleanupTarget(connection, request);
            String details = current.details() + "; discardBackup=" + Objects.toString(backupSha256, "none");
            WorktreeRecord intent = new WorktreeRecord(
                    current.id(),
                    current.workspaceId(),
                    current.parentThreadId(),
                    current.childThreadId(),
                    current.path(),
                    current.baselineCommit(),
                    current.state(),
                    current.cleanupRequired(),
                    details,
                    current.revision(),
                    current.createdAt(),
                    current.updatedAt());
            // 先提交意图及备份引用，后执行外部 Git；重启不会把已经清理的目录当成新的删除授权。
            idempotency.record(
                    connection,
                    "worktree/cleanup/intent",
                    request.key(),
                    cleanupHash(request),
                    intent,
                    System.currentTimeMillis());
            return intent;
        });
    }

    @Override
    public WorktreeRecord finishCleanup(CleanupRequest request, boolean cleaned) {
        return database.transaction(connection -> {
            var replay = idempotency.replay(
                    connection, "worktree/cleanup", request.key(), cleanupHash(request), WorktreeRecord.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            WorktreeRecord intent = idempotency
                    .replay(
                            connection,
                            "worktree/cleanup/intent",
                            request.key(),
                            cleanupHash(request),
                            WorktreeRecord.class)
                    .orElseThrow(() -> new IllegalStateException("cleanup intent is missing"));
            WorktreeRecord current = lockCleanupTarget(connection, request);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE worktrees SET state = ?, cleanup_required = ?, details = ?,
                        revision = revision + 1, updated_at = ? WHERE worktree_id = ? AND revision = ?
                    """)) {
                update.setString(1, cleaned ? State.CLEANED.name() : State.ABANDONED.name());
                update.setBoolean(2, !cleaned);
                update.setString(3, intent.details());
                update.setLong(4, System.currentTimeMillis());
                update.setString(5, current.id());
                update.setLong(6, current.revision());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("worktree revision conflict");
                }
            }
            WorktreeRecord completed = find(connection, request.childThreadId()).orElseThrow();
            idempotency.record(
                    connection,
                    "worktree/cleanup",
                    request.key(),
                    cleanupHash(request),
                    completed,
                    System.currentTimeMillis());
            return completed;
        });
    }

    private static WorktreeRecord lockCleanupTarget(java.sql.Connection connection, CleanupRequest request)
            throws java.sql.SQLException {
        try (PreparedStatement query =
                connection.prepareStatement("SELECT * FROM worktrees WHERE child_thread_id = ? FOR UPDATE")) {
            query.setString(1, request.childThreadId().value());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("worktree is missing");
                }
                WorktreeRecord current = read(row);
                if (current.revision() != request.expectedRevision()) {
                    throw new IllegalStateException("worktree revision conflict");
                }
                if (!request.discardUnmerged() && current.state() != State.MERGED && current.state() != State.CLEANED) {
                    throw new IllegalStateException("unmerged worktree requires explicit discard confirmation");
                }
                return current;
            }
        }
    }

    private static String cleanupHash(CleanupRequest request) {
        return H2IdempotencyStore.requestHash(
                request.childThreadId(), request.expectedRevision(), request.discardUnmerged());
    }

    private static Optional<WorktreeRecord> find(java.sql.Connection connection, ThreadId child)
            throws java.sql.SQLException {
        try (PreparedStatement query =
                connection.prepareStatement("SELECT * FROM worktrees WHERE child_thread_id = ?")) {
            query.setString(1, child.value());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    private static WorktreeRecord read(ResultSet row) throws java.sql.SQLException {
        return new WorktreeRecord(
                row.getString("worktree_id"),
                row.getString("workspace_id"),
                new ThreadId(row.getString("parent_thread_id")),
                new ThreadId(row.getString("child_thread_id")),
                Path.of(row.getString("path")),
                row.getString("baseline_hash").strip(),
                State.valueOf(row.getString("state")),
                row.getBoolean("cleanup_required"),
                row.getString("details"),
                row.getLong("revision"),
                Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("updated_at")));
    }

    private static String required(String value, String name, int maximum) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty() || result.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return result;
    }
}
