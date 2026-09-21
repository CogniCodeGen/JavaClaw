package com.javaclaw.server.persistence;

import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConfigurationSource;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPreviewOperation;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.security.vault.ProviderModelPreviewCredentialReader;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 提供独立 H2 和内存主密钥设施；模型目录端口始终由测试替身提供。 */
final class ProviderModelPreviewFixture implements AutoCloseable {
    static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    final CanonicalJson json = new CanonicalJson();
    final SecretVaultService vault;
    final ProviderService providers;
    final ProviderModelDiscoveryService saved;

    ProviderModelPreviewFixture(Path path) {
        H2Database database = new H2Database(path.resolve("data-v6"));
        database.initialize();
        vault = new SecretVaultService(database, new MemoryProtector(), json, CLOCK, new SecureRandom());
        providers = new ProviderService(database, vault, json, CLOCK);
        saved = new ProviderModelDiscoveryService(
                providers,
                (endpoint, token) -> {
                    token.throwIfCancelled();
                    return new ProviderModelDiscoveryResult(endpoint.id(), endpoint.revision(), List.of(), false, NOW);
                },
                CLOCK);
    }

    ProviderModelPreviewService preview(ProviderModelPreviewService.PreviewPort port) {
        return new ProviderModelPreviewService(
                providers, new ProviderModelPreviewCredentialReader(vault), port, saved, CLOCK);
    }

    ProviderEndpoint create(ProviderAuthentication authentication) {
        var spec = connection(authentication).toEndpointSpec(List.of(), Optional.empty());
        return providers.create(
                identity("provider/create", "create", 0, spec), "preview-source", spec, ProviderLifecycle.DISABLED);
    }

    static ProviderConnectionSpec connection(ProviderAuthentication authentication) {
        return new ProviderConnectionSpec(
                "Preview",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:11434/v1")),
                authentication,
                Duration.ofSeconds(2),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    static ProviderModelPreviewRequest request(ProviderAuthentication authentication) {
        return new ProviderModelPreviewRequest(
                "draft",
                1,
                connection(authentication),
                Optional.empty(),
                authentication == ProviderAuthentication.NONE
                        ? ProviderCredentialChange.CLEAR
                        : ProviderCredentialChange.REPLACE);
    }

    static ProviderModelPreviewRequest source(ProviderEndpoint endpoint, long credentialRevision) {
        return new ProviderModelPreviewRequest(
                "draft",
                1,
                ProviderConnectionSpec.from(endpoint.spec()),
                Optional.of(new ProviderConfigurationSource(endpoint.id(), endpoint.revision(), credentialRevision)),
                ProviderCredentialChange.KEEP);
    }

    static ProviderModelPreviewResult result(ProviderModelPreviewRequest request) {
        return new ProviderModelPreviewResult(request.draftId(), request.generation(), List.of(), false, NOW);
    }

    static CommandIdentity startIdentity(String key, ProviderModelPreviewRequest request) {
        return identity(ProviderConfigurationRpcContracts.PREVIEW_START_METHOD, key, request.generation(), request);
    }

    static CommandIdentity identity(String method, String key, long revision, Object payload) {
        CanonicalJson json = new CanonicalJson();
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    static ProviderModelPreviewOperation await(
            ProviderModelPreviewService service, String owner, ProviderModelPreviewOperation operation)
            throws InterruptedException {
        ProviderModelPreviewOperation current = operation;
        for (int attempt = 0; attempt < 200 && !current.terminal(); attempt++) {
            Thread.sleep(5);
            current = service.read(owner, current.operationId());
        }
        assertTrue(current.terminal());
        return current;
    }

    @Override
    public void close() {
        saved.close();
        vault.close();
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
