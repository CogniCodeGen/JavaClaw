package com.javaclaw.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderRole;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderAdapterFactoryValidationTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void 缺少凭据时仍拒绝无效Responses选项() {
        ProviderEndpoint endpoint = endpoint(
                ProviderAdapter.OPENAI_RESPONSES, Set.of(ProviderRole.CHAT), Map.of("reasoningSummary", "invented"));
        ProviderModelAdapterFactory factory = new ProviderModelAdapterFactory(reference -> Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(endpoint, new ProviderRef(endpoint.id(), endpoint.revision(), "test-model")));
    }

    @Test
    void 缺少凭据时仍拒绝无效Embedding维度与默认选择() {
        ProviderEmbeddingAdapterFactory factory = new ProviderEmbeddingAdapterFactory(reference -> Optional.empty());
        ProviderEndpoint invalidDimensions = endpoint(
                ProviderAdapter.OPENAI_COMPATIBLE,
                Set.of(ProviderRole.EMBEDDING),
                Map.of("embeddingDimensions", "zero"));
        ProviderEndpoint invalidSelection = endpoint(
                ProviderAdapter.GOOGLE_GENAI, Set.of(ProviderRole.EMBEDDING), Map.of("defaultEmbedding", "sometimes"));

        assertThrows(IllegalArgumentException.class, () -> factory.create(invalidDimensions, "test-model"));
        assertThrows(IllegalArgumentException.class, () -> factory.create(invalidSelection, "test-model"));
    }

    private static ProviderEndpoint endpoint(
            ProviderAdapter adapter, Set<ProviderRole> roles, Map<String, String> options) {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "Test",
                adapter,
                Optional.empty(),
                roles,
                List.of("test-model"),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                options);
        return new ProviderEndpoint("test-provider", 1, ProviderLifecycle.ACTIVE, spec, NOW, NOW);
    }
}
