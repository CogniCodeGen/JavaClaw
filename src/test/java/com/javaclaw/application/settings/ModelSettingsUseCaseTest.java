package com.javaclaw.application.settings;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ProbeResult;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Snapshot;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Tier;
import com.javaclaw.application.settings.ModelSettingsApplicationService.TierSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSettingsUseCaseTest {

    @Test
    void validatesTheCompleteModelBeforePersisting() {
        FakeSettingsPort settings = new FakeSettingsPort(snapshot());
        FakeProbePort probes = new FakeProbePort();
        ModelSettingsUseCase useCase = new ModelSettingsUseCase(settings, probes);

        ModelSettings changed = validModel("https://models.example/v1", "new-model");
        var saved = useCase.saveModel(changed);

        assertEquals(1, settings.modelSaves);
        assertEquals(changed, saved.snapshot().model());
        assertTrue(saved.runtimeRefreshRequired());

        ModelSettings invalid = new ModelSettings(
                changed.provider(), changed.baseUrl(), "", changed.apiKey(),
                changed.thinkingEnabled(), changed.thinkingBudget(), "HTTP_3",
                0, changed.readTimeoutSeconds(), changed.writeTimeoutSeconds(),
                changed.orchestratorMaxIterations(), changed.webAgentMaxIterations(),
                changed.emailAgentMaxIterations(), changed.maxRepeatedToolCalls(),
                changed.loopSimilarityThreshold(), changed.evaluatorPassThreshold(),
                changed.evaluatorMaxRetries());
        assertThrows(ValidationException.class, () -> useCase.saveModel(invalid));
        assertEquals(1, settings.modelSaves, "校验失败不得产生部分写入");
        assertEquals(changed, settings.snapshot.model());
    }

    @Test
    void disabledTiersAreNormalizedAndClearUsesTheSameSaveBoundary() {
        FakeSettingsPort settings = new FakeSettingsPort(snapshot());
        ModelSettingsUseCase useCase = new ModelSettingsUseCase(settings, new FakeProbePort());
        Tier disabledWithStaleValues = new Tier(
                false, "OpenAI", "not-a-url", "stale", "secret", true);
        Tier enabled = new Tier(
                true, "Ollama", "http://localhost:11434", "qwen3:8b", "", false);

        useCase.saveTiers(new TierSettings(disabledWithStaleValues, enabled));

        assertEquals(new Tier(false, "", "", "", "", false),
                settings.snapshot.tiers().normal());
        assertEquals(enabled, settings.snapshot.tiers().light());
        assertEquals(1, settings.tierSaves);

        useCase.clearTiers();
        assertFalse(settings.snapshot.tiers().normal().enabled());
        assertFalse(settings.snapshot.tiers().light().enabled());
        assertEquals(2, settings.tierSaves);
    }

    @Test
    void probesValidatedFormAndSuppliesPersistedEmbeddingSnapshot() throws Exception {
        Snapshot initial = snapshot();
        FakeSettingsPort settings = new FakeSettingsPort(initial);
        FakeProbePort probes = new FakeProbePort();
        ModelSettingsUseCase useCase = new ModelSettingsUseCase(settings, probes);
        EmbeddingSettings changed = new EmbeddingSettings(
                true, "OpenAI", "https://embeddings.example/v1", "key",
                "text-embedding-3-small", 1024, 8, 0.45);

        assertTrue(useCase.probeModel(initial.model()).succeeded());
        assertTrue(useCase.probeEmbedding(changed).succeeded());

        assertEquals(initial.model(), probes.probedModel);
        assertEquals(changed, probes.probedEmbedding);
        assertEquals(initial.embedding(), probes.persistedEmbedding);
        assertThrows(ValidationException.class, () -> useCase.probeEmbedding(
                new EmbeddingSettings(true, "OpenAI", "file:///tmp/model", "", "model",
                        0, 0, Double.NaN)));
        assertEquals(1, probes.embeddingCalls);
    }

    private static Snapshot snapshot() {
        Tier disabled = new Tier(false, "", "", "", "", false);
        return new Snapshot(
                validModel("https://api.example/v1", "chat-model"),
                new TierSettings(disabled, disabled),
                new EmbeddingSettings(true, "OpenAI", "https://api.example/v1", "key",
                        "embedding-model", 1024, 5, 0.35),
                "fake-config.json");
    }

    private static ModelSettings validModel(String baseUrl, String model) {
        return new ModelSettings("OpenAI", baseUrl, model, "key", true, 4096,
                "HTTP_2", 10, 120, 30, 30, 15, 15, 5, 0.92, 4.0, 2);
    }

    private static final class FakeSettingsPort implements ModelSettingsPort {
        private Snapshot snapshot;
        private int modelSaves;
        private int tierSaves;

        private FakeSettingsPort(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public Snapshot load() { return snapshot; }

        @Override public void saveModel(ModelSettings value) {
            modelSaves++;
            snapshot = new Snapshot(value, snapshot.tiers(), snapshot.embedding(),
                    snapshot.storageDescription());
        }

        @Override public void resetModel() {
            saveModel(validModel("https://default.example/v1", "default-model"));
        }

        @Override public void saveTiers(TierSettings value) {
            tierSaves++;
            snapshot = new Snapshot(snapshot.model(), value, snapshot.embedding(),
                    snapshot.storageDescription());
        }

        @Override public void saveEmbedding(EmbeddingSettings value) {
            snapshot = new Snapshot(snapshot.model(), snapshot.tiers(), value,
                    snapshot.storageDescription());
        }
    }

    private static final class FakeProbePort implements ModelSettingsProbePort {
        private ModelSettings probedModel;
        private EmbeddingSettings probedEmbedding;
        private EmbeddingSettings persistedEmbedding;
        private int embeddingCalls;

        @Override public ProbeResult probeModel(ModelSettings settings) {
            probedModel = settings;
            return new ProbeResult(true, "model ok");
        }

        @Override public ProbeResult probeEmbedding(
                EmbeddingSettings form, EmbeddingSettings persisted) {
            embeddingCalls++;
            probedEmbedding = form;
            persistedEmbedding = persisted;
            return new ProbeResult(true, "embedding ok");
        }
    }
}
