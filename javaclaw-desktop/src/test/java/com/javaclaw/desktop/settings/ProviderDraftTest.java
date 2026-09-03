package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderReasoningSummary;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderDraftTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void 四类Adapter选项经过界面草稿后保持具体类型和值() {
        List<ProviderAdapterOptions> options = List.of(
                new ProviderAdapterOptions.OpenAiCompatible(Optional.of("org"), Optional.of("project")),
                new ProviderAdapterOptions.Anthropic(),
                new ProviderAdapterOptions.GoogleGenAi(Optional.of("v1beta")),
                new ProviderAdapterOptions.OpenAiResponses(
                        Optional.of("org"), Optional.empty(), ProviderReasoningSummary.CONCISE));

        for (ProviderAdapterOptions option : options) {
            ProviderEndpoint endpoint = endpoint(option);

            ProviderEndpointSpec restored = ProviderDraft.from(endpoint).toSpec();

            assertEquals(option.getClass(), restored.options().getClass());
            assertEquals(option, restored.options());
        }
    }

    private static ProviderEndpoint endpoint(ProviderAdapterOptions options) {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "Provider",
                options.adapter(),
                Optional.empty(),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec(
                        "model", "Model", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                Optional.empty(),
                Duration.ofSeconds(30),
                1,
                options);
        return new ProviderEndpoint("provider", 1, ProviderLifecycle.DISABLED, spec, NOW, NOW);
    }
}
