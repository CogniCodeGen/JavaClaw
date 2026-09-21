package com.javaclaw.server.security.vault;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;

/** 仅使用临时 H2 的本地主密钥测试夹具。 */
final class DatabaseVaultFixture {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T08:00:00Z"), ZoneOffset.UTC);
    final H2Database database;
    final H2Transactions transactions;
    final VaultRepository repository = new VaultRepository();

    DatabaseVaultFixture(Path root) {
        database = new H2Database(root.resolve("data-v6"));
        database.initialize();
        transactions = new H2Transactions(database);
    }

    DatabaseMasterKeyProtector local() {
        return new DatabaseMasterKeyProtector(database);
    }

    SecretVaultService vault(MasterKeyProtector protector) {
        return new SecretVaultService(database, protector, new CanonicalJson(), CLOCK, new SecureRandom());
    }

    CredentialMetadata create(SecretVaultService vault, String value) {
        return vault.create(identity("credential/create"), "provider", value.getBytes(StandardCharsets.UTF_8));
    }

    String activeKey() throws Exception {
        return transactions.execute(connection ->
                repository.keyState(connection, false).orElseThrow().activeKeyId());
    }

    long keyCount() throws Exception {
        return transactions.execute(connection -> {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM CORE.VAULT_LOCAL_MASTER_KEY")) {
                rows.next();
                return rows.getLong(1);
            }
        });
    }

    VaultRepository.StoredSecret secret(CredentialMetadata metadata) throws Exception {
        return transactions.execute(connection ->
                repository.find(connection, metadata.reference(), false).orElseThrow());
    }

    void sql(String sql) throws Exception {
        transactions.execute(connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }

    static CommandIdentity identity(String method) {
        return new CommandIdentity(method, UUID.randomUUID().toString(), 0, "0".repeat(64));
    }

    static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    static byte[] key(int value) {
        byte[] key = new byte[VaultCipher.KEY_BYTES];
        Arrays.fill(key, (byte) value);
        return key;
    }
}
