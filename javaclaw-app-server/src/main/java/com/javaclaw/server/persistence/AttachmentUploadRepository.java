package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.AttachmentUploadSession;
import com.javaclaw.api.AttachmentUploadState;
import com.javaclaw.api.WorkspaceId;

/** 分块 Attachment 上传会话的 SQL、配额锁和行映射。 */
final class AttachmentUploadRepository {
    private static final String COLUMNS = """
            U.ID, U.SCOPE_KIND, U.WORKSPACE_ID, U.STATE, U.MEDIA_TYPE, U.EXPECTED_DIGEST, U.EXPECTED_BYTES,
            U.RECEIVED_BYTES, U.NEXT_CHUNK_INDEX, U.REVISION, U.ATTACHMENT_DIGEST,
            U.FAILURE_REASON, U.CREATED_AT, U.UPDATED_AT, U.EXPIRES_AT,
            U.MEDIA_TYPE AS ATTACHMENT_MEDIA_TYPE, A.BYTE_LENGTH AS ATTACHMENT_BYTES,
            A.CREATED_AT AS ATTACHMENT_CREATED_AT
            """;

    void lockQuota(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ID FROM CORE.ATTACHMENT_UPLOAD_QUOTA WHERE ID = 1 FOR UPDATE");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("Attachment upload quota row is missing");
            }
        }
    }

    ActiveUsage activeUsage(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS ACTIVE_COUNT, COALESCE(SUM(EXPECTED_BYTES), 0) AS RESERVED_BYTES
                FROM CORE.ATTACHMENT_UPLOAD WHERE STATE = 'ACTIVE'
                """);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return new ActiveUsage(result.getInt("ACTIVE_COUNT"), result.getLong("RESERVED_BYTES"));
        }
    }

    void insert(Connection connection, AttachmentUploadSession session) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.ATTACHMENT_UPLOAD (
                    ID, SCOPE_KIND, WORKSPACE_ID, STATE, MEDIA_TYPE, EXPECTED_DIGEST, EXPECTED_BYTES, RECEIVED_BYTES,
                    NEXT_CHUNK_INDEX, REVISION, ATTACHMENT_DIGEST, FAILURE_REASON,
                    CREATED_AT, UPDATED_AT, EXPIRES_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindSession(statement, session);
            statement.executeUpdate();
        }
    }

    Optional<AttachmentUploadSession> find(Connection connection, String uploadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT %s
                FROM CORE.ATTACHMENT_UPLOAD U
                LEFT JOIN CORE.ATTACHMENT A ON A.DIGEST = U.ATTACHMENT_DIGEST
                WHERE U.ID = ?
                """.formatted(COLUMNS))) {
            statement.setString(1, uploadId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    AttachmentUploadSession advance(
            Connection connection,
            String uploadId,
            long expectedRevision,
            int chunkBytes,
            Instant updatedAt,
            Instant expiresAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.ATTACHMENT_UPLOAD
                SET RECEIVED_BYTES = RECEIVED_BYTES + ?, NEXT_CHUNK_INDEX = NEXT_CHUNK_INDEX + 1,
                    REVISION = REVISION + 1, UPDATED_AT = ?, EXPIRES_AT = ?
                WHERE ID = ? AND STATE = 'ACTIVE' AND REVISION = ?
                """)) {
            statement.setInt(1, chunkBytes);
            statement.setObject(2, updatedAt.atOffset(ZoneOffset.UTC));
            statement.setObject(3, expiresAt.atOffset(ZoneOffset.UTC));
            statement.setString(4, uploadId);
            statement.setLong(5, expectedRevision);
            requireUpdated(statement.executeUpdate());
        }
        return find(connection, uploadId).orElseThrow();
    }

    AttachmentUploadSession complete(
            Connection connection,
            String uploadId,
            long expectedRevision,
            AttachmentMetadata attachment,
            Instant updatedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.ATTACHMENT_UPLOAD
                SET STATE = 'COMPLETED', REVISION = REVISION + 1, ATTACHMENT_DIGEST = ?, UPDATED_AT = ?
                WHERE ID = ? AND STATE = 'ACTIVE' AND REVISION = ? AND RECEIVED_BYTES = EXPECTED_BYTES
                """)) {
            statement.setString(1, attachment.digest());
            statement.setObject(2, updatedAt.atOffset(ZoneOffset.UTC));
            statement.setString(3, uploadId);
            statement.setLong(4, expectedRevision);
            requireUpdated(statement.executeUpdate());
        }
        return find(connection, uploadId).orElseThrow();
    }

    AttachmentUploadSession terminate(
            Connection connection,
            String uploadId,
            long expectedRevision,
            AttachmentUploadState state,
            String reason,
            Instant updatedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.ATTACHMENT_UPLOAD
                SET STATE = ?, REVISION = REVISION + 1, FAILURE_REASON = ?, UPDATED_AT = ?
                WHERE ID = ? AND STATE = 'ACTIVE' AND REVISION = ?
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, reason);
            statement.setObject(3, updatedAt.atOffset(ZoneOffset.UTC));
            statement.setString(4, uploadId);
            statement.setLong(5, expectedRevision);
            requireUpdated(statement.executeUpdate());
        }
        return find(connection, uploadId).orElseThrow();
    }

    List<String> expiredIds(Connection connection, Instant now) throws SQLException {
        return findExpired(connection, now);
    }

    boolean expireOne(Connection connection, String uploadId, Instant now, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.ATTACHMENT_UPLOAD
                SET STATE = 'EXPIRED', REVISION = REVISION + 1, FAILURE_REASON = ?, UPDATED_AT = ?
                WHERE ID = ? AND STATE = 'ACTIVE' AND EXPIRES_AT <= ?
                """)) {
            statement.setString(1, reason);
            statement.setObject(2, now.atOffset(ZoneOffset.UTC));
            statement.setString(3, uploadId);
            statement.setObject(4, now.atOffset(ZoneOffset.UTC));
            return statement.executeUpdate() == 1;
        }
    }

    Set<String> activeIds(Connection connection) throws SQLException {
        HashSet<String> ids = new HashSet<>();
        try (PreparedStatement statement =
                        connection.prepareStatement("SELECT ID FROM CORE.ATTACHMENT_UPLOAD WHERE STATE = 'ACTIVE'");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                ids.add(result.getString("ID"));
            }
        }
        return Set.copyOf(ids);
    }

    private static List<String> findExpired(Connection connection, Instant now) throws SQLException {
        ArrayList<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID FROM CORE.ATTACHMENT_UPLOAD
                WHERE STATE = 'ACTIVE' AND EXPIRES_AT <= ? FOR UPDATE
                """)) {
            statement.setObject(1, now.atOffset(ZoneOffset.UTC));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getString("ID"));
                }
            }
        }
        return List.copyOf(ids);
    }

    private static void bindSession(PreparedStatement statement, AttachmentUploadSession session) throws SQLException {
        statement.setString(1, session.id());
        statement.setString(2, session.scope().kind().name());
        statement.setString(
                3, session.scope().workspaceId().map(WorkspaceId::toString).orElse(null));
        statement.setString(4, session.state().name());
        statement.setString(5, session.mediaType());
        statement.setString(6, session.expectedDigest());
        statement.setLong(7, session.expectedSizeBytes());
        statement.setLong(8, session.receivedSizeBytes());
        statement.setInt(9, session.nextChunkIndex());
        statement.setLong(10, session.revision());
        statement.setString(
                11, session.attachment().map(AttachmentMetadata::digest).orElse(null));
        statement.setString(12, session.failureReason().orElse(null));
        statement.setObject(13, session.createdAt().atOffset(ZoneOffset.UTC));
        statement.setObject(14, session.updatedAt().atOffset(ZoneOffset.UTC));
        statement.setObject(15, session.expiresAt().atOffset(ZoneOffset.UTC));
    }

    private static AttachmentUploadSession map(ResultSet result) throws SQLException {
        String attachmentDigest = result.getString("ATTACHMENT_DIGEST");
        Optional<AttachmentMetadata> attachment = attachmentDigest == null
                ? Optional.empty()
                : Optional.of(new AttachmentMetadata(
                        attachmentDigest,
                        result.getString("ATTACHMENT_MEDIA_TYPE"),
                        result.getLong("ATTACHMENT_BYTES"),
                        instant(result, "ATTACHMENT_CREATED_AT")));
        return new AttachmentUploadSession(
                result.getString("ID"),
                scope(result),
                AttachmentUploadState.valueOf(result.getString("STATE")),
                result.getString("MEDIA_TYPE"),
                result.getString("EXPECTED_DIGEST"),
                result.getLong("EXPECTED_BYTES"),
                result.getLong("RECEIVED_BYTES"),
                result.getInt("NEXT_CHUNK_INDEX"),
                result.getLong("REVISION"),
                instant(result, "CREATED_AT"),
                instant(result, "UPDATED_AT"),
                instant(result, "EXPIRES_AT"),
                attachment,
                Optional.ofNullable(result.getString("FAILURE_REASON")));
    }

    private static AttachmentScope scope(ResultSet result) throws SQLException {
        AttachmentScope.Kind kind = AttachmentScope.Kind.valueOf(result.getString("SCOPE_KIND"));
        if (kind == AttachmentScope.Kind.GLOBAL) {
            return AttachmentScope.global();
        }
        return AttachmentScope.workspace(WorkspaceId.parse(result.getString("WORKSPACE_ID")));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static void requireUpdated(int rows) {
        if (rows != 1) {
            throw PersistenceException.revisionConflict("Attachment 上传状态或 revision 已变化");
        }
    }

    record ActiveUsage(int count, long reservedBytes) {}
}
