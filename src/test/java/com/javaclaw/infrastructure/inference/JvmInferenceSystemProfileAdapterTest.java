package com.javaclaw.infrastructure.inference;

import com.javaclaw.application.inference.InferenceModelMetadataPort;
import com.javaclaw.inference.api.InferenceModelAsset;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmInferenceSystemProfileAdapterTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void fillsBalancedDefaultsFromCpuMemoryAndModelMetadata() {
        var adapter = new JvmInferenceSystemProfileAdapter(() ->
                new JvmInferenceSystemProfileAdapter.Hardware(16, 32 * GIB, 24 * GIB, true));

        var result = adapter.recommend(
                new InferenceModelMetadataPort.ModelMetadata("qwen3", List.of("Qwen3"), 32768),
                new InferenceModelAsset.ArtifactMetadata(
                        "SAFETENSORS", "Q4", 14 * GIB, 7 * GIB, Instant.now()));

        assertEquals(6, result.capacity().recommendedThreads());
        assertEquals(8192, result.contextLength());
        assertEquals("auto", result.loadParameters().get("tensorBackend"));
        assertEquals("F32", result.loadParameters().get("workingMemoryType"));
        assertEquals("Q4", result.loadParameters().get("workingQuantType"));
        assertEquals(1, result.loadParameters().get("maxBatchSize"));
        assertEquals("AUTO", result.loadParameters().get("pooling"));
        assertEquals(1024, result.generationParameters().get("maxTokens"));
        assertEquals(32 * GIB * 3 / 5, result.capacity().recommendedReservedBytes());
        assertEquals(result.capacity().recommendedReservedBytes() * 35 / 100,
                result.capacity().recommendedHeapBytes());
        assertEquals(result.capacity().recommendedNativeBytes(),
                result.capacity().safeModelBudgetBytes());
        assertTrue(result.capacity().availableMemoryReliable());
        assertFalse(result.memory().exceedsPhysicalMemory());
        assertFalse(result.memory().exceedsSafeBudget());
    }

    @Test
    void capsReservationAndOnlyWarnsWhenModelExceedsMemory() {
        var adapter = new JvmInferenceSystemProfileAdapter(() ->
                new JvmInferenceSystemProfileAdapter.Hardware(2, 8 * GIB, 3 * GIB, true));

        var result = adapter.recommend(
                new InferenceModelMetadataPort.ModelMetadata("qwen3", List.of(), 2048),
                new InferenceModelAsset.ArtifactMetadata(
                        "SAFETENSORS", "I8", 20 * GIB, 10 * GIB, Instant.now()));

        assertEquals(1, result.capacity().recommendedThreads());
        assertEquals(8 * GIB * 3 / 5, result.capacity().recommendedReservedBytes());
        assertTrue(result.memory().exceedsPhysicalMemory());
        assertTrue(result.memory().exceedsSafeBudget());
        assertTrue(result.memory().message().contains("无法完整驻留"));
        assertEquals(2048, result.contextLength());
        assertEquals(1024, result.generationParameters().get("maxTokens"));
    }

    @Test
    void usesAutomaticContextAndSafeFallbackWhenMetricsAreUnavailable() {
        var adapter = new JvmInferenceSystemProfileAdapter(() ->
                new JvmInferenceSystemProfileAdapter.Hardware(1, 0, 0, false));
        var result = adapter.recommend(
                new InferenceModelMetadataPort.ModelMetadata("bert", List.of(), 0),
                InferenceModelAsset.ArtifactMetadata.unknown(512));

        assertEquals(0, result.contextLength());
        assertEquals(1, result.capacity().recommendedThreads());
        assertFalse(result.capacity().physicalMemoryAvailable());
        assertFalse(result.memory().exceedsPhysicalMemory());
    }

    @Test
    void doesNotTreatAnUnreliableRawFreeValueAsTheSafeModelBudget() {
        var adapter = new JvmInferenceSystemProfileAdapter(() ->
                new JvmInferenceSystemProfileAdapter.Hardware(
                        10, 32 * GIB, 132L * 1024 * 1024, false));

        var result = adapter.recommend(
                new InferenceModelMetadataPort.ModelMetadata("qwen3", List.of(), 8192),
                new InferenceModelAsset.ArtifactMetadata(
                        "SAFETENSORS", "Q4", 14 * GIB, 7 * GIB, Instant.now()));

        assertFalse(result.capacity().availableMemoryReliable());
        assertEquals(result.capacity().recommendedNativeBytes(),
                result.capacity().safeModelBudgetBytes());
        assertFalse(result.memory().exceedsSafeBudget());
    }

    @Test
    void stillWarnsWhenReliableAvailableMemoryIsActuallyLow() {
        var adapter = new JvmInferenceSystemProfileAdapter(() ->
                new JvmInferenceSystemProfileAdapter.Hardware(
                        10, 32 * GIB, 132L * 1024 * 1024, true));

        var result = adapter.recommend(
                new InferenceModelMetadataPort.ModelMetadata("qwen3", List.of(), 8192),
                new InferenceModelAsset.ArtifactMetadata(
                        "SAFETENSORS", "Q4", 2 * GIB, GIB, Instant.now()));

        assertEquals(132L * 1024 * 1024 * 4 / 5,
                result.capacity().safeModelBudgetBytes());
        assertTrue(result.memory().exceedsSafeBudget());
        assertTrue(result.memory().message().contains("内存压力"));
    }
}
