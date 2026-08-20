package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import com.javaclaw.inference.api.InferenceUsage;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcInferenceCatalogTest {

    private JdbcInferenceCatalog catalog;

    @BeforeEach
    void createCatalog() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:inference-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        catalog = new JdbcInferenceCatalog(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void persistsGlobalCatalogAndKeepsWorkspaceBindingsIsolated() {
        var runtime = runtime();
        var asset = asset();
        var profile = profile(runtime.manifest().runtimeId(), asset.id());
        catalog.saveRuntime(runtime);
        catalog.saveAsset(asset);
        catalog.saveProfile(profile);

        catalog.replaceBindings("workspace-a",
                Map.of(InferenceCatalogPort.ModelTier.HIGH, profile.id()));

        assertEquals(profile.id(), catalog.bindings("workspace-a")
                .get(InferenceCatalogPort.ModelTier.HIGH));
        assertTrue(catalog.bindings("workspace-b").isEmpty());
        assertThrows(IllegalStateException.class, () -> catalog.deleteAsset(asset.id()));
        assertThrows(IllegalStateException.class, () -> catalog.deleteRuntime(runtime.manifest().runtimeId()));
    }

    @Test
    void apiUsageMergeAccumulatesEveryAttemptAndFailure() {
        UUID keyId = UUID.randomUUID();
        catalog.saveApiKey(new InferenceCatalogPort.ApiKeyRecord(keyId, "test", "jcl_12345678",
                "c2FsdA", "ZGlnZXN0", Set.of(InferenceCatalogPort.ApiScope.CHAT_INVOKE), Set.of(),
                60, 100_000, 1, false, Instant.now(), null));

        catalog.recordApiUsage(keyId, 42, new InferenceUsage(10, 3), false);
        catalog.recordApiUsage(keyId, 42, new InferenceUsage(7, 0), true);

        assertEquals(new InferenceCatalogPort.ApiUsage(2, 17, 3, 1),
                catalog.apiUsage(keyId, 42).orElseThrow());
    }

    @Test
    void persistsAssetMetadataGatewaySafetyAndInvocationLogging() {
        InferenceModelAsset value = new InferenceModelAsset(UUID.randomUUID(),
                InferenceModelAsset.Source.LOCAL_DIRECTORY, "Qwen local", "qwen2", "d".repeat(64),
                "/tmp/qwen", "", "", List.of(), 0,
                InferenceModelAsset.State.READY, "", Instant.now());
        catalog.saveAsset(value);
        var gateway = new InferenceCatalogPort.GatewayConfiguration(true,
                "192.168.1.20", 18080, false, true, "", "",
                4096, 30, true, Instant.now());
        catalog.saveGatewayConfiguration(gateway);

        assertEquals("Qwen local", catalog.asset(value.id()).orElseThrow().displayName());
        assertEquals("qwen2", catalog.asset(value.id()).orElseThrow().modelType());
        assertTrue(catalog.gatewayConfiguration().allowInsecureLanWithoutTls());
        assertTrue(catalog.gatewayConfiguration().invocationLoggingEnabled());
    }

    @Test
    void schemaBackfillsDisplayNamesForEarlyInferenceAssets() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:inference-migration-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE inference_model_assets (
                  asset_id VARCHAR(128) PRIMARY KEY,
                  source_type VARCHAR(32) NOT NULL,
                  content_sha256 VARCHAR(64) NOT NULL UNIQUE,
                  asset_path CLOB NOT NULL,
                  hf_repository VARCHAR(512),
                  hf_commit VARCHAR(128),
                  files_json CLOB NOT NULL,
                  size_bytes BIGINT NOT NULL,
                  asset_state VARCHAR(32) NOT NULL,
                  failure CLOB,
                  created_at BIGINT NOT NULL)
                """);
        jdbc.update("INSERT INTO inference_model_assets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), "HUGGING_FACE", "e".repeat(64), "/tmp/hf",
                "owner/model-name", "commit", "[]", 0, "READY", null,
                Instant.now().toEpochMilli());
        jdbc.update("INSERT INTO inference_model_assets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), "LOCAL_DIRECTORY", "f".repeat(64), "/tmp/local",
                null, null, "[]", 0, "READY", null, Instant.now().toEpochMilli());

        new SchemaInitializer(dataSource).initialize();

        assertEquals(List.of("model-name", "ffffffffffff"), jdbc.queryForList(
                "SELECT display_name FROM inference_model_assets ORDER BY asset_path",
                String.class));
        assertEquals(List.of("unknown", "unknown"), jdbc.queryForList(
                "SELECT model_type FROM inference_model_assets ORDER BY asset_path", String.class));
    }

    @Test
    void remembersThePreviouslyActivatedRuntimeInsteadOfTheNewestInstalledRuntime() {
        var first = runtime("runtime-first", Instant.parse("2026-01-01T00:00:00Z"), true);
        var neverActivated = runtime("runtime-newest", Instant.parse("2026-03-01T00:00:00Z"), false);
        var second = runtime("runtime-second", Instant.parse("2026-02-01T00:00:00Z"), false);
        catalog.saveRuntime(first);
        catalog.saveRuntime(neverActivated);
        catalog.saveRuntime(second);

        catalog.setActiveRuntime("deliverance", second.manifest().runtimeId());
        assertEquals(first.manifest().runtimeId(),
                catalog.previousActiveRuntime("deliverance").orElseThrow());

        catalog.setActiveRuntime("deliverance", first.manifest().runtimeId());
        assertEquals(second.manifest().runtimeId(),
                catalog.previousActiveRuntime("deliverance").orElseThrow());

        catalog.setActiveRuntime("deliverance", first.manifest().runtimeId());
        assertEquals(second.manifest().runtimeId(),
                catalog.previousActiveRuntime("deliverance").orElseThrow(),
                "重复激活当前版本不得污染回滚历史");
    }

    @Test
    void deletesAnUnreferencedActiveRuntimeAndItsActivationHistory() {
        var runtime = runtime("runtime-active", Instant.now(), false);
        catalog.saveRuntime(runtime);
        catalog.setActiveRuntime("deliverance", runtime.manifest().runtimeId());

        catalog.deleteRuntime(runtime.manifest().runtimeId());

        assertTrue(catalog.runtime(runtime.manifest().runtimeId()).isEmpty());
        assertTrue(catalog.previousActiveRuntime("deliverance").isEmpty());
    }

    private static InferenceCatalogPort.RuntimeInstallation runtime() {
        return runtime("runtime-test", Instant.now(), true);
    }

    private static InferenceCatalogPort.RuntimeInstallation runtime(
            String runtimeId, Instant installedAt, boolean active) {
        var manifest = new InferenceRuntimeManifest(runtimeId, "deliverance", "0.0.12", "1",
                new InferenceRuntimeManifest.ProtocolVersion(1, 0), "test", "test", 25,
                Set.of("chat", "exact_usage", "cancellation"), Map.of("type", "object"),
                List.of(new InferenceRuntimeManifest.RuntimeFile("deliverance.jar",
                        "a".repeat(64), 1)), true);
        return new InferenceCatalogPort.RuntimeInstallation(manifest, "/tmp/" + runtimeId,
                active ? InferenceCatalogPort.RuntimeState.ACTIVE
                        : InferenceCatalogPort.RuntimeState.INSTALLED,
                active, installedAt);
    }

    private static InferenceModelAsset asset() {
        return new InferenceModelAsset(UUID.randomUUID(), InferenceModelAsset.Source.LOCAL_DIRECTORY,
                "b".repeat(64), "/tmp/asset-test", "", "",
                List.of(new InferenceModelAsset.AssetFile("config.json", 1, "c".repeat(64))),
                1, InferenceModelAsset.State.READY, "", Instant.now());
    }

    private static InferenceModelProfile profile(String runtimeId, UUID assetId) {
        return new InferenceModelProfile(UUID.randomUUID(), "test", InferenceModelProfile.Kind.GENERATION,
                assetId, runtimeId, Map.of(), Map.of(), 2048, 0,
                InferenceModelProfile.State.READY, "", Instant.now(), Instant.now());
    }
}
