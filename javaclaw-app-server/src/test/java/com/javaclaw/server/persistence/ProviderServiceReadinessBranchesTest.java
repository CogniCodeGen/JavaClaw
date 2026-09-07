package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.ProviderEndpointTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderServiceReadinessBranchesTest {
    private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;

    @BeforeEach
    void initializeDataV6() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
    }

    @Test
    void createRejectsArchivedLifecycleAtThePublicBoundary() {
        ProviderService providers = providers(reference -> true);
        ProviderEndpointSpec spec =
                ProviderEndpointTestFixtures.chat("Provider", ProviderAdapter.OPENAI_COMPATIBLE, "chat-model");

        assertThrows(
                PersistenceException.class,
                () -> providers.create(
                        identity("provider/create", "archived-create", 0, spec),
                        "archived-create",
                        spec,
                        ProviderLifecycle.ARCHIVED));
    }

    @Test
    void archivedLatestRevisionBlocksDiscoveryOfAnOlderActiveRevision() {
        ProviderService providers = providers(reference -> true);
        ProviderEndpointSpec spec =
                ProviderEndpointTestFixtures.chat("Provider", ProviderAdapter.OPENAI_COMPATIBLE, "chat-model");
        ProviderEndpoint active = providers.create(
                identity("provider/create", "discover-create", 0, spec),
                "discoverable",
                spec,
                ProviderLifecycle.ACTIVE);
        providers.archive(identity("provider/archive", "discover-archive", 1, Map.of("id", active.id())), active.id());

        assertThrows(PersistenceException.class, () -> providers.requireDiscoverable(active.id(), active.revision()));
    }

    @Test
    void probeUsesCurrentCredentialAvailabilityAndReportsMissingPersistedReference() throws Exception {
        CredentialRef available = new CredentialRef("provider", "available-secret");
        CredentialRef unavailable = new CredentialRef("provider", "unavailable-secret");
        ProviderEndpoint ready = endpoint("credential-ready", withCredential(apiKeySpec(), available));
        ProviderEndpoint unavailableEndpoint =
                endpoint("credential-unavailable", withCredential(apiKeySpec(), unavailable));
        ProviderEndpoint missing = endpoint("credential-missing", apiKeySpec());
        insert(ready);
        insert(unavailableEndpoint);
        insert(missing);
        ProviderService providers = providers(available::equals);

        assertEquals(ProviderReadiness.READY, probe(providers, ready));
        assertEquals(ProviderReadiness.CREDENTIAL_UNAVAILABLE, probe(providers, unavailableEndpoint));
        assertEquals(ProviderReadiness.CREDENTIAL_REQUIRED, probe(providers, missing));
        assertEquals(ready, providers.requireAvailable(reference(ready), ProviderModelPurpose.CHAT));
        assertThrows(
                PersistenceException.class,
                () -> providers.requireAvailable(reference(unavailableEndpoint), ProviderModelPurpose.CHAT));
        assertThrows(
                PersistenceException.class,
                () -> providers.requireAvailable(reference(missing), ProviderModelPurpose.CHAT));
    }

    private ProviderService providers(CredentialAvailabilityPort credentials) {
        return new ProviderService(database, credentials, json, CLOCK);
    }

    private ProviderEndpointSpec apiKeySpec() {
        return ProviderEndpointTestFixtures.apiKeyChat("Provider", ProviderAdapter.OPENAI_COMPATIBLE, "chat-model");
    }

    private static ProviderEndpointSpec withCredential(ProviderEndpointSpec source, CredentialRef credential) {
        return new ProviderEndpointSpec(
                source.displayName(),
                source.adapter(),
                source.baseUri(),
                source.authentication(),
                source.models(),
                Optional.of(credential),
                source.timeout(),
                source.maximumRetries(),
                source.options());
    }

    private static ProviderEndpoint endpoint(String id, ProviderEndpointSpec spec) {
        return new ProviderEndpoint(id, 1, ProviderLifecycle.ACTIVE, spec, NOW, NOW);
    }

    private void insert(ProviderEndpoint endpoint) throws Exception {
        new H2Transactions(database).execute(connection -> {
            new ProviderCredentialTransactionPort(json).insert(connection, endpoint);
            return null;
        });
    }

    private ProviderReadiness probe(ProviderService providers, ProviderEndpoint endpoint) {
        return providers.probe(reference(endpoint)).readiness();
    }

    private static ProviderRef reference(ProviderEndpoint endpoint) {
        return new ProviderRef(endpoint.id(), endpoint.revision(), "chat-model");
    }

    private CommandIdentity identity(String method, String key, long expectedRevision, Object payload) {
        return new CommandIdentity(
                method, key, expectedRevision, json.encode(payload).sha256());
    }
}
