package com.javaclaw.server.security.vault;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;

/** 本地主密钥的固定 SQL；字节只绑定参数，不能进入 SQL 文本、日志或诊断。 */
final class LocalMasterKeyRepository {
    Optional<byte[]> find(Connection connection, String keyId) throws SQLException {
        byte[] key = null;
        try (var statement =
                connection.prepareStatement("SELECT KEY_BYTES FROM CORE.VAULT_LOCAL_MASTER_KEY WHERE KEY_ID = ?")) {
            statement.setString(1, keyId);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    key = rows.getBytes(1);
                }
            }
        } catch (SQLException | RuntimeException failure) {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
            throw failure;
        }
        return Optional.ofNullable(key);
    }

    void store(Connection connection, String keyId, byte[] key) throws SQLException {
        try (var statement =
                connection.prepareStatement("UPDATE CORE.VAULT_LOCAL_MASTER_KEY SET KEY_BYTES = ? WHERE KEY_ID = ?")) {
            statement.setBytes(1, key);
            statement.setString(2, keyId);
            if (statement.executeUpdate() == 1) {
                return;
            }
        }
        // INSERT 对并发首次写入明确报冲突，避免 H2 MERGE 在旧 SERIALIZABLE 快照上反复尝试插入。
        try (var statement = connection.prepareStatement(
                "INSERT INTO CORE.VAULT_LOCAL_MASTER_KEY (KEY_ID, KEY_BYTES) VALUES (?, ?)")) {
            statement.setString(1, keyId);
            statement.setBytes(2, key);
            statement.executeUpdate();
        }
    }

    void delete(Connection connection, String keyId) throws SQLException {
        try (var statement = connection.prepareStatement("DELETE FROM CORE.VAULT_LOCAL_MASTER_KEY WHERE KEY_ID = ?")) {
            statement.setString(1, keyId);
            statement.executeUpdate();
        }
    }
}
