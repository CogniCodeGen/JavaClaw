package com.javaclaw.server.config;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.extension.spi.EmbeddingUnavailableException;
import com.javaclaw.extension.spi.EmbeddingVector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.model.EmbeddingAdapterFactory;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.EmbeddingBindingService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.vault.VaultRuntimeGate;

import static com.javaclaw.server.ProviderEndpointTestFixtures.embedding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderEmbeddingRegistryTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private ProviderService providers;
    private EmbeddingBindingService bindings;

    @BeforeEach
    void initializeDataV5() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        providers = new ProviderService(database, reference -> true, json, Clock.fixed(NOW, ZoneOffset.UTC));
        bindings = new EmbeddingBindingService(database, providers, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exactBindingReplacesGenerationAndClosedRegistryRejectsMutation() throws Exception {
        RecordingFactory adapters = new RecordingFactory();
        ProviderEmbeddingRegistry registry =
                new ProviderEmbeddingRegistry(providers, bindings, adapters, new VaultRuntimeGate());
        assertThrows(
                EmbeddingUnavailableException.class,
                () -> registry.embed(List.of("query"), EmbeddingPurpose.QUERY, new CancellationSource()));

        ProviderEndpointSpec basic = spec("Basic", List.of("z-model", "a-model"));
        ProviderEndpoint basicEndpoint = providers.create(
                identity("provider/create", "basic-create", 0, basic), "basic", basic, ProviderLifecycle.ACTIVE);
        bindings.update(
                identity("provider/embeddingBinding/update", "basic-bind", 0, basic),
                new ProviderRef(basicEndpoint.id(), basicEndpoint.revision(), "a-model"));
        registry.embed(List.of("query"), EmbeddingPurpose.QUERY, new CancellationSource());
        assertEquals("basic:a-model", adapters.lastEmbedded.get());

        ProviderEndpointSpec preferred = spec("Preferred", List.of("preferred-model"));
        ProviderEndpoint preferredEndpoint = providers.create(
                identity("provider/create", "preferred-create", 0, preferred),
                "preferred",
                preferred,
                ProviderLifecycle.ACTIVE);
        bindings.update(
                identity("provider/embeddingBinding/update", "preferred-bind", 1, preferred),
                new ProviderRef(preferredEndpoint.id(), preferredEndpoint.revision(), "preferred-model"));
        registry.embed(List.of("document"), EmbeddingPurpose.DOCUMENT, new CancellationSource());
        assertEquals("preferred:preferred-model", adapters.lastEmbedded.get());
        assertTrue(adapters.closed.contains("basic:a-model"));

        providers.update(
                identity("provider/update", "preferred-disable", 1, preferred),
                "preferred",
                preferred,
                ProviderLifecycle.DISABLED);
        assertThrows(
                EmbeddingUnavailableException.class,
                () -> registry.embed(List.of("query"), EmbeddingPurpose.QUERY, new CancellationSource()));

        registry.close();
        registry.close();
        registry.reload();
        assertThrows(
                EmbeddingUnavailableException.class,
                () -> registry.embed(List.of("query"), EmbeddingPurpose.QUERY, new CancellationSource()));
        assertThrows(
                IllegalStateException.class,
                () -> providers.update(
                        identity("provider/update", "basic-after-close", 1, basic),
                        "basic",
                        basic,
                        ProviderLifecycle.ACTIVE));
    }

    @Test
    void bindingConstructionFailureDoesNotCommitBinding() {
        RecordingFactory adapters = new RecordingFactory();
        adapters.rejectedModel = "broken-model";
        try (ProviderEmbeddingRegistry registry =
                new ProviderEmbeddingRegistry(providers, bindings, adapters, new VaultRuntimeGate())) {
            ProviderEndpointSpec rejected = spec("Rejected", List.of("valid-model", "broken-model"));
            ProviderEndpoint endpoint = providers.create(
                    identity("provider/create", "rejected-create", 0, rejected),
                    "rejected",
                    rejected,
                    ProviderLifecycle.ACTIVE);

            assertThrows(
                    IllegalStateException.class,
                    () -> bindings.update(
                            identity("provider/embeddingBinding/update", "rejected-bind", 0, rejected),
                            new ProviderRef(endpoint.id(), endpoint.revision(), "broken-model")));

            assertTrue(bindings.find().isEmpty());
        }
    }

    @Test
    void retiringGenerationWaitsForInFlightEmbeddingLease() throws Exception {
        BlockingFactory adapters = new BlockingFactory();
        ProviderEndpointSpec spec = spec("Blocking", List.of("embedding-model"));
        try (ProviderEmbeddingRegistry registry =
                new ProviderEmbeddingRegistry(providers, bindings, adapters, new VaultRuntimeGate())) {
            ProviderEndpoint endpoint = providers.create(
                    identity("provider/create", "blocking-create", 0, spec),
                    "blocking",
                    spec,
                    ProviderLifecycle.ACTIVE);
            bindings.update(
                    identity("provider/embeddingBinding/update", "blocking-bind", 0, spec),
                    new ProviderRef(endpoint.id(), endpoint.revision(), "embedding-model"));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread invocation = Thread.ofVirtual().start(() -> invoke(registry, adapters, failure));
            assertTrue(adapters.started.await(2, TimeUnit.SECONDS));

            providers.update(
                    identity("provider/update", "blocking-disable", 1, spec),
                    "blocking",
                    spec,
                    ProviderLifecycle.DISABLED);
            assertFalse(adapters.active.closed.get());

            adapters.release.countDown();
            invocation.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(invocation.isAlive());
            assertNull(failure.get());
            assertTrue(adapters.active.closed.get());
        } finally {
            adapters.release.countDown();
        }
    }

    private void invoke(
            ProviderEmbeddingRegistry registry, BlockingFactory adapters, AtomicReference<Throwable> failure) {
        try {
            registry.embed(List.of("query"), EmbeddingPurpose.QUERY, new CancellationSource());
        } catch (Throwable thrown) {
            failure.set(thrown);
            adapters.release.countDown();
        }
    }

    private ProviderEndpointSpec spec(String name, List<String> models) {
        return embedding(name, ProviderAdapter.OPENAI_COMPATIBLE, models.toArray(String[]::new));
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    private static EmbeddingBatch batch() {
        return new EmbeddingBatch("0".repeat(64), 1, List.of(new EmbeddingVector(List.of(1.0))));
    }

    private static class RecordingPort implements EmbeddingPort, AutoCloseable {
        private final String route;
        private final AtomicReference<String> lastEmbedded;
        private final List<String> closed;

        private RecordingPort(String route, AtomicReference<String> lastEmbedded, List<String> closed) {
            this.route = route;
            this.lastEmbedded = lastEmbedded;
            this.closed = closed;
        }

        @Override
        public EmbeddingBatch embed(
                List<String> texts, EmbeddingPurpose purpose, com.javaclaw.api.CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            lastEmbedded.set(route);
            return batch();
        }

        @Override
        public void close() {
            closed.add(route);
        }
    }

    private static final class RecordingFactory implements EmbeddingAdapterFactory {
        private final AtomicReference<String> lastEmbedded = new AtomicReference<>();
        private final List<String> closed = new CopyOnWriteArrayList<>();
        private volatile String rejectedModel = "";

        @Override
        public EmbeddingPort create(ProviderEndpoint endpoint, ProviderRef reference) {
            if (rejectedModel.equals(reference.model())) {
                throw new IllegalStateException("adapter rejected model");
            }
            return new RecordingPort(endpoint.id() + ':' + reference.model(), lastEmbedded, closed);
        }
    }

    private static final class BlockingFactory implements EmbeddingAdapterFactory {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private BlockingPort active;

        @Override
        public EmbeddingPort create(ProviderEndpoint endpoint, ProviderRef reference) {
            active = new BlockingPort(started, release);
            return active;
        }
    }

    private static final class BlockingPort implements EmbeddingPort, AutoCloseable {
        private final CountDownLatch started;
        private final CountDownLatch release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BlockingPort(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        @Override
        public EmbeddingBatch embed(
                List<String> texts, EmbeddingPurpose purpose, com.javaclaw.api.CancellationToken cancellation)
                throws Exception {
            started.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("embedding lease was not released");
            }
            return batch();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
