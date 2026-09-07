package com.javaclaw.protocol;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelDiscoveryOperation;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelDiscoveryRequest;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderReasoningSummary;
import com.javaclaw.api.ProviderRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigurationRpcContractsTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void 四类Adapter选项保持严格统一Wire形状() {
        List<ProviderAdapterOptions> options = List.of(
                new ProviderAdapterOptions.OpenAiCompatible(Optional.of("org"), Optional.of("project")),
                new ProviderAdapterOptions.Anthropic(),
                new ProviderAdapterOptions.GoogleGenAi(Optional.of("v1beta")),
                new ProviderAdapterOptions.OpenAiResponses(
                        Optional.of("org"), Optional.empty(), ProviderReasoningSummary.DETAILED));

        for (ProviderAdapterOptions option : options) {
            CanonicalPayload encoded = JSON.encode(option);
            assertEquals(option, JSON.decode(encoded, ProviderAdapterOptions.class));
            assertEquals(
                    Set.of("adapter", "organization", "project", "apiVersion", "reasoningSummary"),
                    JSON.fieldNames(encoded));
        }
        assertThrows(ProtocolException.class, () -> JSON.decode(JSON.parse("""
                                {
                                  "adapter":"ANTHROPIC",
                                  "organization":"cross-provider",
                                  "project":null,
                                  "apiVersion":null,
                                  "reasoningSummary":"AUTO"
                                }
                                """), ProviderAdapterOptions.class));
    }

    @Test
    void Provider逐模型选项和OptionalInt在共享Codec中无损往返() {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "Responses",
                ProviderAdapter.OPENAI_RESPONSES,
                Optional.of(URI.create("https://api.example.test/v1")),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec("gpt", "GPT", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                Optional.empty(),
                Duration.ofSeconds(30),
                1,
                new ProviderAdapterOptions.OpenAiResponses(
                        Optional.of("org"), Optional.empty(), ProviderReasoningSummary.CONCISE));
        ProviderRpcContracts.ProviderCreatePayload payload =
                new ProviderRpcContracts.ProviderCreatePayload("provider", spec, ProviderLifecycle.DISABLED);

        ProviderRpcContracts.ProviderCreatePayload decoded =
                JSON.decode(JSON.encode(payload), ProviderRpcContracts.ProviderCreatePayload.class);

        assertEquals(payload, decoded);
        assertTrue(JSON.encode(payload).json().contains("\"embeddingDimensions\":null"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderRpcContracts.ProviderCreatePayload("provider", spec, ProviderLifecycle.ARCHIVED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderRpcContracts.ProviderCreatePayload(
                        "provider with space", spec, ProviderLifecycle.DISABLED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderRpcContracts.ProviderCreatePayload(
                        "provider-active", spec, ProviderLifecycle.ACTIVE));
    }

    @Test
    void 禁用Provider连接壳允许空目录而启用状态拒绝空目录() {
        ProviderEndpointSpec emptySpec = new ProviderEndpointSpec(
                "OpenAI-compatible",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("https://api.example.test/v1")),
                ProviderAuthentication.API_KEY,
                List.of(),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));

        ProviderRpcContracts.ProviderCreatePayload disabled =
                new ProviderRpcContracts.ProviderCreatePayload("provider-shell", emptySpec, ProviderLifecycle.DISABLED);

        assertEquals(ProviderLifecycle.DISABLED, disabled.lifecycle());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderRpcContracts.ProviderCreatePayload(
                        "provider-active", emptySpec, ProviderLifecycle.ACTIVE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderRpcContracts.ProviderUpdatePayload(
                        "provider-active", emptySpec, ProviderLifecycle.ACTIVE));
    }

    @Test
    void 模型发现允许未知建议用途并保留精确Provider版本() {
        ProviderModelDiscoveryCandidate candidate =
                new ProviderModelDiscoveryCandidate("custom", "Custom", Set.of(), OptionalInt.empty());
        ProviderModelDiscoveryResult result =
                new ProviderModelDiscoveryResult("provider", 3, List.of(candidate), true, NOW);

        assertEquals(
                new ProviderModelDiscoveryRequest("provider", 3),
                JSON.decode(
                        JSON.encode(new ProviderModelDiscoveryRequest("provider", 3)),
                        ProviderModelDiscoveryRequest.class));
        assertEquals(result, JSON.decode(JSON.encode(result), ProviderModelDiscoveryResult.class));
        ProviderModelDiscoveryOperation operation = new ProviderModelDiscoveryOperation(
                "11111111-1111-1111-1111-111111111111",
                2,
                "provider",
                3,
                ProviderModelDiscoveryOperationState.SUCCEEDED,
                Optional.of(result),
                Optional.empty(),
                NOW,
                NOW);
        assertEquals(operation, JSON.decode(JSON.encode(operation), ProviderModelDiscoveryOperation.class));
        assertEquals(
                ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED,
                new ProviderModelDiscoveryRpcContracts.CancelPayload(
                                operation.operationId(), ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED)
                        .reason());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelDiscoveryRpcContracts.CancelPayload(operation.operationId(), "用户输入文本"));
    }

    @Test
    void Embedding绑定使用独立强类型结果() {
        EmbeddingBinding binding = new EmbeddingBinding(new ProviderRef("provider", 4, "embed"), 2, NOW);
        ProviderRpcContracts.EmbeddingBindingReadResult bindingResult =
                new ProviderRpcContracts.EmbeddingBindingReadResult(Optional.of(binding));
        assertEquals(
                bindingResult,
                JSON.decode(JSON.encode(bindingResult), ProviderRpcContracts.EmbeddingBindingReadResult.class));
        assertEquals(
                binding.provider(),
                new ProviderRpcContracts.EmbeddingBindingUpdatePayload(binding.provider()).provider());
    }
}
