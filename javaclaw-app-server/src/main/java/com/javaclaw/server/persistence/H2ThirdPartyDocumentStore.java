package com.javaclaw.server.persistence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExtensionDocumentStore;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.VersionedDocument;

/** H2 与内容寻址文件共同实现的第三方扩展限额存储。 */
public final class H2ThirdPartyDocumentStore implements ExtensionDocumentStore {
    private static final String COLLECTION = "default";
    private final H2Transactions transactions;
    private final ExtensionId extensionId;
    private final ThirdPartyStorageQuota quota;
    private final ThirdPartyBlobStore blobs;
    private final Clock clock;

    /**
     * 创建绑定到单个扩展命名空间的存储。
     *
     * @param database data-v6 数据库
     * @param extensionId 第三方扩展标识
     * @param quota 存储上限
     * @param clock 平台时钟
     */
    public H2ThirdPartyDocumentStore(
            H2Database database, ExtensionId extensionId, ThirdPartyStorageQuota quota, Clock clock) {
        H2Database checkedDatabase = Objects.requireNonNull(database, "database");
        transactions = new H2Transactions(checkedDatabase);
        this.extensionId = Objects.requireNonNull(extensionId, "extensionId");
        this.quota = Objects.requireNonNull(quota, "quota");
        this.clock = Objects.requireNonNull(clock, "clock");
        blobs = new ThirdPartyBlobStore(checkedDatabase.dataRoot(), extensionId.value());
    }

