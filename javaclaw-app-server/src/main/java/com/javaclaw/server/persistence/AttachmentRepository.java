package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;

/** Core 附件元数据的 SQL 与行映射。 */
final class AttachmentRepository {
    Optional<StoredBlob> findBlob(Connection connection, String digest) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DIGEST, BYTE_LENGTH, BLOB_PATH, CREATED_AT
                FROM CORE.ATTACHMENT WHERE DIGEST = ?
                """)) {
            statement.setString(1, digest);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapBlob(result)) : Optional.empty();
            }
        }
    }

    Optional<StoredAttachment> findOwned(Connection connection, AttachmentScope scope, String digest)
            throws SQLException {
        String sql = scope.kind() == AttachmentScope.Kind.GLOBAL ? """
                  SELECT A.DIGEST, C.MEDIA_TYPE, A.BYTE_LENGTH, A.BLOB_PATH, A.CREATED_AT
                  FROM CORE.ATTACHMENT A
                  JOIN CORE.ATTACHMENT_GLOBAL_CLAIM C ON C.ATTACHMENT_DIGEST = A.DIGEST
                  WHERE A.DIGEST = ?
                  """ : """
                  SELECT A.DIGEST, C.MEDIA_TYPE, A.BYTE_LENGTH, A.BLOB_PATH, A.CREATED_AT
                  FROM CORE.ATTACHMENT A
                  JOIN CORE.ATTACHMENT_WORKSPACE_CLAIM C ON C.ATTACHMENT_DIGEST = A.DIGEST
                  WHERE C.WORKSPACE_ID = ? AND A.DIGEST = ?
                  """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (scope.kind() == AttachmentScope.Kind.WORKSPACE) {
                statement.setString(index++, scope.workspaceId().orElseThrow().toString());
            }
            statement.setString(index, digest);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapOwned(result)) : Optional.empty();
            }
        }
    }

    void insertBlob(Connection connection, StoredBlob value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.ATTACHMENT (DIGEST, BYTE_LENGTH, BLOB_PATH, CREATED_AT)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, value.digest());
            statement.setLong(2, value.sizeBytes());
            statement.setString(3, value.relativePath());
            statement.setObject(4, value.createdAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    void claim(Connection connection, AttachmentScope scope, String digest, String mediaType, Instant createdAt)
            throws SQLException {
        Optional<String> current = findClaimMediaType(connection, scope, digest);
        if (current.isPresent()) {
            if (!current.orElseThrow().equals(mediaType)) {
                throw PersistenceException.invalidRequest("同一所有权范围中的 Attachment 摘要已绑定其他 mediaType");
            }
            return;
        }
        if (scope.kind() == AttachmentScope.Kind.GLOBAL) {
            claimGlobal(connection, digest, mediaType, createdAt);
            return;
        }
        claimWorkspace(connection, scope, digest, mediaType, createdAt);
    }

    private Optional<String> findClaimMediaType(Connection connection, AttachmentScope scope, String digest)
            throws SQLException {
        String sql = scope.kind() == AttachmentScope.Kind.GLOBAL
                ? "SELECT MEDIA_TYPE FROM CORE.ATTACHMENT_GLOBAL_CLAIM WHERE ATTACHMENT_DIGEST = ?"
                : """
                  SELECT MEDIA_TYPE FROM CORE.ATTACHMENT_WORKSPACE_CLAIM
                  WHERE WORKSPACE_ID = ? AND ATTACHMENT_DIGEST = ?
                  """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (scope.kind() == AttachmentScope.Kind.WORKSPACE) {
                statement.setString(index++, scope.workspaceId().orElseThrow().toString());
            }
            statement.setString(index, digest);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("MEDIA_TYPE")) : Optional.empty();
            }
        }
    }

    private static void claimGlobal(Connection connection, String digest, String mediaType, Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.ATTACHMENT_GLOBAL_CLAIM (ATTACHMENT_DIGEST, MEDIA_TYPE, CREATED_AT)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, digest);
            statement.setString(2, mediaType);
            statement.setObject(3, createdAt.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static void claimWorkspace(
            Connection connection, AttachmentScope scope, String digest, String mediaType, Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.ATTACHMENT_WORKSPACE_CLAIM
                    (WORKSPACE_ID, ATTACHMENT_DIGEST, MEDIA_TYPE, CREATED_AT)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, scope.workspaceId().orElseThrow().toString());
            statement.setString(2, digest);
            statement.setString(3, mediaType);
            statement.setObject(4, createdAt.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static StoredAttachment mapOwned(ResultSet result) throws SQLException {
        Instant createdAt = result.getObject("CREATED_AT", OffsetDateTime.class).toInstant();
        AttachmentMetadata metadata = new AttachmentMetadata(
                result.getString("DIGEST"), result.getString("MEDIA_TYPE"), result.getLong("BYTE_LENGTH"), createdAt);
        return new StoredAttachment(metadata, result.getString("BLOB_PATH"));
    }

    private static StoredBlob mapBlob(ResultSet result) throws SQLException {
        return new StoredBlob(
                result.getString("DIGEST"),
                result.getLong("BYTE_LENGTH"),
                result.getString("BLOB_PATH"),
                result.getObject("CREATED_AT", OffsetDateTime.class).toInstant());
    }

    record StoredAttachment(AttachmentMetadata metadata, String relativePath) {}

    record StoredBlob(String digest, long sizeBytes, String relativePath, Instant createdAt) {}
}
