package com.javaclaw.server.security.vault;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.IdempotentCommandStore;
import com.javaclaw.server.persistence.PersistenceException;

/** Vault Secret 行与幂等回执的窄事务端口；该类型永不接收 Provider 领域对象。 */
final class VaultCredentialTransactions {
    private final H2Transactions transactions;
    private final VaultRepository repository = new VaultRepository();
    private final IdempotentCommandStore commands = new IdempotentCommandStore();
    private final CanonicalJson json;
    private final Clock clock;
    private final VaultCipher cipher;

    VaultCredentialTransactions(
            H2Database database, CanonicalJson json, Clock clock, java.security.SecureRandom random) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        cipher = new VaultCipher(Objects.requireNonNull(random, "random"));
    }

    CredentialMetadata create(
            CommandIdentity identity, String namespace, byte[] secret, byte[] key, String activeKeyId) {
        Optional<CredentialMetadata> recovered = recover(identity, CredentialMetadata.class);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        try (PreparedProviderCredentialMutation prepared = prepareCreate(namespace, secret, key, activeKeyId)) {
            return commit(identity, prepared, CredentialMetadata.class, ignored -> prepared.metadata());
        }
    }

    CredentialMetadata rotate(
            CommandIdentity identity, CredentialRef reference, byte[] secret, byte[] key, String activeKeyId) {
        Optional<CredentialMetadata> recovered = recover(identity, CredentialMetadata.class);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        try (PreparedProviderCredentialMutation prepared =
                prepareRotate(reference, identity.expectedRevision(), secret, key, activeKeyId)) {
            return commit(identity, prepared, CredentialMetadata.class, ignored -> prepared.metadata());
        }
    }

    CredentialClearReceipt clear(CommandIdentity identity, CredentialRef reference) {
        Optional<CredentialClearReceipt> recovered = recover(identity, CredentialClearReceipt.class);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        try (PreparedProviderCredentialMutation prepared =
                prepareClear(reference, identity.expectedRevision(), clock.instant())) {
            return commit(
                    identity,
                    prepared,
                    CredentialClearReceipt.class,
                    ignored -> new CredentialClearReceipt(
                            reference,
                            identity.expectedRevision(),
                            prepared.metadata().updatedAt()));
        }
    }

    PreparedProviderCredentialMutation prepareCreate(String namespace, byte[] secret, byte[] key, String keyId) {
        CredentialRef reference = new CredentialRef(namespace, UUID.randomUUID().toString());
        return prepareWrite(
                PreparedProviderCredentialMutation.Action.CREATE,
                new CredentialMetadata(reference, 1, clock.instant()),
                secret,
                key,
                keyId);
    }

    PreparedProviderCredentialMutation prepareRotate(
            CredentialRef reference, long expectedRevision, byte[] secret, byte[] key, String keyId) {
        if (expectedRevision < 1) {
            throw PersistenceException.revisionConflict("CredentialRef revision 已变化");
        }
        CredentialMetadata metadata =
                new CredentialMetadata(reference, Math.addExact(expectedRevision, 1), clock.instant());
        return prepareWrite(PreparedProviderCredentialMutation.Action.ROTATE, metadata, secret, key, keyId);
    }

    PreparedProviderCredentialMutation prepareClear(CredentialRef reference, long expectedRevision, Instant now) {
        if (expectedRevision < 1) {
            throw PersistenceException.revisionConflict("CredentialRef revision 已变化");
        }
        return new PreparedProviderCredentialMutation(
                PreparedProviderCredentialMutation.Action.CLEAR,
                new CredentialMetadata(reference, expectedRevision, now),
                null,
                null);
    }

    <T> T usePrepared(PreparedProviderCredentialMutation prepared, byte[] key, SecretOperation<T> operation)
            throws Exception {
        byte[] plaintext = cipher.decrypt(key, binding(prepared.metadata()), prepared.encrypted());
        try {
            return operation.use(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    <T> T commit(
            CommandIdentity identity,
            PreparedProviderCredentialMutation prepared,
            Class<T> resultType,
            AtomicCredentialCommit<T> work) {
        T result = execute(connection -> {
            Optional<CanonicalPayload> replay = commands.recover(connection, identity);
            if (replay.isPresent()) {
                return json.decode(replay.orElseThrow(), resultType);
            }
            apply(connection, prepared);
            T committed = Objects.requireNonNull(work, "work").commit(connection);
            commands.record(connection, identity, json.encode(committed), clock.instant());
            return committed;
        });
        prepared.committed();
        return result;
    }

    <T> Optional<T> recover(CommandIdentity identity, Class<T> resultType) {
        return execute(
                connection -> commands.recover(connection, identity).map(value -> json.decode(value, resultType)));
    }

    private PreparedProviderCredentialMutation prepareWrite(
            PreparedProviderCredentialMutation.Action action,
            CredentialMetadata metadata,
            byte[] secret,
            byte[] key,
            String keyId) {
        byte[] plaintext = VaultChecks.checkedSecret(secret);
        try {
            EncryptedSecret encrypted = cipher.encrypt(key, binding(metadata), plaintext);
            return new PreparedProviderCredentialMutation(action, metadata, keyId, encrypted);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void apply(java.sql.Connection connection, PreparedProviderCredentialMutation prepared) throws Exception {
        switch (prepared.action()) {
            case CREATE -> applyCreate(connection, prepared);
            case ROTATE -> applyRotate(connection, prepared);
            case CLEAR -> applyClear(connection, prepared);
        }
    }

    private void applyCreate(java.sql.Connection connection, PreparedProviderCredentialMutation prepared)
            throws Exception {
        requireKey(connection, prepared);
        if (repository.find(connection, prepared.metadata().reference(), true).isPresent()) {
            throw PersistenceException.revisionConflict("CredentialRef 已存在");
        }
        repository.insert(connection, new VaultRepository.StoredSecret(prepared.metadata(), prepared.encrypted()));
    }

    private void applyRotate(java.sql.Connection connection, PreparedProviderCredentialMutation prepared)
            throws Exception {
        requireKey(connection, prepared);
        VaultRepository.StoredSecret current = repository
                .find(connection, prepared.metadata().reference(), true)
                .orElseThrow(() -> PersistenceException.invalidRequest("CredentialRef 不存在"));
        VaultChecks.requireRevision(current.metadata(), prepared.metadata().revision() - 1);
        repository.replace(connection, new VaultRepository.StoredSecret(prepared.metadata(), prepared.encrypted()));
    }

    private void applyClear(java.sql.Connection connection, PreparedProviderCredentialMutation prepared)
            throws Exception {
        VaultRepository.StoredSecret current = repository
                .find(connection, prepared.metadata().reference(), true)
                .orElseThrow(() -> PersistenceException.invalidRequest("CredentialRef 不存在"));
        VaultChecks.requireRevision(current.metadata(), prepared.metadata().revision());
        repository.delete(connection, prepared.metadata().reference());
    }

    private void requireKey(java.sql.Connection connection, PreparedProviderCredentialMutation prepared)
            throws Exception {
        VaultRepository.KeyState state =
                repository.keyState(connection, true).orElseThrow(() -> new VaultException("Vault key state 不存在"));
        if (!state.activeKeyId().equals(prepared.keyId())) {
            throw new VaultException("Vault 主密钥已变化，请重新提交 Provider Secret");
        }
    }

    private static SecretBinding binding(CredentialMetadata metadata) {
        return new SecretBinding(
                metadata.reference().namespace(), metadata.reference().id(), metadata.revision());
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new VaultException("Secret Vault 凭据事务失败", failure);
        }
    }
}
