package com.javaclaw.server.rpc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.VaultManagementAction;
import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultState;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CredentialRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialRpcHandlersTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void registrar提供脱敏刷新主密钥轮换和危险重置() throws Exception {
        CanonicalJson json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        try (SecretVaultService vault =
                        new SecretVaultService(database, new MemoryProtector(), json, CLOCK, new SecureRandom());
                SessionSecretChannel secrets = SessionSecretChannel.open()) {
            vault.create(
                    identity("credential/create", "create", 0, json),
                    "provider",
                    "only-in-memory".getBytes(StandardCharsets.UTF_8));
            vault.create(
                    identity("credential/create", "site-create", 0, json),
                    "site",
                    "site-only-in-memory".getBytes(StandardCharsets.UTF_8));
            RpcRouter.Builder routes = RpcRouter.builder();
            new CredentialRpcHandlers(vault, json).register(routes);
            RpcRouter router = routes.build();

            assertEquals(9, router.implementedMethods().size());
            CredentialRpcContracts.ListResult listed = json.decode(
                    router.route(
                            "credential/list", json.encode(new CredentialRpcContracts.ListPayload("site")), secrets),
                    CredentialRpcContracts.ListResult.class);
            assertEquals(
                    List.of("site"),
                    listed.credentials().stream()
                            .map(metadata -> metadata.reference().namespace())
                            .toList());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> router.route(
                            "credential/list",
                            json.encode(new CredentialRpcContracts.ListPayload("provider")),
                            secrets));
            assertEquals(
                    VaultState.READY,
                    json.decode(
                                    router.route("vault/refresh", json.encode(Map.of()), secrets),
                                    com.javaclaw.api.VaultStatus.class)
                            .state());
            WriteCommand rotation = command("rotate-key", 0, new CredentialRpcContracts.MasterKeyRotatePayload(), json);
            VaultManagementReceipt rotated = json.decode(
                    router.route("vault/masterKey/rotate", json.encode(rotation), secrets),
                    VaultManagementReceipt.class);
            assertEquals(VaultManagementAction.MASTER_KEY_ROTATED, rotated.action());
            assertEquals(
                    rotated,
                    json.decode(
                            router.route("vault/masterKey/rotate", json.encode(rotation), secrets),
                            VaultManagementReceipt.class));

            assertThrows(IllegalArgumentException.class, () -> new CredentialRpcContracts.ResetPayload("yes"));
            WriteCommand reset = command("reset", 0, new CredentialRpcContracts.ResetPayload("RESET VAULT"), json);
            VaultManagementReceipt resetReceipt =
                    json.decode(router.route("vault/reset", json.encode(reset), secrets), VaultManagementReceipt.class);
            assertEquals(VaultManagementAction.VAULT_RESET, resetReceipt.action());
            assertEquals(2, resetReceipt.affectedCredentialCount());
            assertEquals(0, vault.status().credentialCount());
        }
    }

    private static CommandIdentity identity(String method, String key, long revision, CanonicalJson json) {
        return CommandIdentity.from(method, command(key, revision, Map.of(), json), json);
    }

    private static WriteCommand command(String key, long revision, Object payload, CanonicalJson json) {
        return new WriteCommand(key, revision, json.encode(payload));
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String keyId) {
            return Optional.ofNullable(keys.get(keyId)).map(byte[]::clone);
        }

        @Override
        public void store(String keyId, byte[] key) {
            keys.put(keyId, key.clone());
        }

        @Override
        public void delete(String keyId) {
            keys.remove(keyId);
        }
    }
}
