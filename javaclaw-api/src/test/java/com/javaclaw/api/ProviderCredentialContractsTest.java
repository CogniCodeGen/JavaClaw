package com.javaclaw.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderCredentialContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");
    private static final CredentialRef REFERENCE = new CredentialRef("provider", "credential-1");

    @Test
    void 绑定结果要求Provider与CredentialMetadata引用一致() {
        CredentialMetadata metadata = new CredentialMetadata(REFERENCE, 1, NOW);
        ProviderEndpoint provider = provider(2, Optional.of(REFERENCE));

        assertEquals(provider, new ProviderCredentialBinding(provider, metadata).provider());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderCredentialBinding(provider(2, Optional.empty()), metadata));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderCredentialBinding(
                        provider, new CredentialMetadata(new CredentialRef("provider", "other"), 1, NOW)));
    }

    @Test
    void 清除结果要求Provider不再保留CredentialRef() {
        CredentialClearReceipt receipt = new CredentialClearReceipt(REFERENCE, 1, NOW);

        assertEquals(receipt, new ProviderCredentialClearResult(provider(3, Optional.empty()), receipt).receipt());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderCredentialClearResult(provider(3, Optional.of(REFERENCE)), receipt));
    }

    @Test
    void ProviderSpec只接受ProviderNamespace的CredentialRef() {
        assertThrows(IllegalArgumentException.class, () -> spec(Optional.of(new CredentialRef("mcp", "credential-1"))));
    }

    private static ProviderEndpoint provider(long revision, Optional<CredentialRef> credential) {
        return new ProviderEndpoint("provider-main", revision, ProviderLifecycle.ACTIVE, spec(credential), NOW, NOW);
    }

    private static ProviderEndpointSpec spec(Optional<CredentialRef> credential) {
        return new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                Set.of(ProviderRole.CHAT),
                List.of("test-model"),
                credential,
                Duration.ofSeconds(30),
                0,
                Map.of());
    }
}
