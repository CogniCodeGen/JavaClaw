package com.javaclaw.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderAdapterFactoryValidationTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void 缺少凭据时仍先拒绝EmbeddingOnly的Chat路由() {
        ProviderEndpoint endpoint = endpoint(ProviderAdapter.OPENAI_COMPATIBLE, ProviderModelPurpose.EMBEDDING);
        ProviderModelAdapterFactory factory = new ProviderModelAdapterFactory(reference -> Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(endpoint, new ProviderRef(endpoint.id(), endpoint.revision(), "test-model")));
    }

    @Test
    void 缺少凭据时仍先拒绝ChatOnly的Embedding路由() {
        ProviderEmbeddingAdapterFactory factory = new ProviderEmbeddingAdapterFactory(reference -> Optional.empty());
        ProviderEndpoint endpoint = endpoint(ProviderAdapter.OPENAI_COMPATIBLE, ProviderModelPurpose.CHAT);
        ProviderRef reference = new ProviderRef(endpoint.id(), endpoint.revision(), "test-model");

        assertThrows(IllegalArgumentException.class, () -> factory.create(endpoint, reference));
    }

    private static ProviderEndpoint endpoint(ProviderAdapter adapter, ProviderModelPurpose purpose) {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "Test",
                adapter,
                Optional.empty(),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec("test-model", "Test model", Set.of(purpose), OptionalInt.empty())),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(adapter));
        return new ProviderEndpoint("test-provider", 1, ProviderLifecycle.ACTIVE, spec, NOW, NOW);
    }
}
