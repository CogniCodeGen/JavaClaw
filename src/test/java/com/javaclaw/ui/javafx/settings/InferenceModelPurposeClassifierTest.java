package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceCatalogPort;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceModelPurposeClassifierTest {

    @Test
    void classifiesGenerationEmbeddingAndDualPurposeModelsWithoutCopyingThem() {
        var runtime = runtime(Set.of(
                "model-type:generation:qwen3",
                "model-type:embedding:bert",
                "model-type:generation:hybrid",
                "model-type:embedding:hybrid"));
        var qwen = model("qwen3", "a");
        var bert = model("bert", "b");
        var hybrid = model("hybrid", "c");

        assertEquals(Set.of(InferenceModelProfile.Kind.GENERATION),
                InferenceModelPurposeClassifier.purposes(qwen, List.of(runtime), List.of()));
        assertEquals(Set.of(InferenceModelProfile.Kind.EMBEDDING),
                InferenceModelPurposeClassifier.purposes(bert, List.of(runtime), List.of()));
        assertEquals(Set.of(InferenceModelProfile.Kind.GENERATION,
                        InferenceModelProfile.Kind.EMBEDDING),
                InferenceModelPurposeClassifier.purposes(hybrid, List.of(runtime), List.of()));
    }

    @Test
    void savedRunSettingKeepsAnOlderModelVisibleWhenCurrentCapabilitiesNoLongerDeclareIt() {
        var legacy = model("legacy", "d");
        var setting = new InferenceModelProfile(UUID.randomUUID(), "legacy vectors",
                InferenceModelProfile.Kind.EMBEDDING, legacy.id(), "removed-runtime",
                Map.of(), Map.of(), 512, 384, InferenceModelProfile.State.READY,
                "", Instant.now(), Instant.now());

        assertEquals(Set.of(InferenceModelProfile.Kind.EMBEDDING),
                InferenceModelPurposeClassifier.purposes(legacy, List.of(), List.of(setting)));
        assertTrue(InferenceModelPurposeClassifier.purposes(
                model("unknown", "e"), List.of(), List.of()).isEmpty());
    }

    private static InferenceCatalogPort.RuntimeInstallation runtime(Set<String> capabilities) {
        var manifest = new InferenceRuntimeManifest("runtime", "deliverance", "0.0.12", "2",
                new InferenceRuntimeManifest.ProtocolVersion(1, 1), "test", "test", 25,
                capabilities, Map.of(), List.of(), true);
        return new InferenceCatalogPort.RuntimeInstallation(manifest, "/tmp/runtime",
                InferenceCatalogPort.RuntimeState.ACTIVE, true, Instant.now());
    }

    private static InferenceModelAsset model(String type, String hashPrefix) {
        return new InferenceModelAsset(UUID.randomUUID(), InferenceModelAsset.Source.LOCAL_DIRECTORY,
                type, type, hashPrefix.repeat(64), "/tmp/" + type, "", "", List.of(), 1,
                InferenceModelAsset.State.READY, "", Instant.now());
    }
}
