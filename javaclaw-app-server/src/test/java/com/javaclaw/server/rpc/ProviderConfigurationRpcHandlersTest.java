package com.javaclaw.server.rpc;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.SessionSecretSealer;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.ProviderEndpointTestFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderConfigurationService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigurationRpcHandlersTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T01:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void 完整保存重放先恢复回执而不再次解封且新连接可查询原结果() throws Exception {
        try (Fixture fixture = new Fixture(directory);
                SessionSecretChannel secrets = SessionSecretChannel.open()) {
            WriteCommand command = fixture.command(secrets, "save-once");
            ProviderConfigurationResult result = fixture.json.decode(
                    fixture.router.route(
                            ProviderConfigurationRpcContracts.SAVE_METHOD, fixture.json.encode(command), secrets),
                    ProviderConfigurationResult.class);

            assertEquals(
                    result,
                    fixture.json.decode(
                            fixture.router.route(
                                    ProviderConfigurationRpcContracts.SAVE_METHOD,
                                    fixture.json.encode(command),
                                    secrets),
                            ProviderConfigurationResult.class));
            assertEquals(1, fixture.providers.listAllVersions().size());
            assertEquals(1, fixture.vault.status().credentialCount());
            try (SessionSecretChannel reconnected = SessionSecretChannel.open()) {
                var recovered = fixture.json.decode(
                        fixture.router.route(
                                ProviderConfigurationRpcContracts.RESULT_METHOD,
                                fixture.json.encode(fixture.resultQuery(command)),
                                reconnected),
                        ProviderConfigurationRpcContracts.ResultResponse.class);
                assertEquals(result, recovered.result().orElseThrow());
                assertEquals(
                        result,
                        fixture.json.decode(
                                fixture.router.route(
                                        ProviderConfigurationRpcContracts.SAVE_METHOD,
                                        fixture.json.encode(command),
                                        reconnected),
                                ProviderConfigurationResult.class));
            }
        }
    }

    @Test
    void 结果查询核对原摘要而未知结果仅返回空回执() throws Exception {
        try (Fixture fixture = new Fixture(directory);
                SessionSecretChannel secrets = SessionSecretChannel.open()) {
            WriteCommand command = fixture.command(secrets, "saved");
            fixture.router.route(ProviderConfigurationRpcContracts.SAVE_METHOD, fixture.json.encode(command), secrets);
            var altered = new ProviderConfigurationRpcContracts.ResultPayload("saved", 0, "f".repeat(64));

            assertThrows(
                    PersistenceException.class,
                    () -> fixture.router.route(
                            ProviderConfigurationRpcContracts.RESULT_METHOD, fixture.json.encode(altered), secrets));
            var wrongRevision = new ProviderConfigurationRpcContracts.ResultPayload(
                    "saved", 1, fixture.resultQuery(command).requestDigest());
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.router.route(
                            ProviderConfigurationRpcContracts.RESULT_METHOD,
                            fixture.json.encode(wrongRevision),
                            secrets));
            var unknown = new ProviderConfigurationRpcContracts.ResultPayload("unknown", 0, "a".repeat(64));
            var result = fixture.json.decode(
                    fixture.router.route(
                            ProviderConfigurationRpcContracts.RESULT_METHOD, fixture.json.encode(unknown), secrets),
                    ProviderConfigurationRpcContracts.ResultResponse.class);

            assertTrue(result.result().isEmpty());
            assertEquals(1, fixture.providers.listAllVersions().size());
        }
    }

    @Test
    void 结果回执在Vault关闭后仍可查询且不重新读取Secret() throws Exception {
        try (Fixture fixture = new Fixture(directory);
                SessionSecretChannel secrets = SessionSecretChannel.open()) {
            WriteCommand command = fixture.command(secrets, "saved-before-lock");
            var saved = fixture.json.decode(
                    fixture.router.route(
                            ProviderConfigurationRpcContracts.SAVE_METHOD, fixture.json.encode(command), secrets),
                    ProviderConfigurationResult.class);
            fixture.vault.close();

            var recovered = fixture.json.decode(
                    fixture.router.route(
                            ProviderConfigurationRpcContracts.RESULT_METHOD,
                            fixture.json.encode(fixture.resultQuery(command)),
                            secrets),
                    ProviderConfigurationRpcContracts.ResultResponse.class);

            assertEquals(saved, recovered.result().orElseThrow());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final CanonicalJson json = new CanonicalJson();
        private final SecretVaultService vault;
        private final ProviderService providers;
        private final RpcRouter router;

        private Fixture(Path directory) {
            H2Database database = new H2Database(directory.resolve("data-v6"));
            database.initialize();
            vault = new SecretVaultService(database, new MemoryProtector(), json, CLOCK, new SecureRandom());
            providers = new ProviderService(database, vault, json, CLOCK);
            var service = new ProviderConfigurationService(
                    database, providers, vault.providerCredentials(), vault::metadata, json, CLOCK);
            router = new ProviderConfigurationRpcHandlers(service, json)
                    .register(RpcRouter.builder())
                    .build();
        }

        private WriteCommand command(SessionSecretChannel secrets, String key) {
            var spec = ProviderEndpointTestFixtures.apiKeyChat("测试服务", ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
            var configuration = new ProviderConfiguration(
                    "provider-main",
                    0,
                    ProviderConnectionSpec.from(spec),
                    spec.models(),
                    ProviderLifecycle.ACTIVE,
                    ProviderCredentialChange.REPLACE,
                    0);
            char[] plaintext = "test-rpc-configuration-key".toCharArray();
            try {
                var sealed = SessionSecretSealer.seal(
                        secrets.publicKey(), ProviderConfigurationRpcContracts.SAVE_PURPOSE, plaintext);
                return new WriteCommand(
                        key,
                        0,
                        json.encode(
                                new ProviderConfigurationRpcContracts.SavePayload(configuration, Optional.of(sealed))));
            } finally {
                Arrays.fill(plaintext, '\0');
            }
        }

        private ProviderConfigurationRpcContracts.ResultPayload resultQuery(WriteCommand command) {
            CommandIdentity identity =
                    CommandIdentity.from(ProviderConfigurationRpcContracts.SAVE_METHOD, command, json);
            return new ProviderConfigurationRpcContracts.ResultPayload(
                    identity.idempotencyKey(), identity.expectedRevision(), identity.requestDigest());
        }

        @Override
        public void close() {
            vault.close();
        }
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
            byte[] removed = keys.remove(keyId);
            if (removed != null) {
                Arrays.fill(removed, (byte) 0);
            }
        }
    }
}
