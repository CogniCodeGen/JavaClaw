package com.javaclaw.server.security.vault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;

/** Vault 元数据与密文的固定 SQL；任何 Secret 明文都不得进入该层。 */
final class VaultRepository {
    Optional<KeyState> keyState(Connection connection, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ACTIVE_KEY_ID, PREVIOUS_KEY_ID
                FROM CORE.VAULT_KEY_STATE WHERE SINGLETON = TRUE
                """ + suffix);
                ResultSet result = statement.executeQuery()) {
            return result.next()
                    ? Optional.of(new KeyState(result.getString("ACTIVE_KEY_ID"), result.getString("PREVIOUS_KEY_ID")))
                    : Optional.empty();
        }
    }

    void insertKeyState(Connection connection, String keyId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.VAULT_KEY_STATE (
                    SINGLETON, ACTIVE_KEY_ID, PREVIOUS_KEY_ID, UPDATED_AT
                ) VALUES (TRUE, ?, NULL, ?)
                """)) {
            statement.setString(1, keyId);
            statement.setObject(2, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    void rotateKeyState(Connection connection, String activeKeyId, String previousKeyId, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.VAULT_KEY_STATE
                SET ACTIVE_KEY_ID = ?, PREVIOUS_KEY_ID = ?, UPDATED_AT = ?
                WHERE SINGLETON = TRUE
                """)) {
            statement.setString(1, activeKeyId);
            statement.setString(2, previousKeyId);
            statement.setObject(3, now.atOffset(ZoneOffset.UTC));
            requireSingleRow(statement.executeUpdate(), "Vault key state is missing");
        }
    }

    void clearPreviousKey(Connection connection, String previousKeyId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.VAULT_KEY_STATE
                SET PREVIOUS_KEY_ID = NULL, UPDATED_AT = ?
                WHERE SINGLETON = TRUE AND PREVIOUS_KEY_ID = ?
                """)) {
            statement.setObject(1, now.atOffset(ZoneOffset.UTC));
            statement.setString(2, previousKeyId);
            statement.executeUpdate();
        }
    }

    long count(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM CORE.CREDENTIAL_SECRET");
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    Optional<StoredSecret> find(Connection connection, CredentialRef reference, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT NAMESPACE, SECRET_ID, REVISION, FORMAT_VERSION, NONCE, CIPHERTEXT, UPDATED_AT
                FROM CORE.CREDENTIAL_SECRET
                WHERE NAMESPACE = ? AND SECRET_ID = ?
                """ + suffix)) {
            statement.setString(1, reference.namespace());
            statement.setString(2, reference.id());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    List<StoredSecret> listForUpdate(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT NAMESPACE, SECRET_ID, REVISION, FORMAT_VERSION, NONCE, CIPHERTEXT, UPDATED_AT
                FROM CORE.CREDENTIAL_SECRET
                ORDER BY NAMESPACE, SECRET_ID FOR UPDATE
                """);
                ResultSet result = statement.executeQuery()) {
            List<StoredSecret> secrets = new ArrayList<>();
            while (result.next()) {
                secrets.add(map(result));
            }
            return List.copyOf(secrets);
        }
    }

    List<CredentialMetadata> listMetadata(Connection connection, String namespace) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT NAMESPACE, SECRET_ID, REVISION, UPDATED_AT
                FROM CORE.CREDENTIAL_SECRET
                WHERE NAMESPACE = ?
                ORDER BY SECRET_ID
                """)) {
            statement.setString(1, namespace);
            try (ResultSet result = statement.executeQuery()) {
                List<CredentialMetadata> metadata = new ArrayList<>();
                while (result.next()) {
                    metadata.add(new CredentialMetadata(
                            new CredentialRef(result.getString("NAMESPACE"), result.getString("SECRET_ID")),
                            result.getLong("REVISION"),
                            result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant()));
                }
                return List.copyOf(metadata);
            }
        }
    }

    void insert(Connection connection, StoredSecret secret) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.CREDENTIAL_SECRET (
                    NAMESPACE, SECRET_ID, REVISION, FORMAT_VERSION, NONCE, CIPHERTEXT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, secret);
            statement.executeUpdate();
        }
    }

    void replace(Connection connection, StoredSecret secret) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.CREDENTIAL_SECRET
                SET REVISION = ?, FORMAT_VERSION = ?, NONCE = ?, CIPHERTEXT = ?, UPDATED_AT = ?
                WHERE NAMESPACE = ? AND SECRET_ID = ?
                """)) {
            statement.setLong(1, secret.metadata().revision());
            statement.setInt(2, secret.encrypted().formatVersion());
            statement.setBytes(3, secret.encrypted().nonce());
            statement.setBytes(4, secret.encrypted().ciphertext());
            statement.setObject(5, secret.metadata().updatedAt().atOffset(ZoneOffset.UTC));
            statement.setString(6, secret.metadata().reference().namespace());
            statement.setString(7, secret.metadata().reference().id());
            requireSingleRow(statement.executeUpdate(), "Vault Secret is missing");
        }
    }

    void replaceCipher(Connection connection, StoredSecret secret) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.CREDENTIAL_SECRET
                SET FORMAT_VERSION = ?, NONCE = ?, CIPHERTEXT = ?
                WHERE NAMESPACE = ? AND SECRET_ID = ? AND REVISION = ?
                """)) {
            statement.setInt(1, secret.encrypted().formatVersion());
            statement.setBytes(2, secret.encrypted().nonce());
            statement.setBytes(3, secret.encrypted().ciphertext());
            statement.setString(4, secret.metadata().reference().namespace());
            statement.setString(5, secret.metadata().reference().id());
            statement.setLong(6, secret.metadata().revision());
            requireSingleRow(statement.executeUpdate(), "Vault Secret changed during key rotation");
        }
    }

    void delete(Connection connection, CredentialRef reference) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM CORE.CREDENTIAL_SECRET WHERE NAMESPACE = ? AND SECRET_ID = ?
                """)) {
            statement.setString(1, reference.namespace());
            statement.setString(2, reference.id());
            requireSingleRow(statement.executeUpdate(), "Vault Secret is missing");
        }
    }

    void deleteAll(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM CORE.CREDENTIAL_SECRET")) {
            statement.executeUpdate();
        }
    }

    private static StoredSecret map(ResultSet result) throws SQLException {
        CredentialRef reference = new CredentialRef(result.getString("NAMESPACE"), result.getString("SECRET_ID"));
        CredentialMetadata metadata = new CredentialMetadata(
                reference,
                result.getLong("REVISION"),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
        EncryptedSecret encrypted = new EncryptedSecret(
                result.getInt("FORMAT_VERSION"), result.getBytes("NONCE"), result.getBytes("CIPHERTEXT"));
        return new StoredSecret(metadata, encrypted);
    }

    private static void bind(PreparedStatement statement, StoredSecret secret) throws SQLException {
        CredentialMetadata metadata = secret.metadata();
        statement.setString(1, metadata.reference().namespace());
        statement.setString(2, metadata.reference().id());
        statement.setLong(3, metadata.revision());
        statement.setInt(4, secret.encrypted().formatVersion());
        statement.setBytes(5, secret.encrypted().nonce());
        statement.setBytes(6, secret.encrypted().ciphertext());
        statement.setObject(7, metadata.updatedAt().atOffset(ZoneOffset.UTC));
    }

    private static void requireSingleRow(int changed, String message) throws SQLException {
        if (changed != 1) {
            throw new SQLException(message);
        }
    }

    record KeyState(String activeKeyId, String previousKeyId) {}

    record StoredSecret(CredentialMetadata metadata, EncryptedSecret encrypted) {}
}