    @Override
    public Optional<VersionedDocument> get(String key) {
        String normalized = key(key);
        return execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT REVISION, PAYLOAD, UPDATED_AT
                    FROM CORE.THIRD_PARTY_DOCUMENT
                    WHERE EXTENSION_ID = ? AND COLLECTION_NAME = ? AND DOCUMENT_KEY = ?
                    """)) {
                statement.setString(1, extensionId.value());
                statement.setString(2, COLLECTION);
                statement.setString(3, normalized);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new VersionedDocument(
                            normalized,
                            result.getLong("REVISION"),
                            new CanonicalPayload(result.getString("PAYLOAD")),
                            result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant()));
                }
            }
        });
    }

    @Override
    public long put(String key, long expectedRevision, CanonicalPayload payload) {
        String normalized = key(key);
        Objects.requireNonNull(payload, "payload");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        long byteLength = payload.json().getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > quota.documentBytes()) {
            throw new IllegalArgumentException("document exceeds per-item quota");
        }
        return execute(connection -> {
            Optional<Long> current = currentRevision(connection, normalized);
            long currentRevision = current.orElse(0L);
            if (currentRevision != expectedRevision) {
                throw PersistenceException.revisionConflict("第三方文档 revision 已改变");
            }
            requireDocumentTotal(connection, normalized, byteLength);
            long revision = Math.addExact(currentRevision, 1);
            if (current.isEmpty()) {
                insertDocument(connection, normalized, revision, payload, byteLength);
            } else {
                updateDocument(connection, normalized, revision, payload, byteLength);
            }
            return revision;
        });
    }

    @Override
    public String putBlob(InputStream content, long sizeBytes, String mediaType) {
        Objects.requireNonNull(content, "content");
        text(mediaType, "mediaType");
        if (sizeBytes < 0 || sizeBytes > quota.blobBytes() || sizeBytes > Integer.MAX_VALUE) {
            close(content);
            throw new IllegalArgumentException("blob size exceeds per-item quota");
        }
        byte[] bytes = readExact(content, Math.toIntExact(sizeBytes));
        String digest = digest(bytes);
        return execute(connection -> {
            Optional<Long> existing = blobSize(connection, digest);
            if (existing.isPresent()) {
                if (existing.orElseThrow() != sizeBytes) {
                    throw new PersistenceException("第三方 Blob 摘要与大小冲突");
                }
                blobs.write(digest, bytes);
                return digest;
            }
            requireBlobTotal(connection, sizeBytes);
            String path = blobs.write(digest, bytes);
            if (blobSize(connection, digest).isPresent()) {
                throw new PersistenceException("第三方 Blob 并发写入冲突");
            }
            insertBlob(connection, digest, sizeBytes, path);
            return digest;
        });
    }

    private Optional<Long> currentRevision(java.sql.Connection connection, String key) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT REVISION FROM CORE.THIRD_PARTY_DOCUMENT
                WHERE EXTENSION_ID = ? AND COLLECTION_NAME = ? AND DOCUMENT_KEY = ? FOR UPDATE
                """)) {
            statement.setString(1, extensionId.value());
            statement.setString(2, COLLECTION);
            statement.setString(3, key);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getLong(1)) : Optional.empty();
            }
        }
    }

    private void requireDocumentTotal(java.sql.Connection connection, String key, long replacementBytes)
            throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(BYTE_LENGTH), 0) FROM CORE.THIRD_PARTY_DOCUMENT
                WHERE EXTENSION_ID = ? AND NOT (COLLECTION_NAME = ? AND DOCUMENT_KEY = ?)
                """)) {
            statement.setString(1, extensionId.value());
            statement.setString(2, COLLECTION);
            statement.setString(3, key);
            try (var result = statement.executeQuery()) {
                result.next();
                if (Math.addExact(result.getLong(1), replacementBytes) > quota.totalDocumentBytes()) {
                    throw new IllegalArgumentException("document namespace exceeds total quota");
                }
            }
        }
    }

    private void insertDocument(
            java.sql.Connection connection, String key, long revision, CanonicalPayload payload, long byteLength)
            throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.THIRD_PARTY_DOCUMENT
                    (EXTENSION_ID, COLLECTION_NAME, DOCUMENT_KEY, REVISION, PAYLOAD, BYTE_LENGTH, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindDocument(statement, key, revision, payload, byteLength);
            statement.executeUpdate();
        }
    }

    private void updateDocument(
            java.sql.Connection connection, String key, long revision, CanonicalPayload payload, long byteLength)
            throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE CORE.THIRD_PARTY_DOCUMENT
                SET REVISION = ?, PAYLOAD = ?, BYTE_LENGTH = ?, UPDATED_AT = ?
                WHERE EXTENSION_ID = ? AND COLLECTION_NAME = ? AND DOCUMENT_KEY = ?
                """)) {
            statement.setLong(1, revision);
            statement.setString(2, payload.json());
            statement.setLong(3, byteLength);
            statement.setObject(4, Instant.now(clock).atOffset(ZoneOffset.UTC));
            statement.setString(5, extensionId.value());
            statement.setString(6, COLLECTION);
            statement.setString(7, key);
            statement.executeUpdate();
        }
    }

    private void bindDocument(
            java.sql.PreparedStatement statement, String key, long revision, CanonicalPayload payload, long byteLength)
            throws java.sql.SQLException {
        statement.setString(1, extensionId.value());
        statement.setString(2, COLLECTION);
        statement.setString(3, key);
        statement.setLong(4, revision);
        statement.setString(5, payload.json());
        statement.setLong(6, byteLength);
        statement.setObject(7, Instant.now(clock).atOffset(ZoneOffset.UTC));
    }

    private Optional<Long> blobSize(java.sql.Connection connection, String digest) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT BYTE_LENGTH FROM CORE.THIRD_PARTY_BLOB WHERE EXTENSION_ID = ? AND DIGEST = ?
                """)) {
            statement.setString(1, extensionId.value());
            statement.setString(2, digest);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getLong(1)) : Optional.empty();
            }
        }
    }

    private void requireBlobTotal(java.sql.Connection connection, long addedBytes) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(BYTE_LENGTH), 0) FROM CORE.THIRD_PARTY_BLOB WHERE EXTENSION_ID = ?
                """)) {
            statement.setString(1, extensionId.value());
            try (var result = statement.executeQuery()) {
                result.next();
                if (Math.addExact(result.getLong(1), addedBytes) > quota.totalBlobBytes()) {
                    throw new IllegalArgumentException("blob namespace exceeds total quota");
                }
            }
        }
    }

    private void insertBlob(java.sql.Connection connection, String digest, long size, String path)
            throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.THIRD_PARTY_BLOB
                    (EXTENSION_ID, DIGEST, BYTE_LENGTH, BLOB_PATH, CREATED_AT)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, extensionId.value());
            statement.setString(2, digest);
            statement.setLong(3, size);
            statement.setString(4, path);
            statement.setObject(5, Instant.now(clock).atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static byte[] readExact(InputStream content, int expected) {
        try (InputStream input = content;
                ByteArrayOutputStream output = new ByteArrayOutputStream(expected)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > expected) {
                    throw new IllegalArgumentException("blob contains more bytes than declared");
                }
                output.write(buffer, 0, read);
            }
            if (output.size() != expected) {
                throw new IllegalArgumentException("blob contains fewer bytes than declared");
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new PersistenceException("第三方 Blob 读取失败", failure);
        }
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String key(String value) {
        String normalized = text(value, "key");
        if (normalized.length() > 500 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("document key is too long or contains NUL");
        }
        return normalized;
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // 参数错误仍必须释放由平台接管的输入流。
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("第三方文档事务失败", failure);
        }
    }
}
