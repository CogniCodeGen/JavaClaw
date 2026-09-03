package com.javaclaw.api;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderVerificationContractsTest {
    private static final ProviderRef PROVIDER = new ProviderRef("provider-main", 3, "test-model");
    private static final ProviderCapabilities CAPABILITIES =
            new ProviderCapabilities(Set.of(ProviderModelPurpose.CHAT), true, true, true, false, false, false, false);
    private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

    @Test
    void 成功结果必须携带Usage且不允许错误码() {
        ProviderVerificationResult result = new ProviderVerificationResult(
                PROVIDER,
                ProviderModelPurpose.CHAT,
                ProviderVerificationState.SUCCEEDED,
                12,
                Optional.of(new ProviderVerificationUsage(2, 1, 0, 0)),
                CAPABILITIES,
                Optional.empty(),
                NOW);

        assertEquals(ProviderVerificationState.SUCCEEDED, result.state());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderVerificationResult(
                        PROVIDER,
                        ProviderModelPurpose.CHAT,
                        ProviderVerificationState.SUCCEEDED,
                        12,
                        Optional.empty(),
                        CAPABILITIES,
                        Optional.empty(),
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderVerificationResult(
                        PROVIDER,
                        ProviderModelPurpose.CHAT,
                        ProviderVerificationState.FAILED,
                        12,
                        Optional.empty(),
                        CAPABILITIES,
                        Optional.empty(),
                        NOW));
    }

    @Test
    void 未知外部结果不伪造延迟或Usage() {
        ProviderVerificationResult unknown = new ProviderVerificationResult(
                PROVIDER,
                ProviderModelPurpose.CHAT,
                ProviderVerificationState.UNKNOWN_OUTCOME,
                0,
                Optional.empty(),
                CAPABILITIES,
                Optional.of("UNKNOWN_OUTCOME"),
                NOW);

        assertEquals(Optional.empty(), unknown.usage());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderVerificationResult(
                        PROVIDER,
                        ProviderModelPurpose.CHAT,
                        ProviderVerificationState.UNKNOWN_OUTCOME,
                        1,
                        Optional.empty(),
                        CAPABILITIES,
                        Optional.of("UNKNOWN_OUTCOME"),
                        NOW));
        assertThrows(IllegalArgumentException.class, () -> new ProviderVerificationUsage(1, 0, 0, 2));
    }

    @Test
    void 向量成功不伪造TokenUsage且用途必须进入能力() {
        ProviderCapabilities embedding = new ProviderCapabilities(
                Set.of(ProviderModelPurpose.EMBEDDING), false, false, false, false, false, false, false);
        ProviderVerificationResult result = new ProviderVerificationResult(
                PROVIDER,
                ProviderModelPurpose.EMBEDDING,
                ProviderVerificationState.SUCCEEDED,
                8,
                Optional.empty(),
                embedding,
                Optional.empty(),
                NOW);

        assertEquals(ProviderModelPurpose.EMBEDDING, result.purpose());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderVerificationResult(
                        PROVIDER,
                        ProviderModelPurpose.EMBEDDING,
                        ProviderVerificationState.SUCCEEDED,
                        8,
                        Optional.of(new ProviderVerificationUsage(1, 0, 0, 0)),
                        embedding,
                        Optional.empty(),
                        NOW));
    }
}
