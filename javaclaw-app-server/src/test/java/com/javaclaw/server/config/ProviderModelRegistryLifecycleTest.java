package com.javaclaw.server.config;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.NativeCompactionRequest;
import com.javaclaw.runtime.NativeCompactionResult;
import com.javaclaw.runtime.NativeCompactionSupport;
import com.javaclaw.runtime.NativeConversationSupport;
import com.javaclaw.runtime.ProviderState;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.vault.VaultRuntimeGate;

import static com.javaclaw.server.ProviderEndpointTestFixtures.chat;
import static com.javaclaw.server.ProviderEndpointTestFixtures.embedding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderModelRegistryLifecycleTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void routingUsesExactProviderRevisionAndClosesSharedAdapterOnlyOnce() throws Exception {
        ProviderService providers = providers("routing");
        ProviderEndpoint endpoint = providers.create(
                identity("provider/create", "routing-create", 0, Map.of("scenario", "routing")),
                "primary",
                spec(ProviderModelPurpose.CHAT, List.of("first", "second")),
                ProviderLifecycle.ACTIVE);
        TrackingGateway shared = new TrackingGateway();
        String firstRoute = route(endpoint, "first");

        ProviderModelRegistry registry =
                new ProviderModelRegistry(providers, (candidate, reference) -> shared, new VaultRuntimeGate());

        assertEquals(shared.capabilities(firstRoute), registry.capabilities(firstRoute));
        assertEquals(
                "invoked",
                registry.invoke(TurnId.random(), invocation(firstRoute), ignoredEvents(), new CancellationSource())
                        .text());
        assertThrows(IllegalArgumentException.class, () -> registry.capabilities("missing-route"));
        registry.close();
        registry.close();
        registry.reload();
        assertEquals(1, shared.closed.get());
        assertThrows(IllegalArgumentException.class, () -> registry.capabilities(firstRoute));
    }

    @Test
    void disabledProviderIsImmediateKillSwitchAndClosedRegistryRejectsMutation() {
        ProviderService providers = providers("kill-switch");
        ProviderEndpointSpec spec = spec(ProviderModelPurpose.CHAT, List.of("model"));
        ProviderEndpoint active = providers.create(
                identity("provider/create", "kill-create", 0, spec), "primary", spec, ProviderLifecycle.ACTIVE);
        TrackingGateway adapter = new TrackingGateway();
        ProviderModelRegistry registry =
                new ProviderModelRegistry(providers, (candidate, reference) -> adapter, new VaultRuntimeGate());
        String route = route(active, "model");

        ProviderEndpoint disabled = providers.update(
                identity("provider/update", "kill-disable", 1, spec), active.id(), spec, ProviderLifecycle.DISABLED);

        assertEquals(ProviderLifecycle.DISABLED, disabled.lifecycle());
        assertThrows(IllegalArgumentException.class, () -> registry.capabilities(route));
        assertEquals(1, adapter.closed.get());
        registry.close();
        assertThrows(
                IllegalStateException.class,
                () -> providers.update(
                        identity("provider/update", "after-close", 2, spec),
                        active.id(),
                        spec,
                        ProviderLifecycle.ACTIVE));
        assertEquals(
                ProviderLifecycle.DISABLED, providers.listLatest().getFirst().lifecycle());
    }

    @Test
    void nonChatProviderIsNotConstructedAndFailedGenerationClosesPartialAdapters() {
        ProviderService embeddings = providers("embedding-only");
        ProviderEndpoint embedding = embeddings.create(
                identity("provider/create", "embedding", 0, Map.of("scenario", "embedding")),
                "embedding",
                spec(ProviderModelPurpose.EMBEDDING, List.of("vector")),
                ProviderLifecycle.ACTIVE);
        AtomicInteger creations = new AtomicInteger();
        try (ProviderModelRegistry registry = new ProviderModelRegistry(
                embeddings,
                (candidate, reference) -> {
                    creations.incrementAndGet();
                    return new TrackingGateway();
                },
                new VaultRuntimeGate())) {
            assertEquals(0, creations.get());
            assertThrows(IllegalArgumentException.class, () -> registry.capabilities(route(embedding, "vector")));
        }

        ProviderService failing = providers("failed-generation");
        failing.create(
                identity("provider/create", "failed", 0, Map.of("scenario", "failed-generation")),
                "failed",
                spec(ProviderModelPurpose.CHAT, List.of("first", "second")),
                ProviderLifecycle.ACTIVE);
        TrackingGateway partial = new TrackingGateway();
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(
                IllegalStateException.class,
                () -> new ProviderModelRegistry(
                        failing,
                        (candidate, reference) -> {
                            if (attempts.incrementAndGet() == 1) {
                                return partial;
                            }
                            throw new IllegalStateException("adapter construction failed");
                        },
                        new VaultRuntimeGate()));
        assertEquals(1, partial.closed.get());
    }

    @Test
    void nativeConversationAndCompactionRequireExplicitAdapterCapabilities() throws Exception {
        ProviderService nativeProviders = providers("native");
        ProviderEndpoint nativeEndpoint = nativeProviders.create(
                identity("provider/create", "native", 0, Map.of("scenario", "native")),
                "native",
                spec(ProviderModelPurpose.CHAT, List.of("model")),
                ProviderLifecycle.ACTIVE);
        NativeGateway nativeAdapter = new NativeGateway();
        ProviderState state = new ProviderState("native", "v1", new CanonicalJson().parse("{}"));
        String nativeRoute = route(nativeEndpoint, "model");
        try (ProviderModelRegistry registry = new ProviderModelRegistry(
                nativeProviders, (candidate, reference) -> nativeAdapter, new VaultRuntimeGate())) {
            assertEquals(
                    "continued",
                    registry.invokeContinuing(
                                    TurnId.random(),
                                    invocation(nativeRoute),
                                    state,
                                    ignoredEvents(),
                                    new CancellationSource())
                            .text());
            assertEquals(
                    7,
                    registry.compact(
                                    new NativeCompactionRequest(TurnId.random(), nativeRoute, state),
                                    new CancellationSource())
                            .consumedTokens());
        }

        ProviderService plainProviders = providers("plain");
        ProviderEndpoint plain = plainProviders.create(
                identity("provider/create", "plain", 0, Map.of("scenario", "plain")),
                "plain",
                spec(ProviderModelPurpose.CHAT, List.of("model")),
                ProviderLifecycle.ACTIVE);
        try (ProviderModelRegistry registry = new ProviderModelRegistry(
                plainProviders, (candidate, reference) -> new TrackingGateway(), new VaultRuntimeGate())) {
            String route = route(plain, "model");
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.invokeContinuing(
                            TurnId.random(), invocation(route), state, ignoredEvents(), new CancellationSource()));
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.compact(
                            new NativeCompactionRequest(TurnId.random(), route, state), new CancellationSource()));
        }
    }

    private ProviderService providers(String suffix) {
        H2Database database = new H2Database(temporaryDirectory.resolve(suffix).resolve("data-v6"));
        database.initialize();
        return new ProviderService(database, reference -> true, new CanonicalJson(), CLOCK);
    }

    private static ProviderEndpointSpec spec(ProviderModelPurpose purpose, List<String> models) {
        String[] modelIds = models.toArray(String[]::new);
        return purpose == ProviderModelPurpose.CHAT
                ? chat("Provider", ProviderAdapter.OPENAI_COMPATIBLE, modelIds)
                : embedding("Provider", ProviderAdapter.OPENAI_COMPATIBLE, modelIds);
    }

    private static String route(ProviderEndpoint endpoint, String model) {
        return new ProviderRef(endpoint.id(), endpoint.revision(), model).routeKey();
    }

    private static ModelInvocation invocation(String route) {
        return new ModelInvocation(route, "", List.of(), List.of(), 10);
    }

    private static ModelEventSink ignoredEvents() {
        return (turnId, event, cancellation) -> {};
    }

    private static CommandIdentity identity(String method, String key, long revision, Object payload) {
        return new CommandIdentity(
                method, key, revision, new CanonicalJson().encode(payload).sha256());
    }

    private static ModelInvocationResult result(String text, Optional<ProviderState> state) {
        return new ModelInvocationResult(
                text, List.of(), new ModelUsage(1, 1, 0, 0), Optional.empty(), state, ModelFinishReason.COMPLETE);
    }

    private static class TrackingGateway implements ModelGateway, AutoCloseable {
        private final AtomicInteger closed = new AtomicInteger();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(true, true, true, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            return result("invoked", Optional.empty());
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }

    private static final class NativeGateway extends TrackingGateway
            implements NativeConversationSupport, NativeCompactionSupport {
        @Override
        public ModelInvocationResult invokeContinuing(
                TurnId turnId,
                ModelInvocation invocation,
                ProviderState state,
                ModelEventSink events,
                CancellationToken cancellation) {
            return result("continued", Optional.of(state));
        }

        @Override
        public NativeCompactionResult compact(NativeCompactionRequest request, CancellationToken cancellation) {
            return new NativeCompactionResult(request.state(), 7);
        }
    }
}
