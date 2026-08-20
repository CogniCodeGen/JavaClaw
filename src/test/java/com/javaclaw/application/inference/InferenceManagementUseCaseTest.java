package com.javaclaw.application.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import com.javaclaw.infrastructure.inference.JdbcInferenceCatalog;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceManagementUseCaseTest {

    private JdbcInferenceCatalog catalog;
    private InferenceModelAsset asset;
    private InferenceModelProfile profile;
    private InferenceCatalogPort.RuntimeInstallation installation;

    @BeforeEach
    void createCatalog() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:inference-usecase-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        catalog = new JdbcInferenceCatalog(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource), new ObjectMapper().findAndRegisterModules());
        var manifest = new InferenceRuntimeManifest("runtime", "deliverance", "0.0.12", "1",
                new InferenceRuntimeManifest.ProtocolVersion(1, 1), "test", "test", 25,
                Set.of("chat", "exact_usage", "terminal_usage", "cancellation",
                        "model-type:generation:qwen2", "model-type:embedding:qwen2"),
                Map.of("type", "object"),
                List.of(new InferenceRuntimeManifest.RuntimeFile("deliverance.jar",
                        "a".repeat(64), 1)), true);
        installation = new InferenceCatalogPort.RuntimeInstallation(manifest, "/tmp/runtime",
                InferenceCatalogPort.RuntimeState.ACTIVE, true, Instant.now());
        catalog.saveRuntime(installation);
        asset = new InferenceModelAsset(UUID.randomUUID(),
                InferenceModelAsset.Source.LOCAL_DIRECTORY, "", "qwen2",
                "b".repeat(64), "/tmp/model", "", "",
                List.of(new InferenceModelAsset.AssetFile("config.json", 1, "c".repeat(64))), 1,
                InferenceModelAsset.State.READY, "", Instant.now());
        catalog.saveAsset(asset);
        profile = new InferenceModelProfile(UUID.randomUUID(), "ready",
                InferenceModelProfile.Kind.GENERATION, asset.id(), manifest.runtimeId(), Map.of(), Map.of(),
                2048, 0, InferenceModelProfile.State.READY, "", Instant.now(), Instant.now());
        catalog.saveProfile(profile);
        profile = catalog.profile(profile.id()).orElseThrow();
    }

    @Test
    void cancelledDraftProbeLeavesThePreviouslyReadyProfileUntouched() {
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(new CancellationException("cancelled")));

        assertThrows(CancellationException.class,
                () -> useCase.saveAndVerifyProfile(draft(profile), () -> true));

        assertEquals(profile, catalog.profile(profile.id()).orElseThrow());
    }

    @Test
    void advancedManagementProjectionsReadOnlyTheirRequiredCatalogSegments() {
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null));

        var models = useCase.snapshot("workspace",
                InferenceManagementApplicationService.Projection.MODEL_MANAGEMENT);
        assertEquals(1, models.runtimes().size());
        assertEquals(1, models.assets().size());
        assertEquals(1, models.profiles().size());
        assertTrue(models.publishedModels().isEmpty());
        assertTrue(models.apiKeys().isEmpty());

        var profiles = useCase.snapshot("workspace",
                InferenceManagementApplicationService.Projection.PROFILE_CONFIGURATION);
        assertEquals(1, profiles.runtimes().size());
        assertEquals(1, profiles.assets().size());
        assertEquals(1, profiles.profiles().size());
        assertTrue(profiles.publishedModels().isEmpty());
        assertTrue(profiles.apiKeys().isEmpty());

        var assets = useCase.snapshot("workspace",
                InferenceManagementApplicationService.Projection.ASSET_CATALOG);
        assertEquals(1, assets.assets().size());
        assertEquals(1, assets.runtimes().size());
        assertEquals(1, assets.profiles().size());

        var runtimes = useCase.snapshot("workspace",
                InferenceManagementApplicationService.Projection.RUNTIME_CATALOG);
        assertEquals(1, runtimes.runtimes().size());
        assertEquals(1, runtimes.profiles().size());
        assertTrue(runtimes.assets().isEmpty());

        var api = useCase.snapshot("workspace",
                InferenceManagementApplicationService.Projection.API_CONFIGURATION);
        assertEquals(1, api.profiles().size());
        assertTrue(api.runtimes().isEmpty());
        assertTrue(api.assets().isEmpty());
        assertEquals(catalog.gatewayConfiguration().bindAddress(), api.gateway().bindAddress());
        assertEquals(catalog.gatewayConfiguration().port(), api.gateway().port());
        assertThrows(NullPointerException.class, () -> useCase.snapshot("workspace", null));
    }

    @Test
    void modelWorkbenchReadsOnlyRunningStatusesForRequestedProfiles() {
        FakeRuntime runtime = new FakeRuntime(null);
        UUID stopped = UUID.randomUUID();
        runtime.statuses.put(profile.id(), new InferenceRuntimePort.RuntimeProfileStatus(
                profile.id(), true, 2048, 0, "metal", Set.of("chat")));
        runtime.statuses.put(stopped, new InferenceRuntimePort.RuntimeProfileStatus(
                stopped, false, 2048, 0, "metal", Set.of("chat")));
        InferenceManagementUseCase useCase = useCase(runtime);

        assertEquals(Set.of(profile.id()),
                useCase.runtimeStatuses(Set.of(profile.id(), stopped)).keySet());
        assertTrue(useCase.runtimeStatuses(Set.of()).isEmpty());
        assertTrue(useCase.runtimeStatuses(null).isEmpty());
    }

    @Test
    void runtimeTransitionReturnsOnlyAfterTheRequestedStateIsConfirmed() throws Exception {
        FakeRuntime runtime = new FakeRuntime(null);
        InferenceManagementUseCase useCase = useCase(runtime);

        var loaded = useCase.setProfileRunning(profile.id(), true);
        assertTrue(loaded.running());
        assertEquals(profile.id(), loaded.status().profileId());

        var unloaded = useCase.setProfileRunning(profile.id(), false);
        assertFalse(unloaded.running());
        assertEquals(null, unloaded.status());

        runtime.reportTransitions = false;
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> useCase.setProfileRunning(profile.id(), true));
        assertTrue(failure.getMessage().contains("未确认驻留状态"));
    }

    @Test
    void readingTheRestoredCatalogNeverAutoLoadsAProfile() {
        FakeRuntime runtime = new FakeRuntime(null);
        catalog.saveProfile(profile);

        useCase(runtime).snapshot("workspace");

        assertEquals(0, runtime.startCalls);
    }

    @Test
    void actualProbeFailureLeavesThePreviouslyReadyProfileUntouched() {
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(new IllegalStateException("load failed")));

        assertThrows(IllegalStateException.class,
                () -> useCase.saveAndVerifyProfile(draft(profile), () -> false));

        assertEquals(profile, catalog.profile(profile.id()).orElseThrow());
    }

    @Test
    void cannotRevokeTheLastKeyWhileTheGatewayIsEnabled() {
        UUID keyId = UUID.randomUUID();
        catalog.saveApiKey(new InferenceCatalogPort.ApiKeyRecord(keyId, "only", "jcl_12345678",
                "salt", "digest", Set.of(InferenceCatalogPort.ApiScope.MODELS_READ), Set.of(),
                60, 10_000, 1, false, Instant.now(), null));
        catalog.saveGatewayConfiguration(new InferenceCatalogPort.GatewayConfiguration(true,
                "127.0.0.1", 18080, false, "", "", 4096, 30, Instant.now()));

        assertThrows(IllegalStateException.class, () -> useCase(new FakeRuntime(null)).revokeApiKey(keyId));
        assertEquals(false, catalog.apiKeys().getFirst().revoked());
    }

    @Test
    void profileVerificationRequiresReadyAssetAndInstalledRuntime() throws Exception {
        FakeRuntime runtime = new FakeRuntime(null);
        InferenceManagementUseCase useCase = useCase(runtime);

        InferenceModelProfile saved = useCase.saveAndVerifyProfile(draft(profile), () -> false);
        assertEquals(profile.id(), saved.id());
        assertEquals(InferenceModelProfile.State.READY, saved.state());
        assertEquals(saved, runtime.validated);

        InferenceModelProfile missingAsset = profileWith(InferenceModelProfile.Kind.GENERATION,
                UUID.randomUUID(), installation.manifest().runtimeId(), InferenceModelProfile.State.DRAFT);
        assertThrows(IllegalStateException.class,
                () -> useCase.saveAndVerifyProfile(draft(missingAsset), () -> false));

        InferenceModelAsset staging = assetWith(InferenceModelAsset.State.STAGING);
        catalog.saveAsset(staging);
        InferenceModelProfile unprepared = profileWith(InferenceModelProfile.Kind.GENERATION,
                staging.id(), installation.manifest().runtimeId(), InferenceModelProfile.State.DRAFT);
        assertThrows(IllegalStateException.class,
                () -> useCase.saveAndVerifyProfile(draft(unprepared), () -> false));

        InferenceModelProfile missingRuntime = profileWith(InferenceModelProfile.Kind.GENERATION,
                asset.id(), "missing-runtime", InferenceModelProfile.State.DRAFT);
        assertThrows(IllegalStateException.class,
                () -> useCase.saveAndVerifyProfile(draft(missingRuntime), () -> false));

        InferenceModelAsset unsupported = new InferenceModelAsset(UUID.randomUUID(),
                InferenceModelAsset.Source.LOCAL_DIRECTORY, "unsupported", "phi3",
                "e".repeat(64), "/tmp/unsupported", "", "", List.of(), 0,
                InferenceModelAsset.State.READY, "", Instant.now());
        catalog.saveAsset(unsupported);
        var unsupportedDraft = new InferenceManagementApplicationService.ProfileDraft(
                UUID.randomUUID(), "unsupported", InferenceModelProfile.Kind.GENERATION,
                unsupported.id(), installation.manifest().runtimeId(), Map.of(), Map.of(), 0);
        IllegalArgumentException unsupportedFailure = assertThrows(IllegalArgumentException.class,
                () -> useCase.saveAndVerifyProfile(unsupportedDraft, () -> false));
        assertTrue(unsupportedFailure.getMessage().contains("不支持"));
    }

    @Test
    void probeFillsAutomaticContextAndEmbeddingDimensionsAndRejectsExplicitOverflow() throws Exception {
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null));
        var automatic = new InferenceManagementApplicationService.ProfileDraft(
                UUID.randomUUID(), "automatic", InferenceModelProfile.Kind.GENERATION,
                asset.id(), installation.manifest().runtimeId(), Map.of(), Map.of(), 0);
        assertEquals(8192, useCase.saveAndVerifyProfile(automatic, () -> false).contextLength());

        var embedding = new InferenceManagementApplicationService.ProfileDraft(
                UUID.randomUUID(), "embedding", InferenceModelProfile.Kind.EMBEDDING,
                asset.id(), installation.manifest().runtimeId(), Map.of(), Map.of(), 2048);
        assertEquals(32, useCase.saveAndVerifyProfile(embedding, () -> false).embeddingDimensions());

        var overflow = new InferenceManagementApplicationService.ProfileDraft(
                profile.id(), profile.name(), profile.kind(), profile.assetId(), profile.runtimeId(),
                Map.of("new", true), profile.defaultParameters(), 8193);
        assertThrows(IllegalArgumentException.class,
                () -> useCase.saveAndVerifyProfile(overflow, () -> false));
        assertEquals(profile, catalog.profile(profile.id()).orElseThrow());
    }

    @Test
    void databaseWriteFailureAfterSuccessfulProbeLeavesOldReadyProfileAvailable() {
        InferenceCatalogPort failingCatalog = (InferenceCatalogPort) Proxy.newProxyInstance(
                InferenceCatalogPort.class.getClassLoader(), new Class<?>[]{InferenceCatalogPort.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("saveProfile")) {
                        throw new IllegalStateException("synthetic database failure");
                    }
                    try {
                        return method.invoke(catalog, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        var useCase = new InferenceManagementUseCase(failingCatalog, new FakeAssets(),
                new FakeRuntime(null), InferenceSecretPort.PASSTHROUGH,
                InferenceApiServerControlPort.NOOP);

        assertThrows(IllegalStateException.class,
                () -> useCase.saveAndVerifyProfile(draft(profile), () -> false));
        assertEquals(profile, catalog.profile(profile.id()).orElseThrow());
    }

    @Test
    void assetPreparationUsesSafeCallbacksAndPersistsResults() throws Exception {
        RecordingAssets prepared = new RecordingAssets(asset);
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null), prepared,
                InferenceSecretPort.PASSTHROUGH, InferenceApiServerControlPort.NOOP);
        var request = new InferenceAssetPreparationPort.HuggingFaceRequest("owner/model", null, null);

        assertEquals(asset, useCase.importLocal(Path.of("/tmp/source"), null, null));
        assertEquals(asset, useCase.downloadHuggingFace(request, ignored -> { }, () -> false));
        assertEquals("fixed-commit", useCase.previewHuggingFace(request, null).commit());
        assertFalse(prepared.nullSafeCancellationObserved);
        assertEquals(1, useCase.snapshot(" workspace ").assets().size());
        assertThrows(IllegalArgumentException.class, () -> useCase.snapshot(null));
        assertThrows(IllegalArgumentException.class, () -> useCase.snapshot("  "));
    }

    @Test
    void verificationUsesActualCapabilitiesAndAllFailuresPreserveOldProfile() throws Exception {
        InferenceManagementUseCase successful = useCase(new FakeRuntime(null));
        InferenceModelProfile verified = successful.saveAndVerifyProfile(draft(profile), null);
        assertEquals(2048, verified.contextLength());

        assertVerificationRestores(new Exception(new InterruptedException("stop")));
        assertVerificationRestores(new CancellationException("cancelled"));

        catalog.saveProfile(profile);
        Exception noMessage = new Exception((String) null);
        assertThrows(Exception.class, () -> useCase(new FakeRuntime(noMessage))
                .saveAndVerifyProfile(draft(profile), () -> false));
        assertEquals(profile, catalog.profile(profile.id()).orElseThrow());

        catalog.saveProfile(profile);
        Thread.currentThread().interrupt();
        try {
            assertThrows(Exception.class, () -> useCase(new FakeRuntime(new Exception("stop")))
                    .saveAndVerifyProfile(draft(profile), () -> false));
            assertEquals(profile, catalog.profile(profile.id()).orElseThrow());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void profileAndAssetDeletionEnforceReferencesBeforeRemovingStorage() throws Exception {
        FakeRuntime runtime = new FakeRuntime(null);
        RecordingAssets prepared = new RecordingAssets(asset);
        InferenceManagementUseCase useCase = useCase(runtime, prepared,
                InferenceSecretPort.PASSTHROUGH, InferenceApiServerControlPort.NOOP);

        catalog.bind("workspace", InferenceCatalogPort.ModelTier.NORMAL, profile.id());
        assertThrows(IllegalStateException.class, () -> useCase.deleteProfile(profile.id()));
        catalog.clearBinding("workspace", InferenceCatalogPort.ModelTier.NORMAL);
        useCase.deleteProfile(profile.id());
        assertEquals(profile.id(), runtime.stopped);
        assertTrue(catalog.profile(profile.id()).isEmpty());

        assertThrows(IllegalArgumentException.class, () -> useCase.deleteAsset(UUID.randomUUID()));
        useCase.deleteAsset(asset.id());
        assertEquals(asset.id(), prepared.deleted.id());
        assertEquals(asset.contentSha256(), prepared.deleted.contentSha256());
        assertTrue(catalog.asset(asset.id()).isEmpty());
    }

    @Test
    void profileDeletionStopsWhenTheRuntimeCannotUnloadTheModel() {
        FakeRuntime runtime = new FakeRuntime(null);
        runtime.stopFailure = new IllegalStateException("model still has active requests");
        InferenceManagementUseCase useCase = useCase(runtime);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> useCase.deleteProfile(profile.id()));

        assertEquals("model still has active requests", failure.getMessage());
        assertTrue(catalog.profile(profile.id()).isPresent());
    }

    @Test
    void referencedAssetCannotBeDeleted() {
        RecordingAssets prepared = new RecordingAssets(asset);
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null), prepared,
                InferenceSecretPort.PASSTHROUGH, InferenceApiServerControlPort.NOOP);

        assertThrows(IllegalStateException.class, () -> useCase.deleteAsset(asset.id()));
        assertNotNull(catalog.asset(asset.id()).orElse(null));
        assertEquals(null, prepared.deleted);
    }

    @Test
    void bindingsAcceptMatchingKindsAndRejectBothMismatchDirections() {
        InferenceModelProfile embedding = profileWith(InferenceModelProfile.Kind.EMBEDDING,
                asset.id(), installation.manifest().runtimeId(), InferenceModelProfile.State.READY);
        catalog.saveProfile(embedding);
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null));

        useCase.bind(" workspace ", InferenceCatalogPort.ModelTier.NORMAL, profile.id());
        useCase.bind("workspace", InferenceCatalogPort.ModelTier.EMBEDDING, embedding.id());
        assertEquals(profile.id(), catalog.bindings("workspace").get(InferenceCatalogPort.ModelTier.NORMAL));
        assertThrows(IllegalArgumentException.class, () -> useCase.bind("workspace",
                InferenceCatalogPort.ModelTier.EMBEDDING, profile.id()));
        assertThrows(IllegalArgumentException.class, () -> useCase.bind("workspace",
                InferenceCatalogPort.ModelTier.HIGH, embedding.id()));

        useCase.saveBindings("empty", null);
        assertTrue(catalog.bindings("empty").isEmpty());
        useCase.saveBindings("all", Map.of(InferenceCatalogPort.ModelTier.LIGHT, profile.id(),
                InferenceCatalogPort.ModelTier.EMBEDDING, embedding.id()));
        assertEquals(2, catalog.bindings("all").size());
        assertThrows(IllegalArgumentException.class, () -> useCase.saveBindings("bad",
                Map.of(InferenceCatalogPort.ModelTier.EMBEDDING, profile.id())));
        assertThrows(IllegalArgumentException.class, () -> useCase.saveBindings("bad",
                Map.of(InferenceCatalogPort.ModelTier.NORMAL, embedding.id())));
        assertThrows(IllegalArgumentException.class, () -> useCase.clearBinding(null,
                InferenceCatalogPort.ModelTier.NORMAL));
    }

    @Test
    void publishingPreservesCreationTimeAndRequiresReadyProfiles() {
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null));
        useCase.publish("local-model", profile.id());
        Instant created = catalog.publishedModel("local-model").orElseThrow().createdAt();
        useCase.publish("local-model", profile.id());
        assertEquals(created, catalog.publishedModel("local-model").orElseThrow().createdAt());
        useCase.unpublish("local-model");
        assertTrue(catalog.publishedModel("local-model").isEmpty());

        InferenceModelProfile draft = profileWith(InferenceModelProfile.Kind.GENERATION,
                asset.id(), installation.manifest().runtimeId(), InferenceModelProfile.State.DRAFT);
        catalog.saveProfile(draft);
        assertThrows(IllegalStateException.class, () -> useCase.publish("draft", draft.id()));
        assertThrows(IllegalArgumentException.class, () -> useCase.startProfile(UUID.randomUUID()));
    }

    @Test
    void publishedCatalogSyncFailureRestoresPreviousAliasForPublishAndUnpublish() {
        InferenceModelProfile replacement = profileWith(InferenceModelProfile.Kind.GENERATION,
                asset.id(), installation.manifest().runtimeId(), InferenceModelProfile.State.READY);
        catalog.saveProfile(replacement);
        catalog.publish(new InferenceCatalogPort.PublishedModel(
                "local-model", profile.id(), true, Instant.now(), Instant.now()));
        FakeApiServer server = new FakeApiServer();
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null), new FakeAssets(),
                InferenceSecretPort.PASSTHROUGH, server);

        server.failSync(new IllegalStateException("sync failed"));
        assertThrows(IllegalStateException.class,
                () -> useCase.publish("local-model", replacement.id()));
        assertEquals(profile.id(), catalog.publishedModel("local-model").orElseThrow().profileId());
        assertEquals(2, server.syncCalls);

        server.failSync(new IllegalStateException("unpublish sync failed"));
        assertThrows(IllegalStateException.class, () -> useCase.unpublish("local-model"));
        assertEquals(profile.id(), catalog.publishedModel("local-model").orElseThrow().profileId());
        assertEquals(4, server.syncCalls);
    }

    @Test
    void gatewayRequiresAnActiveKeyAndAnExplicitDecisionForLanHttp() {
        FakeApiServer server = new FakeApiServer();
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null), new FakeAssets(),
                InferenceSecretPort.PASSTHROUGH, server);

        assertThrows(IllegalStateException.class, () -> useCase.saveGateway(
                gateway(true, "127.0.0.1", false, "", "")));
        saveKey(UUID.randomUUID(), false);
        useCase.saveGateway(gateway(false, "127.0.0.1", false, "", ""));
        useCase.saveGateway(gateway(true, "127.0.0.1", false, "", ""));
        assertThrows(IllegalArgumentException.class, () -> useCase.saveGateway(
                gateway(true, "192.168.1.8", false, "", "")));
        assertThrows(IllegalArgumentException.class, () -> useCase.saveGateway(
                gateway(true, "192.168.1.8", true, "", "")));
        useCase.saveGateway(gateway(true, "192.168.1.8", true, "/tmp/server.p12", "encrypted"));
        useCase.saveGateway(new InferenceCatalogPort.GatewayConfiguration(true,
                "192.168.1.8", 19090, false, true, "", "", 4096, 30, Instant.now()));
        assertTrue(catalog.gatewayConfiguration().allowInsecureLanWithoutTls());
        assertEquals(4, server.reconfigureCalls);
    }

    @Test
    void gatewayPasswordIsEncryptedPreservedClearedAndInputIsWiped() throws Exception {
        RecordingSecrets secrets = new RecordingSecrets();
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null), new FakeAssets(),
                secrets, InferenceApiServerControlPort.NOOP);

        useCase.saveGateway(gateway(false, "127.0.0.1", false, "", "ignored"), null);
        assertEquals("", catalog.gatewayConfiguration().encryptedKeyStorePassword());

        char[] password = "secret".toCharArray();
        useCase.saveGateway(gateway(false, "127.0.0.1", true, "/tmp/a.p12", ""), password);
        assertTrue(Arrays.equals(new char[password.length], password));
        assertEquals("enc:secret", catalog.gatewayConfiguration().encryptedKeyStorePassword());
        assertEquals("secret", secrets.lastPlainText);

        useCase.saveGateway(gateway(false, "127.0.0.1", true, "/tmp/a.p12", ""), new char[0]);
        assertEquals("enc:secret", catalog.gatewayConfiguration().encryptedKeyStorePassword());
        useCase.saveGateway(gateway(false, "127.0.0.1", true, "/tmp/b.p12", ""), new char[0]);
        assertEquals("", catalog.gatewayConfiguration().encryptedKeyStorePassword());
    }

    @Test
    void failedGatewayReloadRestoresConfigurationAndReportsRollbackFailure() {
        InferenceCatalogPort.GatewayConfiguration previous = catalog.gatewayConfiguration();
        FakeApiServer server = new FakeApiServer(
                new IllegalStateException("bind failed"), new IllegalStateException("rollback failed"));
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null), new FakeAssets(),
                InferenceSecretPort.PASSTHROUGH, server);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> useCase.saveGateway(gateway(false, "127.0.0.1", false, "", "")));
        assertEquals(2, server.reconfigureCalls);
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals(previous.bindAddress(), catalog.gatewayConfiguration().bindAddress());
        assertEquals(previous.port(), catalog.gatewayConfiguration().port());
    }

    @Test
    void invocationLoggingIsPersistedAndAppliedWithoutGatewayRestart() throws Exception {
        FakeApiServer server = new FakeApiServer();
        server.loggingSupported = true;
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null), new FakeAssets(),
                InferenceSecretPort.PASSTHROUGH, server);

        useCase.setInvocationLogging(true);

        assertTrue(catalog.gatewayConfiguration().invocationLoggingEnabled());
        assertEquals(List.of(true), server.loggingChanges);
        assertEquals(0, server.reconfigureCalls);

        server.failLogging(new IllegalStateException("hot update failed"));
        assertThrows(IllegalStateException.class, () -> useCase.setInvocationLogging(false));
        assertTrue(catalog.gatewayConfiguration().invocationLoggingEnabled());
        assertEquals(List.of(true, false, true), server.loggingChanges);
        assertEquals(0, server.reconfigureCalls);
    }

    @Test
    void apiKeysAreRandomHashedAndCanBeRevokedWhenSafetyAllows() {
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null));
        var first = useCase.createApiKey(null, null, null, 60, 10_000, 2);
        var second = useCase.createApiKey("second", Set.of(InferenceCatalogPort.ApiScope.CHAT_INVOKE),
                Set.of("local-model"), 60, 10_000, 2);
        var third = useCase.createApiKey("third", Set.of(InferenceCatalogPort.ApiScope.MODELS_READ),
                Set.of(), 60, 10_000, 2);

        assertTrue(first.secret().startsWith("jcl_"));
        assertEquals(first.secret().substring(0, 12), first.metadata().prefix());
        assertNotEquals(first.secret(), first.metadata().digest());
        assertNotEquals(first.secret(), second.secret());
        assertThrows(IllegalArgumentException.class, () -> useCase.revokeApiKey(UUID.randomUUID()));

        useCase.revokeApiKey(first.metadata().id());
        assertTrue(catalog.apiKeys().stream().filter(key -> key.id().equals(first.metadata().id()))
                .findFirst().orElseThrow().revoked());
        catalog.saveGatewayConfiguration(gateway(true, "127.0.0.1", false, "", ""));
        useCase.revokeApiKey(first.metadata().id());
        useCase.revokeApiKey(second.metadata().id());
        catalog.saveGatewayConfiguration(gateway(false, "127.0.0.1", false, "", ""));
        useCase.revokeApiKey(third.metadata().id());
        assertTrue(catalog.apiKeys().stream().allMatch(InferenceCatalogPort.ApiKeyRecord::revoked));
    }

    @Test
    void enabledGatewayReloadsKeyPoliciesAndRollsBackFailedChanges() {
        UUID existing = UUID.randomUUID();
        saveKey(existing, false);
        catalog.saveGatewayConfiguration(gateway(true, "127.0.0.1", false, "", ""));
        FakeApiServer server = new FakeApiServer();
        InferenceManagementUseCase useCase = useCase(new FakeRuntime(null), new FakeAssets(),
                InferenceSecretPort.PASSTHROUGH, server);

        var created = useCase.createApiKey("new", Set.of(InferenceCatalogPort.ApiScope.CHAT_INVOKE),
                Set.of("local-chat"), 60, 10_000, 1);
        assertEquals(1, server.reconfigureCalls);
        useCase.revokeApiKey(created.metadata().id());
        assertEquals(2, server.reconfigureCalls);
        assertTrue(catalog.apiKeys().stream().filter(key -> key.id().equals(created.metadata().id()))
                .findFirst().orElseThrow().revoked());

        FakeApiServer createFailure = new FakeApiServer(new IllegalStateException("reload failed"));
        InferenceManagementUseCase failingCreate = useCase(new FakeRuntime(null), new FakeAssets(),
                InferenceSecretPort.PASSTHROUGH, createFailure);
        assertThrows(IllegalStateException.class, () -> failingCreate.createApiKey("failed",
                Set.of(InferenceCatalogPort.ApiScope.MODELS_READ), Set.of(), 60, 10_000, 1));
        assertEquals(2, createFailure.reconfigureCalls);
        assertTrue(catalog.apiKeys().stream().filter(key -> "failed".equals(key.name()))
                .findFirst().orElseThrow().revoked());

        UUID revocable = UUID.randomUUID();
        saveKey(revocable, false);
        FakeApiServer revokeFailure = new FakeApiServer(new IllegalStateException("reload failed"));
        InferenceManagementUseCase failingRevoke = useCase(new FakeRuntime(null), new FakeAssets(),
                InferenceSecretPort.PASSTHROUGH, revokeFailure);
        assertThrows(IllegalStateException.class, () -> failingRevoke.revokeApiKey(revocable));
        assertEquals(2, revokeFailure.reconfigureCalls);
        assertFalse(catalog.apiKeys().stream().filter(key -> key.id().equals(revocable))
                .findFirst().orElseThrow().revoked());
    }

    private void assertVerificationRestores(Exception failure) {
        catalog.saveProfile(profile);
        assertThrows(Exception.class, () -> useCase(new FakeRuntime(failure))
                .saveAndVerifyProfile(draft(profile), () -> false));
        assertEquals(profile, catalog.profile(profile.id()).orElseThrow());
    }

    private InferenceManagementUseCase useCase(InferenceRuntimePort runtime) {
        return new InferenceManagementUseCase(catalog, new FakeAssets(), runtime,
                InferenceSecretPort.PASSTHROUGH, InferenceApiServerControlPort.NOOP);
    }

    private InferenceManagementUseCase useCase(
            InferenceRuntimePort runtime,
            InferenceAssetPreparationPort preparedAssets,
            InferenceSecretPort secrets,
            InferenceApiServerControlPort server) {
        return new InferenceManagementUseCase(catalog, preparedAssets, runtime, secrets, server);
    }

    private InferenceModelProfile profileWith(
            InferenceModelProfile.Kind kind, UUID assetId, String runtimeId,
            InferenceModelProfile.State state) {
        return new InferenceModelProfile(UUID.randomUUID(), "profile-" + UUID.randomUUID(), kind,
                assetId, runtimeId, Map.of(), Map.of(), 2048,
                kind == InferenceModelProfile.Kind.EMBEDDING ? 32 : 0,
                state, "", Instant.now(), Instant.now());
    }

    private InferenceModelAsset assetWith(InferenceModelAsset.State state) {
        return new InferenceModelAsset(UUID.randomUUID(), InferenceModelAsset.Source.LOCAL_DIRECTORY,
                "", "qwen2", "d".repeat(64), "/tmp/model-staging", "", "", List.of(), 0,
                state, "", Instant.now());
    }

    private static InferenceManagementApplicationService.ProfileDraft draft(InferenceModelProfile value) {
        return new InferenceManagementApplicationService.ProfileDraft(value.id(), value.name(), value.kind(),
                value.assetId(), value.runtimeId(), value.loadParameters(), value.defaultParameters(),
                value.contextLength());
    }

    private static InferenceCatalogPort.GatewayConfiguration gateway(
            boolean enabled, String address, boolean tls, String keyStorePath, String encrypted) {
        return new InferenceCatalogPort.GatewayConfiguration(enabled, address, 19090, tls,
                keyStorePath, encrypted, 4096, 30, Instant.now());
    }

    private void saveKey(UUID id, boolean revoked) {
        String suffix = id.toString().replace("-", "").substring(0, 8);
        catalog.saveApiKey(new InferenceCatalogPort.ApiKeyRecord(id, "key", "jcl_" + suffix,
                "salt-" + suffix, "digest-" + suffix,
                Set.of(InferenceCatalogPort.ApiScope.MODELS_READ), Set.of(),
                60, 10_000, 1, revoked, Instant.now(), null));
    }

    private static final class FakeRuntime implements InferenceRuntimePort {
        private final Exception probeFailure;
        private InferenceModelProfile validated;
        private UUID stopped;
        private RuntimeException stopFailure;
        private int startCalls;
        private boolean reportTransitions = true;
        private final Map<UUID, RuntimeProfileStatus> statuses = new java.util.HashMap<>();
        private FakeRuntime(Exception probeFailure) { this.probeFailure = probeFailure; }
        @Override public void validateProfile(InferenceModelProfile profile) { validated = profile; }
        @Override public void start(InferenceModelProfile profile) {
            startCalls++;
            if (reportTransitions) statuses.put(profile.id(), new RuntimeProfileStatus(
                    profile.id(), true, profile.contextLength(), profile.embeddingDimensions(),
                    "test", Set.of()));
        }
        @Override public void stop(UUID profileId) {
            stopped = profileId;
            if (stopFailure != null) throw stopFailure;
            if (reportTransitions) statuses.remove(profileId);
        }
        @Override public Optional<RuntimeProfileStatus> status(UUID profileId) {
            return Optional.ofNullable(statuses.get(profileId));
        }
        @Override public ProfileProbeResult probeDraft(
                ProfileProbeCommand command, BooleanSupplier cancelled) throws Exception {
            if (probeFailure != null) throw probeFailure;
            return new ProfileProbeResult(8192,
                    command.kind() == InferenceModelProfile.Kind.EMBEDDING ? 32 : 0,
                    "jvector", Set.of("terminal_usage"));
        }
        @Override public List<String> recentLogs(UUID profileId, int maxLines) { return List.of(); }
    }

    private static class FakeAssets
            implements InferenceAssetPreparationPort, InferenceModelMetadataPort {
        @Override public ModelMetadata inspectModel(Path modelDirectory, BooleanSupplier cancelled) {
            return new ModelMetadata("qwen2", List.of("Qwen2ForCausalLM"));
        }
        @Override public HuggingFacePreview previewHuggingFace(
                HuggingFaceRequest request, BooleanSupplier cancelled) {
            throw new UnsupportedOperationException();
        }
        @Override public InferenceModelAsset importLocalDirectory(
                Path source, Consumer<Progress> progress, BooleanSupplier cancelled) {
            throw new UnsupportedOperationException();
        }
        @Override public InferenceModelAsset downloadHuggingFace(
                HuggingFaceRequest request, Consumer<Progress> progress, BooleanSupplier cancelled) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteManagedAsset(InferenceModelAsset asset) { }
    }

    private static final class RecordingAssets extends FakeAssets {
        private final InferenceModelAsset prepared;
        private boolean nullSafeCancellationObserved;
        private InferenceModelAsset deleted;

        private RecordingAssets(InferenceModelAsset prepared) { this.prepared = prepared; }

        @Override public HuggingFacePreview previewHuggingFace(
                HuggingFaceRequest request, BooleanSupplier cancelled) {
            nullSafeCancellationObserved |= cancelled.getAsBoolean();
            return new HuggingFacePreview(request.repository(), "fixed-commit", 1,
                    "apache-2.0", false, 1, "qwen2");
        }

        @Override public InferenceModelAsset importLocalDirectory(
                Path source, Consumer<Progress> progress, BooleanSupplier cancelled) {
            progress.accept(new Progress("copy", "config.json", 1, 1));
            nullSafeCancellationObserved |= cancelled.getAsBoolean();
            return prepared;
        }

        @Override public InferenceModelAsset downloadHuggingFace(
                HuggingFaceRequest request, Consumer<Progress> progress,
                BooleanSupplier cancelled) {
            progress.accept(new Progress("download", "config.json", 1, 1));
            nullSafeCancellationObserved |= cancelled.getAsBoolean();
            return prepared;
        }

        @Override public void deleteManagedAsset(InferenceModelAsset value) { deleted = value; }
    }

    private static final class RecordingSecrets implements InferenceSecretPort {
        private String lastPlainText;
        @Override public String encrypt(String plainText) {
            lastPlainText = plainText;
            return "enc:" + plainText;
        }
        @Override public String decrypt(String encryptedText) { return encryptedText; }
    }

    private static final class FakeApiServer implements InferenceApiServerControlPort {
        private final List<Exception> failures;
        private int failureIndex;
        private int reconfigureCalls;
        private List<Exception> syncFailures = List.of();
        private int syncFailureIndex;
        private int syncCalls;
        private boolean loggingSupported;
        private List<Exception> loggingFailures = List.of();
        private int loggingFailureIndex;
        private final List<Boolean> loggingChanges = new ArrayList<>();

        private FakeApiServer(Exception... failures) {
            this.failures = new ArrayList<>(Arrays.asList(failures));
        }

        @Override public void reconfigure() throws Exception {
            reconfigureCalls++;
            if (failureIndex < failures.size()) throw failures.get(failureIndex++);
        }

        private void failSync(Exception... failures) {
            syncFailures = new ArrayList<>(Arrays.asList(failures));
            syncFailureIndex = 0;
        }

        @Override public void syncPublishedModels() throws Exception {
            syncCalls++;
            if (syncFailureIndex < syncFailures.size()) {
                throw syncFailures.get(syncFailureIndex++);
            }
        }

        private void failLogging(Exception... failures) {
            loggingFailures = new ArrayList<>(Arrays.asList(failures));
            loggingFailureIndex = 0;
        }

        @Override public boolean supportsInvocationLogging() { return loggingSupported; }
        @Override public void setInvocationLogging(boolean enabled) throws Exception {
            loggingChanges.add(enabled);
            if (loggingFailureIndex < loggingFailures.size()) {
                throw loggingFailures.get(loggingFailureIndex++);
            }
        }

        @Override public String endpoint() { return "http://127.0.0.1:19090/v1"; }
    }
}
