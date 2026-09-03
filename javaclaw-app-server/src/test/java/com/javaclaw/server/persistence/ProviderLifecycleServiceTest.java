package com.javaclaw.server.persistence;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderCapabilities;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.ProviderEndpointTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderLifecycleServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private ProviderService providers;

    @BeforeEach
    void initializeDataV5() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        providers = new ProviderService(database, reference -> true, json, clock);
    }

    @Test
    void lifecycleHistoryAndProbeDescribeTheExactImmutableRevision() {
        ProviderEndpointSpec spec = spec(ProviderAdapter.OPENAI_COMPATIBLE);
        ProviderEndpoint active = providers.create(
                identity("provider/create", "create", 0, spec), "primary", spec, ProviderLifecycle.ACTIVE);
        assertEquals(ProviderReadiness.READY, probe(active, "test-model"));

        ProviderEndpoint disabled = providers.update(
                identity("provider/update", "disable", 1, spec), active.id(), spec, ProviderLifecycle.DISABLED);
        assertEquals(ProviderReadiness.DISABLED, probe(active, "test-model"));
        assertEquals(ProviderReadiness.DISABLED, probe(disabled, "test-model"));

        ProviderEndpoint enabled = providers.update(
                identity("provider/update", "enable", 2, spec), active.id(), spec, ProviderLifecycle.ACTIVE);
        assertEquals(ProviderReadiness.READY, probe(active, "test-model"));
        assertEquals(ProviderReadiness.INVALID_CONFIGURATION, probe(enabled, "missing-model"));

        CommandIdentity archiveIdentity = identity("provider/archive", "archive", 3, Map.of("id", active.id()));
        ProviderEndpoint archived = providers.archive(archiveIdentity, active.id());

        assertEquals(ProviderReadiness.ARCHIVED, probe(active, "test-model"));
        assertEquals(ProviderReadiness.ARCHIVED, probe(archived, "test-model"));
        assertEquals(List.of(active, disabled, enabled, archived), providers.listAllVersions());
        assertEquals(List.of(archived), providers.listLatest());
        assertEquals(archived, providers.archive(archiveIdentity, active.id()));
    }

    @Test
    void updateAndLookupRejectArchivedLifecycleMissingResourcesAndUnsafeIdentifiers() {
        ProviderEndpointSpec spec = spec(ProviderAdapter.OPENAI_COMPATIBLE);
        ProviderEndpoint created = providers.create(
                identity("provider/create", "create", 0, spec), "primary", spec, ProviderLifecycle.ACTIVE);

        assertThrows(
                PersistenceException.class,
                () -> providers.update(
                        identity("provider/update", "archive-via-update", 1, spec),
                        created.id(),
                        spec,
                        ProviderLifecycle.ARCHIVED));
        assertThrows(PersistenceException.class, () -> providers.require("missing", 1));
        assertThrows(
                PersistenceException.class,
                () -> providers.archive(
                        identity("provider/archive", "missing", 1, Map.of("id", "missing")), "missing"));
        assertThrows(
                IllegalArgumentException.class,
                () -> providers.create(
                        identity("provider/create", "invalid-id", 0, spec), "bad id", spec, ProviderLifecycle.ACTIVE));

        CommandIdentity create = identity("provider/create", "conflict", 0, spec);
        providers.create(create, "secondary", spec, ProviderLifecycle.ACTIVE);
        CommandIdentity conflict = new CommandIdentity(
                create.method(),
                create.idempotencyKey(),
                create.expectedRevision(),
                json.encode(Map.of("payload", "different")).sha256());
        assertThrows(
                PersistenceException.class,
                () -> providers.create(conflict, "secondary", spec, ProviderLifecycle.ACTIVE));
    }

    @Test
    void providerCapabilitiesKeepNativeResponsesFeaturesInsideItsAdapter() {
        Set<ProviderModelPurpose> chatAndEmbedding = Set.of(ProviderModelPurpose.CHAT, ProviderModelPurpose.EMBEDDING);

        for (ProviderAdapter adapter : List.of(ProviderAdapter.OPENAI_COMPATIBLE, ProviderAdapter.GOOGLE_GENAI)) {
            ProviderCapabilities capabilities = ProviderService.capabilities(adapter, chatAndEmbedding);
            assertEquals(chatAndEmbedding, capabilities.purposes());
            assertFalse(capabilities.opaqueState());
            assertFalse(capabilities.nativeCompaction());
        }
        Set<ProviderModelPurpose> chat = Set.of(ProviderModelPurpose.CHAT);
        ProviderCapabilities anthropic = ProviderService.capabilities(ProviderAdapter.ANTHROPIC, chat);
        assertEquals(chat, anthropic.purposes());
        ProviderCapabilities responses = ProviderService.capabilities(ProviderAdapter.OPENAI_RESPONSES, chat);
        assertEquals(chat, responses.purposes());
        assertTrue(responses.opaqueState());
        assertTrue(responses.nativeCompaction());
        assertTrue(responses.reasoningSummary());
    }

    @Test
    void probeReportsOnlyTheSelectedModelsPurposesAndChatCapabilities() {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "Mixed",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:11434/v1")),
                ProviderAuthentication.NONE,
                List.of(
                        new ProviderModelSpec(
                                "chat-model", "Chat", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty()),
                        new ProviderModelSpec(
                                "embedding-model",
                                "Embedding",
                                Set.of(ProviderModelPurpose.EMBEDDING),
                                OptionalInt.of(768))),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        ProviderEndpoint endpoint = providers.create(
                identity("provider/create", "mixed", 0, spec), "mixed", spec, ProviderLifecycle.ACTIVE);

        ProviderCapabilities chat = providers
                .probe(new ProviderRef(endpoint.id(), endpoint.revision(), "chat-model"))
                .capabilities();
        ProviderCapabilities embedding = providers
                .probe(new ProviderRef(endpoint.id(), endpoint.revision(), "embedding-model"))
                .capabilities();

        assertEquals(Set.of(ProviderModelPurpose.CHAT), chat.purposes());
        assertTrue(chat.streaming());
        assertEquals(Set.of(ProviderModelPurpose.EMBEDDING), embedding.purposes());
        assertFalse(embedding.streaming());
        assertFalse(embedding.toolCalls());
        assertFalse(embedding.structuredOutput());
    }

    @Test
    void 历史精确版本在最新版本启用时可用而停用归档立即阻断() {
        ProviderEndpointSpec spec = spec(ProviderAdapter.OPENAI_COMPATIBLE);
        ProviderEndpoint first = providers.create(
                identity("provider/create", "history-create", 0, spec), "history", spec, ProviderLifecycle.ACTIVE);
        ProviderRef original = new ProviderRef(first.id(), first.revision(), "test-model");
        providers.update(
                identity("provider/update", "history-v2", 1, spec), first.id(), spec, ProviderLifecycle.ACTIVE);

        assertEquals(first, providers.requireAvailable(original, ProviderModelPurpose.CHAT));

        providers.update(
                identity("provider/update", "history-disable", 2, spec), first.id(), spec, ProviderLifecycle.DISABLED);
        assertThrows(PersistenceException.class, () -> providers.requireAvailable(original, ProviderModelPurpose.CHAT));
        providers.update(
                identity("provider/update", "history-enable", 3, spec), first.id(), spec, ProviderLifecycle.ACTIVE);
        assertEquals(first, providers.requireAvailable(original, ProviderModelPurpose.CHAT));

        providers.archive(identity("provider/archive", "history-archive", 4, Map.of("id", first.id())), first.id());
        assertThrows(PersistenceException.class, () -> providers.requireAvailable(original, ProviderModelPurpose.CHAT));
    }

    @Test
    void invalidCandidateRevisionAndFailedCleanupNeverCommitConfiguration() {
        ProviderEndpointSpec spec = spec(ProviderAdapter.OPENAI_COMPATIBLE);
        ProviderEndpoint invalid = new ProviderEndpoint("primary", 2, ProviderLifecycle.ACTIVE, spec, NOW, NOW);
        AtomicBoolean committed = new AtomicBoolean();
        assertThrows(
                PersistenceException.class,
                () -> providers.coordinatePreparedMutation(invalid, 0, () -> {
                    committed.set(true);
                    return invalid;
                }));
        assertFalse(committed.get());

        IllegalStateException failure = new IllegalStateException("prepare failed");
        providers.participate(candidate -> new ProviderService.PreparedProviderChange() {
            @Override
            public void activate() {}

            @Override
            public void close() {
                throw new IllegalStateException("cleanup failed");
            }
        });
        providers.participate(candidate -> {
            throw failure;
        });
        assertEquals(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> providers.create(
                                identity("provider/create", "prepare-fails", 0, spec),
                                "valid",
                                spec,
                                ProviderLifecycle.ACTIVE)));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(providers.listLatest().isEmpty());
    }

    private ProviderReadiness probe(ProviderEndpoint endpoint, String model) {
        return providers
                .probe(new ProviderRef(endpoint.id(), endpoint.revision(), model))
                .readiness();
    }

    private static ProviderEndpointSpec spec(ProviderAdapter adapter) {
        return ProviderEndpointTestFixtures.chat("Provider", adapter, "test-model");
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return new CommandIdentity(method, key, revision, json.encode(payload).sha256());
    }
}
