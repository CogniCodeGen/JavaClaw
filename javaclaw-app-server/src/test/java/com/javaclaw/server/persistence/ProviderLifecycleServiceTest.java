package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderCapabilities;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderRole;
import com.javaclaw.protocol.CanonicalJson;

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
        providers = new ProviderService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void lifecycleHistoryAndProbeDescribeTheExactImmutableRevision() {
        ProviderEndpointSpec spec = spec(ProviderAdapter.OPENAI_RESPONSES);
        ProviderEndpoint active = providers.create(identity("provider/create", "create", 0, spec), "primary", spec);
        ProviderEndpoint disabled = providers.update(
                identity("provider/update", "disable", 1, spec), active.id(), spec, ProviderLifecycle.DISABLED);
        ProviderEndpoint enabled = providers.update(
                identity("provider/update", "enable", 2, spec), active.id(), spec, ProviderLifecycle.ACTIVE);
        CommandIdentity archiveIdentity = identity("provider/archive", "archive", 3, Map.of("id", active.id()));
        ProviderEndpoint archived = providers.archive(archiveIdentity, active.id());

        assertEquals(ProviderReadiness.CREDENTIAL_REQUIRED, probe(active, "test-model"));
        assertEquals(ProviderReadiness.DISABLED, probe(disabled, "test-model"));
        assertEquals(ProviderReadiness.INVALID_CONFIGURATION, probe(enabled, "missing-model"));
        assertEquals(ProviderReadiness.ARCHIVED, probe(archived, "test-model"));
        assertEquals(List.of(active, disabled, enabled, archived), providers.listAllVersions());
        assertEquals(List.of(archived), providers.listLatest());
        assertEquals(archived, providers.archive(archiveIdentity, active.id()));
    }

    @Test
    void updateAndLookupRejectArchivedLifecycleMissingResourcesAndUnsafeIdentifiers() {
        ProviderEndpointSpec spec = spec(ProviderAdapter.OPENAI_COMPATIBLE);
        ProviderEndpoint created = providers.create(identity("provider/create", "create", 0, spec), "primary", spec);

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
                () -> providers.create(identity("provider/create", "invalid-id", 0, spec), "bad id", spec));

        CommandIdentity create = identity("provider/create", "conflict", 0, spec);
        providers.create(create, "secondary", spec);
        CommandIdentity conflict = new CommandIdentity(
                create.method(),
                create.idempotencyKey(),
                create.expectedRevision(),
                json.encode(Map.of("payload", "different")).sha256());
        assertThrows(PersistenceException.class, () -> providers.create(conflict, "secondary", spec));
    }

    @Test
    void providerCapabilitiesKeepNativeResponsesFeaturesInsideItsAdapter() {
        Set<ProviderRole> roles = Set.of(ProviderRole.CHAT, ProviderRole.EMBEDDING);

        for (ProviderAdapter adapter :
                List.of(ProviderAdapter.OPENAI_COMPATIBLE, ProviderAdapter.ANTHROPIC, ProviderAdapter.GOOGLE_GENAI)) {
            ProviderCapabilities capabilities = ProviderService.capabilities(adapter, roles);
            assertEquals(roles, capabilities.roles());
            assertFalse(capabilities.opaqueState());
            assertFalse(capabilities.nativeCompaction());
        }
        ProviderCapabilities responses = ProviderService.capabilities(ProviderAdapter.OPENAI_RESPONSES, roles);
        assertTrue(responses.opaqueState());
        assertTrue(responses.nativeCompaction());
        assertTrue(responses.reasoningSummary());
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
                        () -> providers.create(identity("provider/create", "prepare-fails", 0, spec), "valid", spec)));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(providers.listLatest().isEmpty());
    }

    private ProviderReadiness probe(ProviderEndpoint endpoint, String model) {
        return providers
                .probe(new ProviderRef(endpoint.id(), endpoint.revision(), model))
                .readiness();
    }

    private static ProviderEndpointSpec spec(ProviderAdapter adapter) {
        return new ProviderEndpointSpec(
                "Provider",
                adapter,
                Optional.empty(),
                Set.of(ProviderRole.CHAT),
                List.of("test-model"),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                Map.of());
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return new CommandIdentity(method, key, revision, json.encode(payload).sha256());
    }
}
