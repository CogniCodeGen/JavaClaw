package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.api.TurnId;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.extension.spi.EmbeddingVector;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.config.ProviderModelRegistry;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderVerificationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC);
    private static final String RESPONSE_MARKER = "MODEL-RESPONSE-MUST-NOT-BE-PERSISTED";
    private static final List<String> SENSITIVE_MARKERS = List.of(RESPONSE_MARKER, "这是连通性验证", "返回 OK");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 成功调用经ProviderRegistry和Harness且重放不重复计费() throws Exception {
        AdapterFactory adapters;
        try (Fixture fixture = fixture()) {
            adapters = fixture.adapters();
            ProviderRef provider = fixture.reference();
            CommandIdentity identity = identity(fixture.json(), "verify-once", provider);

            var first = fixture.verification()
                    .verify(identity, provider, ProviderModelPurpose.CHAT, new CancellationSource());
            var replay = fixture.verification()
                    .verify(identity, provider, ProviderModelPurpose.CHAT, new CancellationSource());

            assertEquals(first, replay);
            assertEquals(ProviderVerificationState.SUCCEEDED, first.state());
            assertEquals(1, fixture.adapters().invocations.get());
            assertEquals(2, first.usage().orElseThrow().inputTokens());
            assertNoSensitivePersistence(fixture.database());
        }
        assertTrue(adapters.closed.get() > 0);
        try (var paths = Files.walk(temporaryDirectory.resolve("data-v5"))) {
            assertTrue(paths.filter(Files::isRegularFile).noneMatch(this::containsSensitiveContent));
        }
    }

    @Test
    void 未确认由协议拒绝且禁用Provider不会进入Adapter() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderVerificationRpcContracts.VerifyPayload(
                        new ProviderRef("provider-main", 2, "test-model"),
                        ProviderModelPurpose.CHAT,
                        false,
                        ProviderVerificationRpcContracts.BILLING_CONFIRMATION));
        try (Fixture fixture = fixture()) {
            ProviderCredentialBinding binding = fixture.binding();
            var disabled = fixture.providers()
                    .update(
                            new CommandIdentity(
                                    "provider/update",
                                    "disable",
                                    binding.provider().revision(),
                                    "b".repeat(64)),
                            binding.provider().id(),
                            binding.provider().spec(),
                            ProviderLifecycle.DISABLED);
            ProviderRef provider = new ProviderRef(disabled.id(), disabled.revision(), "test-model");

            assertThrows(
                    PersistenceException.class,
                    () -> fixture.verification()
                            .verify(
                                    identity(fixture.json(), "disabled", provider),
                                    provider,
                                    ProviderModelPurpose.CHAT,
                                    new CancellationSource()));
            assertEquals(0, fixture.adapters().invocations.get());
        }
    }

    @Test
    void 撤销Credential后验证失败关闭且不会进入Adapter() {
        try (Fixture fixture = fixture()) {
            ProviderCredentialBinding binding = fixture.binding();
            var cleared = fixture.credentials()
                    .clear(
                            new CommandIdentity(
                                    "provider/credential/clear",
                                    "clear-before-verify",
                                    binding.provider().revision(),
                                    "c".repeat(64)),
                            binding.provider().id(),
                            binding.provider().revision(),
                            binding.credential().reference(),
                            binding.credential().revision());
            ProviderRef provider =
                    new ProviderRef(cleared.provider().id(), cleared.provider().revision(), "test-model");

            assertThrows(
                    PersistenceException.class,
                    () -> fixture.verification()
                            .verify(
                                    identity(fixture.json(), "revoked", provider),
                                    provider,
                                    ProviderModelPurpose.CHAT,
                                    new CancellationSource()));
            assertEquals(0, fixture.adapters().invocations.get());
        }
    }

    @Test
    void 启动恢复把未完成意图固定为Unknown且不再次调用模型() throws Exception {
        try (Fixture fixture = fixture()) {
            ProviderRef provider = fixture.reference();
            CommandIdentity identity =
                    identity(fixture.json(), "interrupted", provider, ProviderModelPurpose.EMBEDDING);
            new H2Transactions(fixture.database()).execute(connection -> {
                new ProviderVerificationRepository()
                        .insertRunning(connection, identity, provider, ProviderModelPurpose.EMBEDDING, CLOCK.instant());
                return null;
            });

            try (ProviderVerificationService restarted = new ProviderVerificationService(
                    fixture.database(),
                    fixture.providers(),
                    fixture.vault(),
                    fixture.registry(),
                    fixture.adapters()::createEmbedding,
                    fixture.json(),
                    CLOCK)) {
                var result =
                        restarted.verify(identity, provider, ProviderModelPurpose.EMBEDDING, new CancellationSource());

                assertEquals(ProviderVerificationState.UNKNOWN_OUTCOME, result.state());
                assertEquals(ProviderModelPurpose.EMBEDDING, result.purpose());
                assertEquals(0, fixture.adapters().invocations.get());
                assertEquals(0, fixture.adapters().embeddingInvocations.get());
            }
        }
    }

    @Test
    void 验证身份ProviderRevision模型与Credential必须精确匹配() {
        try (Fixture fixture = fixture()) {
            ProviderRef provider = fixture.reference();
            CommandIdentity valid = identity(fixture.json(), "validation", provider);
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.verification()
                            .verify(
                                    new CommandIdentity(
                                            "provider/verify-wrong",
                                            valid.idempotencyKey(),
                                            valid.expectedRevision(),
                                            valid.requestDigest()),
                                    provider,
                                    ProviderModelPurpose.CHAT,
                                    new CancellationSource()));
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.verification()
                            .verify(
                                    new CommandIdentity(
                                            valid.method(),
                                            "wrong-expected-revision",
                                            provider.endpointRevision() - 1,
                                            valid.requestDigest()),
                                    provider,
                                    ProviderModelPurpose.CHAT,
                                    new CancellationSource()));

            ProviderRef stale =
                    new ProviderRef(provider.endpointId(), provider.endpointRevision() - 1, provider.model());
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.verification()
                            .verify(
                                    identity(fixture.json(), "stale-provider", stale),
                                    stale,
                                    ProviderModelPurpose.CHAT,
                                    new CancellationSource()));
            ProviderRef unknownModel =
                    new ProviderRef(provider.endpointId(), provider.endpointRevision(), "unknown-model");
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.verification()
                            .verify(
                                    identity(fixture.json(), "unknown-model", unknownModel),
                                    unknownModel,
                                    ProviderModelPurpose.CHAT,
                                    new CancellationSource()));
            assertEquals(0, fixture.adapters().invocations.get());
        }
    }

    @Test
    void 验证拒绝EmbeddingOnly且Provider拒绝无凭据启用() {
        try (Fixture fixture = fixture()) {
            ProviderRef provider = fixture.reference();
            ProviderEndpointSpec current = fixture.binding().provider().spec();
            ProviderEndpointSpec embeddingOnly = new ProviderEndpointSpec(
                    current.displayName(),
                    current.adapter(),
                    current.baseUri(),
                    current.authentication(),
                    List.of(new ProviderModelSpec(
                            "test-model", "test-model", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.empty())),
                    current.credential(),
                    current.timeout(),
                    current.maximumRetries(),
                    current.options());
            var updated = fixture.providers()
                    .update(
                            new CommandIdentity(
                                    "provider/update", "embedding-only", provider.endpointRevision(), "e".repeat(64)),
                            provider.endpointId(),
                            embeddingOnly,
                            ProviderLifecycle.ACTIVE);
            ProviderRef embedding = new ProviderRef(updated.id(), updated.revision(), "test-model");
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.verification()
                            .verify(
                                    identity(fixture.json(), "embedding-only", embedding),
                                    embedding,
                                    ProviderModelPurpose.CHAT,
                                    new CancellationSource()));
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.providers()
                            .create(
                                    new CommandIdentity("provider/create", "unbound-provider", 0, "d".repeat(64)),
                                    "provider-unbound",
                                    providerSpec(),
                                    ProviderLifecycle.ACTIVE));
            assertEquals(0, fixture.adapters().invocations.get());
        }
    }

    @Test
    void 已提交验证结果拒绝同一幂等键绑定不同请求() {
        try (Fixture fixture = fixture()) {
            ProviderRef provider = fixture.reference();
            CommandIdentity identity = identity(fixture.json(), "stored-conflict", provider);
            fixture.verification().verify(identity, provider, ProviderModelPurpose.CHAT, new CancellationSource());
            CommandIdentity changed = new CommandIdentity(
                    identity.method(), identity.idempotencyKey(), identity.expectedRevision(), "f".repeat(64));

            assertThrows(
                    PersistenceException.class,
                    () -> fixture.verification()
                            .verify(changed, provider, ProviderModelPurpose.CHAT, new CancellationSource()));
            assertEquals(1, fixture.adapters().invocations.get());
        }
    }

    @Test
    void 向量验证使用用户选择的精确引用且不依赖全局绑定() throws Exception {
        AdapterFactory adapters;
        try (Fixture fixture = fixture()) {
            adapters = fixture.adapters();
            ProviderRef provider = fixture.reference();
            CommandIdentity identity =
                    identity(fixture.json(), "embedding-once", provider, ProviderModelPurpose.EMBEDDING);

            var result = fixture.verification()
                    .verify(identity, provider, ProviderModelPurpose.EMBEDDING, new CancellationSource());

            assertEquals(ProviderVerificationState.SUCCEEDED, result.state());
            assertEquals(ProviderModelPurpose.EMBEDDING, result.purpose());
            assertEquals(Optional.empty(), result.usage());
            assertEquals(
                    Set.of(ProviderModelPurpose.EMBEDDING),
                    result.capabilities().purposes());
            assertEquals(1, adapters.embeddingInvocations.get());
            assertEquals(0, adapters.invocations.get());
            new H2Transactions(fixture.database()).execute(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT PURPOSE FROM CORE.PROVIDER_VERIFICATION WHERE IDEMPOTENCY_KEY = ?")) {
                    statement.setString(1, identity.idempotencyKey());
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("EMBEDDING", rows.getString(1));
                    }
                }
                return null;
            });
            assertNoSensitivePersistence(fixture.database());
        }
        assertEquals(1, adapters.embeddingClosed.get());
    }

    private Fixture fixture() {
        CanonicalJson json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        SecretVaultService vault =
                new SecretVaultService(database, new MemoryProtector(), json, CLOCK, new SecureRandom());
        ProviderService providers = new ProviderService(database, reference -> true, json, CLOCK);
        providers.create(
                new CommandIdentity("provider/create", "create-provider", 0, "a".repeat(64)),
                "provider-main",
                providerSpec(),
                ProviderLifecycle.DISABLED);
        ProviderCredentialService credentials =
                new ProviderCredentialService(providers, vault.providerCredentials(), json, CLOCK);
        ProviderCredentialBinding binding = credentials.set(
                new CommandIdentity("provider/credential/set", "bind-provider", 1, "b".repeat(64)),
                "provider-main",
                1,
                0,
                "local-fake-secret".getBytes(StandardCharsets.UTF_8));
        var active = providers.update(
                new CommandIdentity(
                        "provider/update", "enable-provider", binding.provider().revision(), "c".repeat(64)),
                binding.provider().id(),
                binding.provider().spec(),
                ProviderLifecycle.ACTIVE);
        binding = new ProviderCredentialBinding(active, binding.credential());
        AdapterFactory adapters = new AdapterFactory();
        ProviderModelRegistry registry = new ProviderModelRegistry(providers, adapters::create, vault.runtimeGate());
        vault.onRuntimeChange(registry::invalidate, registry::reload);
        ProviderVerificationService verification = new ProviderVerificationService(
                database, providers, vault, registry, adapters::createEmbedding, json, CLOCK);
        return new Fixture(database, json, providers, credentials, vault, registry, verification, adapters, binding);
    }

    private static ProviderEndpointSpec providerSpec() {
        return new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec(
                        "test-model",
                        "test-model",
                        Set.of(ProviderModelPurpose.CHAT, ProviderModelPurpose.EMBEDDING),
                        OptionalInt.of(3))),
                Optional.empty(),
                Duration.ofSeconds(2),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private static CommandIdentity identity(CanonicalJson json, String key, ProviderRef provider) {
        return identity(json, key, provider, ProviderModelPurpose.CHAT);
    }

    private static CommandIdentity identity(
            CanonicalJson json, String key, ProviderRef provider, ProviderModelPurpose purpose) {
        var payload = new ProviderVerificationRpcContracts.VerifyPayload(
                provider, purpose, true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        return new CommandIdentity(
                ProviderVerificationRpcContracts.METHOD,
                key,
                provider.endpointRevision(),
                json.encode(payload).sha256());
    }

    private boolean containsSensitiveContent(Path path) {
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return SENSITIVE_MARKERS.stream().anyMatch(content::contains);
        } catch (Exception failure) {
            throw new AssertionError("无法扫描测试数据库", failure);
        }
    }

    private static void assertNoSensitivePersistence(H2Database database) throws Exception {
        new H2Transactions(database).execute(connection -> {
            try (var statement = connection.createStatement();
                    var results = statement.executeQuery("""
                            SELECT RESULT_PAYLOAD AS PAYLOAD FROM CORE.PROVIDER_VERIFICATION
                            UNION ALL
                            SELECT RESPONSE_PAYLOAD AS PAYLOAD FROM CORE.COMMAND_RESULT
                            """)) {
                while (results.next()) {
                    String payload = results.getString("PAYLOAD");
                    assertFalse(SENSITIVE_MARKERS.stream().anyMatch(payload::contains));
                }
            }
            try (var statement = connection.createStatement();
                    var items = statement.executeQuery("SELECT COUNT(*) FROM CORE.ITEM")) {
                assertTrue(items.next());
                assertEquals(0, items.getLong(1));
            }
            return null;
        });
    }

    private record Fixture(
            H2Database database,
            CanonicalJson json,
            ProviderService providers,
            ProviderCredentialService credentials,
            SecretVaultService vault,
            ProviderModelRegistry registry,
            ProviderVerificationService verification,
            AdapterFactory adapters,
            ProviderCredentialBinding binding)
            implements AutoCloseable {
        private ProviderRef reference() {
            return new ProviderRef(binding.provider().id(), binding.provider().revision(), "test-model");
        }

        @Override
        public void close() {
            verification.close();
            registry.close();
            vault.close();
        }
    }

    private static final class AdapterFactory {
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final AtomicInteger embeddingInvocations = new AtomicInteger();
        private final AtomicInteger embeddingClosed = new AtomicInteger();

        private ModelGateway create(com.javaclaw.api.ProviderEndpoint endpoint, ProviderRef provider) {
            return new FakeAdapter(invocations, closed);
        }

        private EmbeddingPort createEmbedding(com.javaclaw.api.ProviderEndpoint endpoint, ProviderRef provider) {
            return new FakeEmbeddingAdapter(embeddingInvocations, embeddingClosed);
        }
    }

    private record FakeEmbeddingAdapter(AtomicInteger invocations, AtomicInteger closed)
            implements EmbeddingPort, AutoCloseable {
        @Override
        public EmbeddingBatch embed(List<String> texts, EmbeddingPurpose purpose, CancellationToken cancellation) {
            invocations.incrementAndGet();
            cancellation.throwIfCancelled();
            assertEquals(1, texts.size());
            assertEquals(EmbeddingPurpose.QUERY, purpose);
            return new EmbeddingBatch("1".repeat(64), 3, List.of(new EmbeddingVector(List.of(0.1, 0.2, 0.3))));
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }

    private record FakeAdapter(AtomicInteger invocations, AtomicInteger closed) implements ModelGateway, AutoCloseable {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(true, true, true, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            invocations.incrementAndGet();
            assertTrue(invocation.tools().isEmpty());
            return new ModelInvocationResult(
                    RESPONSE_MARKER,
                    List.of(),
                    new ModelUsage(2, 1, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    ModelFinishReason.COMPLETE);
        }

        @Override
        public void close() {
            closed.incrementAndGet();
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
