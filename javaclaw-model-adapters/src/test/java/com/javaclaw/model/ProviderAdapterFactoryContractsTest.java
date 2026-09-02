package com.javaclaw.model;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.extension.spi.EmbeddingUnavailableException;
import com.javaclaw.runtime.ModelGateway;

import static com.javaclaw.model.ModelAdapterTestFixtures.simpleInvocation;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderAdapterFactoryContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final CredentialRef CREDENTIAL = new CredentialRef("provider", "credential-1");
    private static final String MODEL = "provider-model";

    @Test
    void 凭据材料始终复制并在关闭后拒绝读取() {
        char[] source = "secret".toCharArray();
        CredentialMaterial material = new CredentialMaterial(source);
        Arrays.fill(source, 'x');

        char[] first = material.copy();
        char[] second = material.copy();
        first[0] = 'x';

        assertNotSame(first, second);
        assertArrayEquals("secret".toCharArray(), second);
        material.close();
        material.close();
        assertThrows(IllegalStateException.class, material::copy);
        assertThrows(NullPointerException.class, () -> new CredentialMaterial(null));
        assertThrows(IllegalArgumentException.class, () -> new CredentialMaterial(new char[0]));
    }

    @Test
    void 模型工厂拒绝不匹配的冻结引用和未声明模型() {
        ProviderEndpoint endpoint = endpoint(ProviderAdapter.OPENAI_COMPATIBLE, Set.of(ProviderRole.CHAT), Map.of());
        ProviderModelAdapterFactory factory = new ProviderModelAdapterFactory(reference -> Optional.empty());

        assertThrows(NullPointerException.class, () -> new ProviderModelAdapterFactory(null));
        assertThrows(NullPointerException.class, () -> factory.create(null, reference(endpoint, MODEL)));
        assertThrows(NullPointerException.class, () -> factory.create(endpoint, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(endpoint, new ProviderRef("other-provider", endpoint.revision(), MODEL)));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(endpoint, new ProviderRef(endpoint.id(), endpoint.revision() + 1, MODEL)));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(endpoint, new ProviderRef(endpoint.id(), endpoint.revision(), "undeclared")));
    }

    @Test
    void 缺少凭据的模型网关对能力和调用均安全拒绝() {
        ProviderEndpoint endpoint = endpoint(ProviderAdapter.ANTHROPIC, Set.of(ProviderRole.CHAT), Map.of());
        ProviderRef reference = reference(endpoint, MODEL);
        ModelGateway gateway = new ProviderModelAdapterFactory(ignored -> Optional.empty()).create(endpoint, reference);

        IllegalStateException capabilityFailure =
                assertThrows(IllegalStateException.class, () -> gateway.capabilities(reference.routeKey()));
        IllegalStateException invocationFailure = assertThrows(
                IllegalStateException.class,
                () -> gateway.invoke(
                        TurnId.random(),
                        simpleInvocation(),
                        (turnId, event, cancellation) -> {},
                        new CancellationSource()));

        assertTrue(capabilityFailure.getMessage().contains(endpoint.id()));
        assertEquals(capabilityFailure.getMessage(), invocationFailure.getMessage());
    }

    @Test
    void 四类模型适配器均可离线构造并关闭() throws Exception {
        for (ProviderAdapter adapter : ProviderAdapter.values()) {
            Map<String, String> options =
                    adapter == ProviderAdapter.OPENAI_RESPONSES ? Map.of("reasoningSummary", "detailed") : Map.of();
            ProviderEndpoint endpoint = endpoint(adapter, Set.of(ProviderRole.CHAT), options);
            ProviderRef reference = reference(endpoint, MODEL);
            ModelGateway gateway = new ProviderModelAdapterFactory(this::credential).create(endpoint, reference);

            assertTrue(gateway.capabilities(reference.routeKey()).streaming());
            if (gateway instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    @Test
    void Responses摘要选项忽略大小写并拒绝未知值() throws Exception {
        for (String value : List.of("AUTO", "concise", "Detailed")) {
            ProviderEndpoint endpoint = endpoint(
                    ProviderAdapter.OPENAI_RESPONSES, Set.of(ProviderRole.CHAT), Map.of("reasoningSummary", value));
            ProviderRef reference = reference(endpoint, MODEL);
            ModelGateway gateway = new ProviderModelAdapterFactory(this::credential).create(endpoint, reference);
            ((AutoCloseable) gateway).close();
        }

        ProviderEndpoint invalid = endpoint(
                ProviderAdapter.OPENAI_RESPONSES, Set.of(ProviderRole.CHAT), Map.of("reasoningSummary", "unavailable"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelAdapterFactory(ignored -> Optional.empty())
                        .create(invalid, reference(invalid, MODEL)));
    }

    @Test
    void Embedding工厂拒绝角色模型维度和默认选择错误() {
        ProviderEmbeddingAdapterFactory factory = new ProviderEmbeddingAdapterFactory(ignored -> Optional.empty());
        ProviderEndpoint chatOnly = endpoint(ProviderAdapter.OPENAI_COMPATIBLE, Set.of(ProviderRole.CHAT), Map.of());
        ProviderEndpoint invalidNumber = embeddingEndpoint(ProviderAdapter.OPENAI_COMPATIBLE, "not-a-number");
        ProviderEndpoint tooSmall = embeddingEndpoint(ProviderAdapter.OPENAI_COMPATIBLE, "0");
        ProviderEndpoint tooLarge = embeddingEndpoint(ProviderAdapter.GOOGLE_GENAI, "65537");
        ProviderEndpoint invalidDefault = endpoint(
                ProviderAdapter.GOOGLE_GENAI, Set.of(ProviderRole.EMBEDDING), Map.of("defaultEmbedding", "sometimes"));

        assertThrows(NullPointerException.class, () -> new ProviderEmbeddingAdapterFactory(null));
        assertThrows(NullPointerException.class, () -> factory.create(null, MODEL));
        assertThrows(NullPointerException.class, () -> factory.create(chatOnly, null));
        assertThrows(IllegalArgumentException.class, () -> factory.create(chatOnly, MODEL));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(embeddingEndpoint(ProviderAdapter.GOOGLE_GENAI, "3"), "other"));
        assertThrows(IllegalArgumentException.class, () -> factory.create(invalidNumber, MODEL));
        assertThrows(IllegalArgumentException.class, () -> factory.create(tooSmall, MODEL));
        assertThrows(IllegalArgumentException.class, () -> factory.create(tooLarge, MODEL));
        assertThrows(IllegalArgumentException.class, () -> factory.create(invalidDefault, MODEL));
    }

    @Test
    void 不支持或缺少凭据的Embedding端口安全拒绝() {
        AtomicBoolean resolved = new AtomicBoolean();
        ProviderEmbeddingAdapterFactory factory = new ProviderEmbeddingAdapterFactory(reference -> {
            resolved.set(true);
            return Optional.of(new CredentialMaterial("unused".toCharArray()));
        });
        EmbeddingPort anthropic =
                factory.create(endpoint(ProviderAdapter.ANTHROPIC, Set.of(ProviderRole.EMBEDDING), Map.of()), MODEL);
        assertFalse(resolved.get());
        assertThrows(
                EmbeddingUnavailableException.class,
                () -> anthropic.embed(List.of("document"), EmbeddingPurpose.DOCUMENT, new CancellationSource()));

        ProviderEmbeddingAdapterFactory missing = new ProviderEmbeddingAdapterFactory(ignored -> Optional.empty());
        EmbeddingPort unavailable = missing.create(
                endpoint(ProviderAdapter.OPENAI_COMPATIBLE, Set.of(ProviderRole.EMBEDDING), Map.of()), MODEL);
        assertThrows(
                EmbeddingUnavailableException.class,
                () -> unavailable.embed(List.of("query"), EmbeddingPurpose.QUERY, new CancellationSource()));
    }

    @Test
    void OpenAI和GoogleEmbedding客户端可离线构造并释放() throws Exception {
        for (ProviderAdapter adapter : List.of(
                ProviderAdapter.OPENAI_COMPATIBLE, ProviderAdapter.OPENAI_RESPONSES, ProviderAdapter.GOOGLE_GENAI)) {
            ProviderEndpoint endpoint = endpoint(
                    adapter,
                    Set.of(ProviderRole.EMBEDDING),
                    Map.of("embeddingDimensions", "16", "defaultEmbedding", "true"));
            EmbeddingPort port = new ProviderEmbeddingAdapterFactory(this::credential).create(endpoint, MODEL);

            assertTrue(port instanceof AutoCloseable);
            ((AutoCloseable) port).close();
        }
    }

    private Optional<CredentialMaterial> credential(CredentialRef reference) {
        assertEquals(CREDENTIAL, reference);
        return Optional.of(new CredentialMaterial("local-test-secret".toCharArray()));
    }

    private static ProviderEndpoint embeddingEndpoint(ProviderAdapter adapter, String dimensions) {
        return endpoint(adapter, Set.of(ProviderRole.EMBEDDING), Map.of("embeddingDimensions", dimensions));
    }

    private static ProviderRef reference(ProviderEndpoint endpoint, String model) {
        return new ProviderRef(endpoint.id(), endpoint.revision(), model);
    }

    private static ProviderEndpoint endpoint(
            ProviderAdapter adapter, Set<ProviderRole> roles, Map<String, String> options) {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "Local fake provider",
                adapter,
                Optional.of(URI.create("http://127.0.0.1:1")),
                roles,
                List.of(MODEL),
                Optional.of(CREDENTIAL),
                Duration.ofMillis(250),
                0,
                options);
        return new ProviderEndpoint("provider-main", 3, ProviderLifecycle.ACTIVE, spec, NOW, NOW);
    }
}
