package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceQuickSetupContractsTest {

    @Test
    void lightweightCatalogProjectionsNormalizeLegacyNullsAndRejectInvalidRows() {
        UUID id = UUID.randomUUID();
        var asset = new InferenceCatalogPort.ModelAssetSummary(
                id, "  ", 0, InferenceModelAsset.State.READY, null);
        assertEquals("本地模型", asset.displayName());
        assertEquals("", asset.failure());
        assertThrows(IllegalArgumentException.class, () -> new InferenceCatalogPort.ModelAssetSummary(
                null, "model", 0, InferenceModelAsset.State.READY, ""));
        assertThrows(IllegalArgumentException.class, () -> new InferenceCatalogPort.ModelAssetSummary(
                id, "model", -1, InferenceModelAsset.State.READY, ""));
        assertThrows(IllegalArgumentException.class, () -> new InferenceCatalogPort.ModelAssetSummary(
                id, "model", 0, null, ""));

        var profile = new InferenceCatalogPort.ModelProfileSummary(id, UUID.randomUUID(),
                InferenceModelProfile.Kind.GENERATION, InferenceModelProfile.State.READY,
                null, null);
        assertEquals("", profile.failure());
        assertEquals(Instant.EPOCH, profile.updatedAt());
        assertThrows(IllegalArgumentException.class, () -> new InferenceCatalogPort.ModelProfileSummary(
                null, id, InferenceModelProfile.Kind.GENERATION,
                InferenceModelProfile.State.READY, "", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new InferenceCatalogPort.ModelProfileSummary(
                id, null, InferenceModelProfile.Kind.GENERATION,
                InferenceModelProfile.State.READY, "", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new InferenceCatalogPort.ModelProfileSummary(
                id, UUID.randomUUID(), null, InferenceModelProfile.State.READY, "", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new InferenceCatalogPort.ModelProfileSummary(
                id, UUID.randomUUID(), InferenceModelProfile.Kind.GENERATION,
                null, "", Instant.now()));
    }

    @Test
    void quickViewRecordsHaveSafeDefaultsAndValidateUserControlledValues() {
        var snapshot = new LocalInferenceQuickSetupApplicationService.QuickSnapshot(
                null, null, " ", null, false, false, false);
        assertTrue(snapshot.models().isEmpty());
        assertEquals(LocalInferenceQuickSetupApplicationService.DEFAULT_ALIAS, snapshot.alias());
        assertEquals("", snapshot.endpoint());
        assertTrue(snapshot.supportedModelTypes().isEmpty());

        var supported = LocalInferenceQuickSetupApplicationService.SupportedModelType
                .fromId("qwen2");
        assertEquals("Qwen 2 / 2.5", supported.displayName());
        assertThrows(IllegalArgumentException.class,
                () -> new LocalInferenceQuickSetupApplicationService.SupportedModelType("Qwen 2", "Qwen"));

        UUID assetId = UUID.randomUUID();
        var model = new LocalInferenceQuickSetupApplicationService.LocalModel(assetId, null, 0,
                InferenceModelAsset.State.READY, null,
                LocalInferenceQuickSetupApplicationService.Status.IMPORTED, null);
        assertEquals("本地模型", model.displayName());
        assertEquals("", model.failure());
        assertThrows(IllegalArgumentException.class,
                () -> new LocalInferenceQuickSetupApplicationService.LocalModel(null, "m", 0,
                        InferenceModelAsset.State.READY, null,
                        LocalInferenceQuickSetupApplicationService.Status.IMPORTED, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalInferenceQuickSetupApplicationService.LocalModel(assetId, "m", -1,
                        InferenceModelAsset.State.READY, null,
                        LocalInferenceQuickSetupApplicationService.Status.IMPORTED, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalInferenceQuickSetupApplicationService.LocalModel(assetId, "m", 0,
                        null, null, LocalInferenceQuickSetupApplicationService.Status.IMPORTED, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalInferenceQuickSetupApplicationService.LocalModel(assetId, "m", 0,
                        InferenceModelAsset.State.READY, null, null, ""));

        assertEquals(1, new LocalInferenceQuickSetupApplicationService.Progress(" done ", 2).fraction());
        assertEquals(0, new LocalInferenceQuickSetupApplicationService.Progress(null, -2).fraction());
        assertEquals(-1, new LocalInferenceQuickSetupApplicationService.Progress("", Double.NaN).fraction());

        var result = new LocalInferenceQuickSetupApplicationService.LoadResult(
                UUID.randomUUID(), null, null, 0, null, null);
        assertEquals(LocalInferenceQuickSetupApplicationService.DEFAULT_ALIAS, result.alias());
        assertEquals("", result.endpoint());
        assertEquals("", result.selectedBackend());
        assertFalse(result.apiKeyCreated());
        assertTrue(new LocalInferenceQuickSetupApplicationService.LoadResult(UUID.randomUUID(),
                "alias", "http://localhost/v1", 1, "auto", "secret").apiKeyCreated());
        assertThrows(IllegalArgumentException.class,
                () -> new LocalInferenceQuickSetupApplicationService.LoadResult(
                        null, "alias", "", 0, "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalInferenceQuickSetupApplicationService.LoadCommand(null, false));
    }

    @Test
    void runtimeManifestExposesOnlyKindScopedSignedModelTypeCapabilities() {
        var manifest = new InferenceRuntimeManifest("runtime", "deliverance", "0.0.12", "1",
                new InferenceRuntimeManifest.ProtocolVersion(1, 1), "test", "test", 25,
                Set.of("chat", "model-type:generation:qwen2",
                        "model-type:generation:llama", "model-type:embedding:bert",
                        "model-type:generation:invalid value"), Map.of(), List.of(), true);

        assertEquals(Set.of("qwen2", "llama"),
                manifest.supportedModelTypes(InferenceModelProfile.Kind.GENERATION));
        assertEquals(Set.of("bert"),
                manifest.supportedModelTypes(InferenceModelProfile.Kind.EMBEDDING));
        assertEquals("model-type:generation:qwen3_moe",
                InferenceRuntimeManifest.modelTypeCapability(
                        InferenceModelProfile.Kind.GENERATION, "QWEN3_MOE"));
    }

    @Test
    void apiKeysAndGatewayEnforceLanAndRateLimitBoundaries() {
        Instant now = Instant.now();
        var gateway = new InferenceCatalogPort.GatewayConfiguration(false, null, 18080,
                false, null, null, 1024, 1, null);
        assertEquals("127.0.0.1", gateway.bindAddress());
        assertTrue(gateway.loopbackOnly());
        assertTrue(new InferenceCatalogPort.GatewayConfiguration(false, "LOCALHOST", 1,
                false, false, "", "", 1024, 1, now).loopbackOnly());
        assertFalse(new InferenceCatalogPort.GatewayConfiguration(false, "192.168.1.2", 65535,
                false, true, "", "", 64L * 1024 * 1024, 3600, now).loopbackOnly());
        for (String wildcard : List.of("0.0.0.0", "::")) {
            assertThrows(IllegalArgumentException.class, () -> new InferenceCatalogPort.GatewayConfiguration(
                    false, wildcard, 1, false, false, "", "", 1024, 1, now));
        }
        assertThrows(IllegalArgumentException.class, () -> gateway(0, 1024, 1));
        assertThrows(IllegalArgumentException.class, () -> gateway(65536, 1024, 1));
        assertThrows(IllegalArgumentException.class, () -> gateway(1, 1023, 1));
        assertThrows(IllegalArgumentException.class, () -> gateway(1, 64L * 1024 * 1024 + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> gateway(1, 1024, 0));
        assertThrows(IllegalArgumentException.class, () -> gateway(1, 1024, 3601));

        UUID keyId = UUID.randomUUID();
        var key = new InferenceCatalogPort.ApiKeyRecord(keyId, " ", "prefix", "salt", "digest",
                null, null, 1, 1, 1, false, null, null);
        assertEquals("未命名密钥", key.name());
        assertTrue(key.scopes().isEmpty());
        assertTrue(key.modelAliases().isEmpty());
        assertFalse(key.permits(InferenceCatalogPort.ApiScope.MODELS_READ, null));
        var permitted = new InferenceCatalogPort.ApiKeyRecord(keyId, "key", "prefix", "salt", "digest",
                Set.of(InferenceCatalogPort.ApiScope.CHAT_INVOKE), Set.of(), 100_000, 1, 1024,
                false, now, null);
        assertTrue(permitted.permits(InferenceCatalogPort.ApiScope.CHAT_INVOKE, "local-chat"));
        assertFalse(new InferenceCatalogPort.ApiKeyRecord(keyId, "key", "prefix", "salt", "digest",
                permitted.scopes(), Set.of("other"), 1, 1, 1, false, now, null)
                .permits(InferenceCatalogPort.ApiScope.CHAT_INVOKE, "local-chat"));
        assertFalse(new InferenceCatalogPort.ApiKeyRecord(keyId, "key", "prefix", "salt", "digest",
                permitted.scopes(), Set.of(), 1, 1, 1, true, now, null)
                .permits(InferenceCatalogPort.ApiScope.CHAT_INVOKE, null));
        assertThrows(IllegalArgumentException.class, () -> apiKey(null, "prefix", 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> apiKey(keyId, "", 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> apiKey(keyId, "prefix", 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> apiKey(keyId, "prefix", 100_001, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> apiKey(keyId, "prefix", 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> apiKey(keyId, "prefix", 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> apiKey(keyId, "prefix", 1, 1, 1025));
    }

    private static InferenceCatalogPort.GatewayConfiguration gateway(
            int port, long bytes, int timeout) {
        return new InferenceCatalogPort.GatewayConfiguration(false, "127.0.0.1", port,
                false, false, "", "", bytes, timeout, Instant.now());
    }

    private static InferenceCatalogPort.ApiKeyRecord apiKey(
            UUID id, String prefix, int rpm, long tpm, int concurrency) {
        return new InferenceCatalogPort.ApiKeyRecord(id, "key", prefix, "salt", "digest",
                Set.of(), Set.of(), rpm, tpm, concurrency, false, Instant.now(), null);
    }
}
