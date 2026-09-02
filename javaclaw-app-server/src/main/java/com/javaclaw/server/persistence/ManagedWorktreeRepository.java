package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;

/** Managed Worktree 当前状态的 SQL 与行映射。 */
final class ManagedWorktreeRepository {
    private static final String COLUMNS = """
            W.ID, W.WORKSPACE_ID, W.PARENT_THREAD_ID, W.CHILD_THREAD_ID, W.EXECUTION_ROOT,
            W.BASE_COMMIT, W.STATE, W.REVISION, W.BACKUP_DIGEST, W.CREATED_AT, W.UPDATED_AT,
            COALESCE(WC.MEDIA_TYPE, GC.MEDIA_TYPE) AS BACKUP_MEDIA_TYPE,
            A.BYTE_LENGTH AS BACKUP_BYTE_LENGTH
            """;
    private static final String ATTACHMENT_JOINS = """
             LEFT JOIN CORE.ATTACHMENT A ON A.DIGEST = W.BACKUP_DIGEST
             LEFT JOIN CORE.ATTACHMENT_WORKSPACE_CLAIM WC
                ON WC.ATTACHMENT_DIGEST = W.BACKUP_DIGEST AND WC.WORKSPACE_ID = W.WORKSPACE_ID
             LEFT JOIN CORE.ATTACHMENT_GLOBAL_CLAIM GC ON GC.ATTACHMENT_DIGEST = W.BACKUP_DIGEST
            """;

    List<ManagedWorktree> list(Connection connection, WorkspaceId workspaceId, boolean includeCleaned)
            throws SQLException {
        String cleanupFilter = includeCleaned ? "" : " AND W.STATE <> 'CLEANED'";
        String query = "SELECT " + COLUMNS + " FROM CORE.WORKTREE W" + ATTACHMENT_JOINS + " WHERE W.WORKSPACE_ID = ?"
                + cleanupFilter + " ORDER BY W.CREATED_AT, W.ID";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, workspaceId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<ManagedWorktree> values = new ArrayList<>();
                while (result.next()) {
                    values.add(map(result));
                }
                return List.copyOf(values);
            }
        }
    }

    Optional<ManagedWorktree> find(Connection connection, WorktreeId id) throws SQLException {
        return find(connection, "W.ID = ?", id.toString());
    }

    Optional<ManagedWorktree> findByChild(Connection connection, ThreadId childThreadId) throws SQLException {
        return find(connection, "W.CHILD_THREAD_ID = ?", childThreadId.toString());
    }

    ManagedWorktree lock(Connection connection, WorktreeId id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT ID FROM CORE.WORKTREE WHERE ID = ? FOR UPDATE")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw PersistenceException.invalidRequest("Managed Worktree 不存在");
                }
            }
        }
        return find(connection, id).orElseThrow();
    }

    void insert(Connection connection, ManagedWorktree worktree) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.WORKTREE (
                    ID, WORKSPACE_ID, PARENT_THREAD_ID, CHILD_THREAD_ID, EXECUTION_ROOT, BASE_COMMIT,
                    STATE, REVISION, BACKUP_DIGEST, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, worktree.id().toString());
            statement.setString(2, worktree.workspaceId().toString());
            statement.setString(3, worktree.parentThreadId().toString());
            statement.setString(4, worktree.childThreadId().toString());
            statement.setString(5, worktree.executionRoot().toString());
            statement.setString(6, worktree.baseCommit());
            statement.setString(7, worktree.state().name());
            statement.setLong(8, worktree.revision());
            statement.setString(9, worktree.backup().map(AttachmentRef::digest).orElse(null));
            statement.setObject(10, worktree.createdAt().atOffset(ZoneOffset.UTC));
            statement.setObject(11, worktree.updatedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    ManagedWorktree transition(
            Connection connection,
            ManagedWorktree current,
            ManagedWorktreeState state,
            Optional<AttachmentRef> backup,
            java.time.Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.WORKTREE
                SET STATE = ?, REVISION = REVISION + 1, BACKUP_DIGEST = ?, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ?
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, backup.map(AttachmentRef::digest).orElse(null));
            statement.setObject(3, now.atOffset(ZoneOffset.UTC));
            statement.setString(4, current.id().toString());
            statement.setLong(5, current.revision());
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("Managed Worktree revision 已改变");
            }
        }
        return find(connection, current.id()).orElseThrow();
    }

    private Optional<ManagedWorktree> find(Connection connection, String predicate, String value) throws SQLException {
        String query = "SELECT " + COLUMNS + " FROM CORE.WORKTREE W" + ATTACHMENT_JOINS + " WHERE " + predicate;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private static ManagedWorktree map(ResultSet result) throws SQLException {
        WorktreeId id = WorktreeId.parse(result.getString("ID"));
        String digest = result.getString("BACKUP_DIGEST");
        Optional<AttachmentRef> backup = mapBackup(result, id, digest);
        return new ManagedWorktree(
                id,
                WorkspaceId.parse(result.getString("WORKSPACE_ID")),
                ThreadId.parse(result.getString("PARENT_THREAD_ID")),
                ThreadId.parse(result.getString("CHILD_THREAD_ID")),
                Path.of(result.getString("EXECUTION_ROOT")),
                result.getString("BASE_COMMIT"),
                ManagedWorktreeState.valueOf(result.getString("STATE")),
                result.getLong("REVISION"),
                backup,
                result.getObject("CREATED_AT", OffsetDateTime.class).toInstant(),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }

    private static Optional<AttachmentRef> mapBackup(ResultSet result, WorktreeId id, String digest)
            throws SQLException {
        if (digest == null) {
            return Optional.empty();
        }
        String mediaType = result.getString("BACKUP_MEDIA_TYPE");
        long sizeBytes = result.getLong("BACKUP_BYTE_LENGTH");
        if (mediaType == null || result.wasNull()) {
            throw new PersistenceException("Managed Worktree Backup 缺少可用的 Attachment ownership claim");
        }
        return Optional.of(new AttachmentRef(digest, mediaType, "worktree-" + id + "-backup.patch", sizeBytes));
    }
}
