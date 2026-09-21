package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.server.ProviderEndpointTestFixtures;
import com.javaclaw.server.security.vault.SecretVaultService;

/** 仅使用本地 H2、内存主密钥保护器的完整配置服务夹具，不构造远程模型客户端。 */
final class ProviderConfigurationTestFixture implements AutoCloseable {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T01:00:00Z"), ZoneOffset.UTC);
    static final String SECRET = "configuration-test-secret";
    final CanonicalJson json = new CanonicalJson();
    final H2Database database;
    final MemoryProtector protector = new MemoryProtector();
    final SecretVaultService vault;
    final ProviderService providers;
    final ProviderConfigurationService service;

    ProviderConfigurationTestFixture(Path directory) {
        this(directory, () -> {});
    }

    ProviderConfigurationTestFixture(Path directory, ProviderCredentialTransactionPort.FailureProbe failure) {
        database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        vault = new SecretVaultService(database, protector, json, CLOCK, new SecureRandom());
        providers = new ProviderService(database, vault, json, CLOCK);
        var port = new ProviderConfigurationTransactionPort(
                database, json, new ProviderCredentialTransactionPort(json, failure));
        service =
                new ProviderConfigurationService(providers, vault.providerCredentials(), vault::metadata, port, CLOCK);
    }

    static ProviderConfiguration apiKey(long revision, ProviderCredentialChange change, long credentialRevision) {
        ProviderEndpointSpec spec =
                ProviderEndpointTestFixtures.apiKeyChat("配置测试", ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
        return configuration(spec, revision, change, credentialRevision);
    }

    static ProviderConfiguration none(long revision, ProviderCredentialChange change, long credentialRevision) {
        ProviderEndpointSpec spec =
                ProviderEndpointTestFixtures.chat("本地服务", ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
        return configuration(spec, revision, change, credentialRevision);
    }

    static ProviderConfiguration configuration(
            ProviderEndpointSpec spec, long revision, ProviderCredentialChange change, long credentialRevision) {
        return new ProviderConfiguration(
                "provider-main",
                revision,
                ProviderConnectionSpec.from(spec),
                spec.models(),
                ProviderLifecycle.ACTIVE,
                change,
                credentialRevision);
    }

    static CommandIdentity identity(String key, long revision) {
        return new CommandIdentity(ProviderConfigurationRpcContracts.SAVE_METHOD, key, revision, "a".repeat(64));
    }

    static byte[] secret() {
        return SECRET.getBytes(StandardCharsets.UTF_8);
    }

    static boolean cleared(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        vault.close();
    }

    static final class MemoryProtector implements MasterKeyProtector {
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
