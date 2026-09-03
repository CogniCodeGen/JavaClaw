package com.javaclaw.server.security.vault;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.VaultState;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.extension.spi.EmbeddingVector;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.server.config.ProviderEmbeddingRegistry;
import com.javaclaw.server.config.ProviderModelRegistry;
import com.javaclaw.server.model.EmbeddingAdapterFactory;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.EmbeddingBindingService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ProviderCredentialService;
import com.javaclaw.server.persistence.ProviderService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultProviderRuntimeSafetyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void VaultReset后模型重建失败仍退役旧凭据并阻断新Lease() {
        ConfiguredProvider configured = configure("model-reset", ProviderModelPurpose.CHAT);
        FailingModelFactory factory = new FailingModelFactory();
        SecretVaultService vault = configured.vault();
        try (vault;
                ProviderModelRegistry registry =
                        new ProviderModelRegistry(configured.providers(), factory, vault.runtimeGate())) {
            vault.onRuntimeChange(registry::invalidate, registry::reload);
            String route = route(configured.endpoint(), "test-model");
            assertDoesNotThrow(() -> registry.capabilities(route));

            factory.fail.set(true);
            assertThrows(VaultException.class, () -> vault.reset(identity("vault/reset", 0)));

            assertEquals(0, vault.status().credentialCount());
            assertTrue(factory.created.getFirst().closed.get());
            assertThrows(IllegalStateException.class, () -> registry.capabilities(route));
        }
    }

    @Test
    void Secret清除后Embedding重建失败仍退役旧凭据并阻断新Lease() {
        ConfiguredProvider configured = configure("embedding-clear", ProviderModelPurpose.EMBEDDING);
        EmbeddingBindingService bindings =
                new EmbeddingBindingService(configured.database(), configured.providers(), configured.json(), CLOCK);
        bindings.update(
                identity("provider/embeddingBinding/update", 0),
                new ProviderRef(
                        configured.endpoint().id(), configured.endpoint().revision(), "test-model"));
        FailingEmbeddingFactory factory = new FailingEmbeddingFactory();
        SecretVaultService vault = configured.vault();
        try (vault;
                ProviderEmbeddingRegistry registry =
                        new ProviderEmbeddingRegistry(configured.providers(), bindings, factory, vault.runtimeGate())) {
            vault.onRuntimeChange(registry::invalidate, registry::reload);
            assertDoesNotThrow(
                    () -> registry.embed(List.of("before"), EmbeddingPurpose.QUERY, new CancellationSource()));

            factory.fail.set(true);
            assertThrows(
                    VaultException.class,
                    () -> vault.clear(
                            identity("credential/clear", configured.credential().revision()),
                            configured.credential().reference()));

            assertTrue(vault.metadata(configured.credential().reference()).isEmpty());
            assertTrue(factory.created.getFirst().closed.get());
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.embed(List.of("after"), EmbeddingPurpose.QUERY, new CancellationSource()));
        }
    }

    @Test
    void 重建阻塞期间并发模型调用不能获得旧GenerationLease() throws Exception {
        ConfiguredProvider configured = configure("lease-gate", ProviderModelPurpose.CHAT);
        BlockingModelFactory factory = new BlockingModelFactory();
        SecretVaultService vault = configured.vault();
        try (vault;
                ProviderModelRegistry registry =
                        new ProviderModelRegistry(configured.providers(), factory, vault.runtimeGate())) {
            vault.onRuntimeChange(registry::invalidate, registry::reload);
            String route = route(configured.endpoint(), "test-model");
            factory.blockNext.set(true);
            AtomicReference<Throwable> refreshFailure = new AtomicReference<>();
            Thread refresh = Thread.ofVirtual().start(() -> {
                try {
                    vault.refresh();
                } catch (Throwable failure) {
                    refreshFailure.set(failure);
                }
            });
            try {
                assertTrue(factory.rebuildStarted.await(5, TimeUnit.SECONDS));
                assertTrue(factory.created.getFirst().closed.get());
                assertThrows(IllegalStateException.class, () -> registry.capabilities(route));
            } finally {
                factory.releaseRebuild.countDown();
            }
            refresh.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(refresh.isAlive());
            assertNull(refreshFailure.get());
            assertDoesNotThrow(() -> registry.capabilities(route));
        }
    }

    @Test
    void Vault锁定会重建安全路由而重复关闭不再重建() {
        ConfiguredProvider configured = configure("locked-vault", ProviderModelPurpose.CHAT);
        FailingModelFactory factory = new FailingModelFactory();
        AtomicInteger invalidations = new AtomicInteger();
        AtomicInteger rebuilds = new AtomicInteger();
        SecretVaultService vault = configured.vault();
        try (vault;
                ProviderModelRegistry registry =
                        new ProviderModelRegistry(configured.providers(), factory, vault.runtimeGate())) {
            vault.onRuntimeChange(registry::invalidate, registry::reload);
            vault.onRuntimeChange(invalidations::incrementAndGet, rebuilds::incrementAndGet);
            configured.protector().loadUnavailable = true;

            assertEquals(VaultState.LOCKED, assertDoesNotThrow(vault::refresh).state());
            assertEquals(2, factory.created.size());
            assertEquals(1, invalidations.get());
            assertEquals(1, rebuilds.get());

            vault.close();
            vault.close();
            assertEquals(2, invalidations.get());
            assertEquals(1, rebuilds.get());
        }
    }

    @Test
    void Registry公共构造器强制要求显式VaultGate() {
        assertEquals(1, ProviderModelRegistry.class.getConstructors().length);
        assertArrayEquals(
                new Class<?>[] {
                    ProviderService.class, ProviderModelRegistry.AdapterFactory.class, VaultRuntimeGate.class
                },
                ProviderModelRegistry.class.getConstructors()[0].getParameterTypes());
        assertEquals(1, ProviderEmbeddingRegistry.class.getConstructors().length);
        assertArrayEquals(
                new Class<?>[] {
                    ProviderService.class,
                    EmbeddingBindingService.class,
                    EmbeddingAdapterFactory.class,
                    VaultRuntimeGate.class
                },
                ProviderEmbeddingRegistry.class.getConstructors()[0].getParameterTypes());
    }

    private ConfiguredProvider configure(String directory, ProviderModelPurpose purpose) {
        H2Database database =
                new H2Database(temporaryDirectory.resolve(directory).resolve("data-v5"));
        database.initialize();
        CanonicalJson json = new CanonicalJson();
        MemoryProtector protector = new MemoryProtector();
        SecretVaultService vault = new SecretVaultService(database, protector, json, CLOCK, new SecureRandom());
        ProviderService providers = new ProviderService(database, vault, json, CLOCK);
        ProviderEndpoint disabled = providers.create(
                identity("provider/create", 0), "provider", apiKeySpec(purpose), ProviderLifecycle.DISABLED);
        ProviderCredentialService credentials =
                new ProviderCredentialService(providers, vault.providerCredentials(), json, CLOCK);
        ProviderCredentialBinding binding = credentials.set(
                identity("provider/credential/set", disabled.revision()),
                disabled.id(),
                disabled.revision(),
                0,
                "copied-secret".getBytes(StandardCharsets.UTF_8));
        ProviderEndpoint active = providers.update(
                identity("provider/update", binding.provider().revision()),
                binding.provider().id(),
                binding.provider().spec(),
                ProviderLifecycle.ACTIVE);
        return new ConfiguredProvider(database, json, protector, vault, providers, active, binding.credential());
    }

    private static ProviderEndpointSpec apiKeySpec(ProviderModelPurpose purpose) {
        return new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:12345/v1")),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec(
                        "test-model",
                        "Test Model",
                        Set.of(purpose),
                        purpose == ProviderModelPurpose.EMBEDDING ? OptionalInt.of(1) : OptionalInt.empty())),
                Optional.empty(),
                Duration.ofSeconds(5),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private static CommandIdentity identity(String method, long revision) {
        return new CommandIdentity(method, UUID.randomUUID().toString(), revision, "0".repeat(64));
    }

    private static String route(ProviderEndpoint endpoint, String model) {
        return new ProviderRef(endpoint.id(), endpoint.revision(), model).routeKey();
    }

    private record ConfiguredProvider(
            H2Database database,
            CanonicalJson json,
            MemoryProtector protector,
            SecretVaultService vault,
            ProviderService providers,
            ProviderEndpoint endpoint,
            CredentialMetadata credential) {}

    private static class TrackingGateway implements ModelGateway, AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(true, true, true, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            throw new UnsupportedOperationException("测试不执行真实模型请求");
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class FailingModelFactory implements ProviderModelRegistry.AdapterFactory {
        private final AtomicBoolean fail = new AtomicBoolean();
        private final List<TrackingGateway> created = new CopyOnWriteArrayList<>();

        @Override
        public ModelGateway create(ProviderEndpoint endpoint, ProviderRef reference) {
            if (fail.get()) {
                throw new IllegalStateException("模型 Adapter 重建失败");
            }
            TrackingGateway gateway = new TrackingGateway();
            created.add(gateway);
            return gateway;
        }
    }

    private static final class BlockingModelFactory implements ProviderModelRegistry.AdapterFactory {
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private final CountDownLatch rebuildStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRebuild = new CountDownLatch(1);
        private final List<TrackingGateway> created = new CopyOnWriteArrayList<>();

        @Override
        public ModelGateway create(ProviderEndpoint endpoint, ProviderRef reference) {
            if (blockNext.compareAndSet(true, false)) {
                rebuildStarted.countDown();
                try {
                    if (!releaseRebuild.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待重建放行超时");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待重建时被中断", failure);
                }
            }
            TrackingGateway gateway = new TrackingGateway();
            created.add(gateway);
            return gateway;
        }
    }

    private static final class FailingEmbeddingFactory implements EmbeddingAdapterFactory {
        private final AtomicBoolean fail = new AtomicBoolean();
        private final List<TrackingEmbeddingPort> created = new CopyOnWriteArrayList<>();

        @Override
        public EmbeddingPort create(ProviderEndpoint endpoint, ProviderRef reference) {
            if (fail.get()) {
                throw new IllegalStateException("Embedding Adapter 重建失败");
            }
            TrackingEmbeddingPort port = new TrackingEmbeddingPort();
            created.add(port);
            return port;
        }
    }

    private static final class TrackingEmbeddingPort implements EmbeddingPort, AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public EmbeddingBatch embed(List<String> texts, EmbeddingPurpose purpose, CancellationToken cancellation) {
            return new EmbeddingBatch("0".repeat(64), 1, List.of(new EmbeddingVector(List.of(1.0))));
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new java.util.HashMap<>();
        private volatile boolean loadUnavailable;

        @Override
        public Optional<byte[]> load(String keyId) {
            if (loadUnavailable) {
                throw new com.javaclaw.nativehost.credential.MasterKeyProtectionException("test unavailable");
            }
            byte[] key = keys.get(keyId);
            return key == null ? Optional.empty() : Optional.of(key.clone());
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
