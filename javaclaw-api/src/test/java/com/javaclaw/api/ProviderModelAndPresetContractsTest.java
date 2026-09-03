package com.javaclaw.api;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelAndPresetContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("8efcb622-709c-44db-bcf5-fc9d8c605738");

    @Test
    void 模型用途和维度属于单个模型而不是整个端点() {
        ProviderModelSpec chat = model("chat", Set.of(ProviderModelPurpose.CHAT));
        ProviderModelSpec embedding = new ProviderModelSpec(
                "embedding", "Embedding", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.of(3_072));
        ProviderEndpointSpec spec = spec(
                ProviderAdapter.OPENAI_COMPATIBLE,
                ProviderAuthentication.API_KEY,
                Optional.empty(),
                List.of(chat, embedding),
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));

        assertTrue(chat.supports(ProviderModelPurpose.CHAT));
        assertFalse(chat.supports(ProviderModelPurpose.EMBEDDING));
        assertEquals(3_072, spec.models().get(1).embeddingDimensions().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelSpec("chat", "Chat", Set.of(ProviderModelPurpose.CHAT), OptionalInt.of(512)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelSpec(
                        "embedding", "Embedding", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.of(65_537)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelSpec("unknown", "Unknown", Set.of(), OptionalInt.empty()));
    }

    @Test
    void 禁用连接壳可为空而启用版本必须声明模型() {
        ProviderEndpointSpec empty = spec(
                ProviderAdapter.OPENAI_COMPATIBLE,
                ProviderAuthentication.API_KEY,
                Optional.empty(),
                List.of(),
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));

        ProviderEndpoint disabled = new ProviderEndpoint("provider", 1, ProviderLifecycle.DISABLED, empty, NOW, NOW);

        assertTrue(disabled.spec().models().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderEndpoint("provider", 1, ProviderLifecycle.ACTIVE, empty, NOW, NOW));
    }

    @Test
    void Adapter强类型选项拒绝跨厂商字段() {
        ProviderAdapterOptions.OpenAiResponses responses = new ProviderAdapterOptions.OpenAiResponses(
                Optional.of("org"), Optional.of("project"), ProviderReasoningSummary.DETAILED);
        ProviderAdapterOptions.GoogleGenAi google = new ProviderAdapterOptions.GoogleGenAi(Optional.of("v1beta"));

        assertEquals(ProviderReasoningSummary.DETAILED, responses.reasoningSummary());
        assertEquals("v1beta", google.apiVersion().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> spec(
                        ProviderAdapter.ANTHROPIC,
                        ProviderAuthentication.API_KEY,
                        Optional.empty(),
                        List.of(model("chat", Set.of(ProviderModelPurpose.CHAT))),
                        new ProviderAdapterOptions.OpenAiCompatible(Optional.of("org"), Optional.empty())));
    }

    @Test
    void 无鉴权只允许显式兼容端点且不绑定凭据() {
        URI custom = URI.create("http://models.example.test/v1");
        ProviderEndpointSpec allowed = spec(
                ProviderAdapter.OPENAI_COMPATIBLE,
                ProviderAuthentication.NONE,
                Optional.of(custom),
                List.of(model("chat", Set.of(ProviderModelPurpose.CHAT))),
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));

        assertEquals(custom, allowed.baseUri().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> spec(
                        ProviderAdapter.ANTHROPIC,
                        ProviderAuthentication.NONE,
                        Optional.of(custom),
                        List.of(model("chat", Set.of(ProviderModelPurpose.CHAT))),
                        ProviderAdapterOptions.defaults(ProviderAdapter.ANTHROPIC)));
    }

    @Test
    void 自定义地址只接受API根且Google版本是安全单段值() {
        assertThrows(
                IllegalArgumentException.class,
                () -> spec(
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        ProviderAuthentication.NONE,
                        Optional.of(URI.create("https://models.example.test/v1/chat/completions")),
                        List.of(model("chat", Set.of(ProviderModelPurpose.CHAT))),
                        ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> spec(
                        ProviderAdapter.GOOGLE_GENAI,
                        ProviderAuthentication.API_KEY,
                        Optional.of(URI.create("https://generativelanguage.example.test/v1beta")),
                        List.of(model("chat", Set.of(ProviderModelPurpose.CHAT))),
                        new ProviderAdapterOptions.GoogleGenAi(Optional.of("v1beta"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderAdapterOptions.GoogleGenAi(Optional.of("v1beta/models")));
    }

    @Test
    void 目录候选允许未知用途并限制重复和数量() {
        ProviderModelDiscoveryCandidate unknown =
                new ProviderModelDiscoveryCandidate("custom", "Custom", Set.of(), OptionalInt.empty());
        ProviderModelDiscoveryCandidate embedding = new ProviderModelDiscoveryCandidate(
                "embed", "Embed", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.of(768));
        ProviderModelDiscoveryResult result =
                new ProviderModelDiscoveryResult("provider", 2, List.of(unknown, embedding), false, NOW);

        assertTrue(result.candidates().getFirst().suggestedPurposes().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelDiscoveryCandidate("bad", "Bad", Set.of(), OptionalInt.of(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelDiscoveryResult("provider", 2, List.of(unknown, unknown), false, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelDiscoveryResult(
                        "provider", 2, java.util.Collections.nCopies(1_001, unknown), true, NOW));
        assertEquals(2, new ProviderModelDiscoveryRequest("provider", 2).endpointRevision());
    }

    @Test
    void Profile和权限预设只是普通配置的可审阅初值() {
        TurnBudget budget = new TurnBudget(32_000, 4_000, 24, 0, Duration.ofMinutes(5));
        AgentProfilePreset profilePreset =
                new AgentProfilePreset("explorer", 1, "Explorer", "只读探索", "只读探索并返回证据。", "a".repeat(64), budget);
        PermissionPresetDescriptor permissionPreset =
                new PermissionPresetDescriptor("workspace-review", 1, "Workspace review", "只读工作区", false);
        PermissionPresetInstantiationRequest request = new PermissionPresetInstantiationRequest(
                permissionPreset.id(),
                permissionPreset.revision(),
                WORKSPACE,
                "explorer-review",
                Set.of("read"),
                Set.of());
        PermissionProfile proposed = permissionProfile(request.profileId());
        PermissionPresetPreview preview =
                new PermissionPresetPreview(permissionPreset, WORKSPACE, proposed, List.of("不允许写入"));
        PermissionPresetInstantiationResult result =
                new PermissionPresetInstantiationResult(permissionPreset, WORKSPACE, proposed);

        assertEquals(budget, profilePreset.defaultBudget());
        assertEquals(proposed, preview.proposedProfile());
        assertEquals(proposed, result.profile());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionPresetInstantiationRequest(
                        "workspace-developer", 1, WORKSPACE, "developer", Set.of("*"), Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionPresetInstantiationRequest(
                        "workspace-developer", 1, WORKSPACE, "developer", Set.of("core/*"), Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionPresetInstantiationRequest(
                        "workspace-review", 1, WORKSPACE, "standard", Set.of(), Set.of()));
    }

    @Test
    void Embedding绑定始终指向精确Provider版本() {
        ProviderRef provider = new ProviderRef("embedding", 4, "text-embedding");
        EmbeddingBinding binding = new EmbeddingBinding(provider, 2, NOW);

        assertEquals(4, binding.provider().endpointRevision());
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingBinding(provider, 0, NOW));
    }

    @Test
    void Provider与预设输入边界和ProtocolSchema保持一致() {
        ProviderModelSpec chat = model("chat", Set.of(ProviderModelPurpose.CHAT));
        Set<String> tooManyTools =
                IntStream.range(0, 10_001).mapToObj(index -> "tool-" + index).collect(Collectors.toUnmodifiableSet());

        assertThrows(IllegalArgumentException.class, () -> model("m".repeat(1_001), Set.of(ProviderModelPurpose.CHAT)));
        assertThrows(
                IllegalArgumentException.class,
                () -> spec(
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        ProviderAuthentication.API_KEY,
                        Optional.empty(),
                        java.util.Collections.nCopies(1_001, chat),
                        ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> spec(
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        ProviderAuthentication.API_KEY,
                        Optional.of(URI.create("https://example.test/" + "a".repeat(4_096))),
                        List.of(chat),
                        ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionPresetInstantiationRequest(
                        "workspace-review", 1, WORKSPACE, "review", tooManyTools, Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionPresetPreview(
                        new PermissionPresetDescriptor("review", 1, "Review", "只读", false),
                        WORKSPACE,
                        permissionProfile("review"),
                        java.util.Collections.nCopies(101, "提示")));
    }

    private static ProviderEndpointSpec spec(
            ProviderAdapter adapter,
            ProviderAuthentication authentication,
            Optional<URI> baseUri,
            List<ProviderModelSpec> models,
            ProviderAdapterOptions options) {
        return new ProviderEndpointSpec(
                "Provider",
                adapter,
                baseUri,
                authentication,
                models,
                Optional.empty(),
                Duration.ofSeconds(30),
                1,
                options);
    }

    private static ProviderModelSpec model(String id, Set<ProviderModelPurpose> purposes) {
        return new ProviderModelSpec(id, id, purposes, OptionalInt.empty());
    }

    private static PermissionProfile permissionProfile(String id) {
        return new PermissionProfile(
                id,
                1,
                new FilePermission(List.of(Path.of("/workspace")), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                new ToolPermission(Set.of("read"), ToolRisk.READ_ONLY, ApprovalRequirement.RISKY),
                new ResourceLimits(1024, 1024, 1, 1));
    }
}
