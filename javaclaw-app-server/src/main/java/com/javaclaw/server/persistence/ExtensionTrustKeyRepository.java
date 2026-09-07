package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.BundleRpcContracts;

/** Trust Key 持久化与撤销相关 Bundle 的同事务实时禁用。 */
public final class ExtensionTrustKeyRepository {
    private static final String SELECT = """
            SELECT KEY_ID, REVISION, STATE, FINGERPRINT, ATTACHMENT_DIGEST,
                   PUBLIC_KEY, CREATED_AT, UPDATED_AT
            FROM CORE.EXTENSION_TRUST_KEY
            """;

    private final H2Transactions transactions;
    private final Clock clock;

    /**
     * 创建 Trust Key 仓储。
     *
     * @param database data-v6 数据库
     * @param clock 平台时钟
     */
    public ExtensionTrustKeyRepository(H2Database database, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 导入新的 ACTIVE Trust Key。
     *
     * @param record 已校验记录
     * @return 持久化记录；完全相同的重试返回原值
     */
    public ExtensionTrustKeyRecord importKey(ExtensionTrustKeyRecord record) {
        Objects.requireNonNull(record, "record");
        return execute(connection -> {
            Optional<ExtensionTrustKeyRecord> existing =
                    find(connection, record.metadata().id());
            if (existing.isPresent()) {
                ExtensionTrustKeyRecord current = existing.orElseThrow();
                if (sameKey(current, record)) {
                    return current;
                }
                throw PersistenceException.revisionConflict("Trust Key 标识已被其他公钥占用");
            }
            insert(connection, record);
            return find(connection, record.metadata().id()).orElseThrow();
        });
    }

    /**
     * 列出全部 ACTIVE 与 REVOKED Trust Key。
     *
     * @return 按 keyId 排序的记录
     */
    public List<ExtensionTrustKeyRecord> list() {
        return execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT + " ORDER BY KEY_ID");
                    ResultSet result = statement.executeQuery()) {
                ArrayList<ExtensionTrustKeyRecord> records = new ArrayList<>();
                while (result.next()) {
                    records.add(map(result));
                }
                return List.copyOf(records);
            }
        });
    }

    /**
     * 读取 Trust Key。
     *
     * @param keyId 密钥标识
     * @return 记录
     */
    public Optional<ExtensionTrustKeyRecord> find(String keyId) {
        return execute(connection -> find(connection, keyId));
    }

    /**
     * 撤销密钥，并在同一事务内禁用该密钥签名的全部活动 Bundle。
     *
     * <p>事务提交后，新 Bundle 调用会在实时目录检查处失败；运行中的 Worker 不因此获得任何新调用。
     *
     * @param keyId 密钥标识
     * @param expectedRevision 当前密钥 revision
     * @return 新密钥元数据与被禁用扩展标识
     */
    public RevocationResult revoke(String keyId, long expectedRevision) {
        return execute(connection -> {
            ExtensionTrustKeyRecord current =
                    find(connection, keyId).orElseThrow(() -> PersistenceException.invalidRequest("Trust Key 不存在"));
            if (current.metadata().revision() != expectedRevision) {
                throw PersistenceException.revisionConflict("Trust Key revision 已改变");
            }
            if (current.metadata().state() == BundleRpcContracts.TrustState.REVOKED) {
                return new RevocationResult(current.metadata(), List.of());
            }
            long revision = Math.incrementExact(expectedRevision);
            updateRevoked(connection, keyId, revision);
            List<ExtensionId> disabled = disableBundles(connection, keyId);
            BundleRpcContracts.TrustKey key =
                    find(connection, keyId).orElseThrow().metadata();
            return new RevocationResult(key, disabled);
        });
    }

    private Optional<ExtensionTrustKeyRecord> find(Connection connection, String keyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE KEY_ID = ?")) {
            statement.setString(1, Objects.requireNonNull(keyId, "keyId"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private void insert(Connection connection, ExtensionTrustKeyRecord record) throws SQLException {
        BundleRpcContracts.TrustKey key = record.metadata();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.EXTENSION_TRUST_KEY
                    (KEY_ID, REVISION, STATE, FINGERPRINT, ATTACHMENT_DIGEST,
                     PUBLIC_KEY, CREATED_AT, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, key.id());
            statement.setLong(2, key.revision());
            statement.setString(3, key.state().name());
            statement.setString(4, key.fingerprint());
            statement.setString(5, key.attachmentDigest());
            statement.setBytes(6, record.encodedKey());
            statement.setObject(7, key.createdAt().atOffset(ZoneOffset.UTC));
            statement.setObject(8, key.updatedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private void updateRevoked(Connection connection, String keyId, long revision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.EXTENSION_TRUST_KEY
                SET REVISION = ?, STATE = ?, UPDATED_AT = ?
                WHERE KEY_ID = ?
                """)) {
            statement.setLong(1, revision);
            statement.setString(2, BundleRpcContracts.TrustState.REVOKED.name());
            statement.setObject(3, clock.instant().atOffset(ZoneOffset.UTC));
            statement.setString(4, keyId);
            statement.executeUpdate();
        }
    }

    private static List<ExtensionId> disableBundles(Connection connection, String keyId) throws SQLException {
        ArrayList<ExtensionId> affected = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT EXTENSION_ID FROM CORE.THIRD_PARTY_BUNDLE WHERE SIGNING_KEY_ID = ? ORDER BY EXTENSION_ID
                """)) {
            query.setString(1, keyId);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    affected.add(new ExtensionId(result.getString(1)));
                }
            }
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE CORE.EXTENSION SET STATE = ?, UPDATED_AT = CURRENT_TIMESTAMP
                WHERE ID IN (SELECT EXTENSION_ID FROM CORE.THIRD_PARTY_BUNDLE WHERE SIGNING_KEY_ID = ?)
                """)) {
            update.setString(1, ExtensionState.DISABLED.name());
            update.setString(2, keyId);
            update.executeUpdate();
        }
        return List.copyOf(affected);
    }

    private static ExtensionTrustKeyRecord map(ResultSet result) throws SQLException {
        var metadata = new BundleRpcContracts.TrustKey(
                result.getString("KEY_ID"),
                result.getString("FINGERPRINT"),
                result.getString("ATTACHMENT_DIGEST"),
                result.getLong("REVISION"),
                BundleRpcContracts.TrustState.valueOf(result.getString("STATE")),
                result.getObject("CREATED_AT", OffsetDateTime.class).toInstant(),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
        return new ExtensionTrustKeyRecord(metadata, result.getBytes("PUBLIC_KEY"));
    }

    private static boolean sameKey(ExtensionTrustKeyRecord current, ExtensionTrustKeyRecord candidate) {
        return current.metadata().state() == BundleRpcContracts.TrustState.ACTIVE
                && current.metadata().fingerprint().equals(candidate.metadata().fingerprint())
                && current.metadata()
                        .attachmentDigest()
                        .equals(candidate.metadata().attachmentDigest())
                && java.util.Arrays.equals(current.encodedKey(), candidate.encodedKey());
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Trust Key 事务失败", failure);
        }
    }

    /**
     * 撤销事务结果。
     *
     * @param key 新密钥状态
     * @param disabledExtensions 被实时禁用的 Bundle
     */
    public record RevocationResult(BundleRpcContracts.TrustKey key, List<ExtensionId> disabledExtensions) {
        /** 固定结果集合。 */
        public RevocationResult {
            Objects.requireNonNull(key, "key");
            disabledExtensions = List.copyOf(disabledExtensions);
        }
    }
}
