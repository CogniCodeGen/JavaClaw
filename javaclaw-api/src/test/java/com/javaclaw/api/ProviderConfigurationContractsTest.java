package com.javaclaw.api;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigurationContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-20T01:00:00Z");
    private static final CredentialRef REFERENCE = new CredentialRef("provider", "key-main");

    @Test
    void 连接草稿只复制连接参数并由服务端显式组装模型和凭据() {
        ProviderEndpointSpec saved = connection().toEndpointSpec(models(), Optional.of(REFERENCE));

        ProviderConnectionSpec draft = ProviderConnectionSpec.from(saved);

        assertEquals(saved, draft.toEndpointSpec(saved.models(), saved.credential()));
        assertTrue(draft.toEndpointSpec(List.of(), Optional.empty()).models().isEmpty());
        assertTrue(
                draft.toEndpointSpec(List.of(), Optional.empty()).credential().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConnectionSpec(
                        "非法连接",
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        Optional.of(URI.create("http://remote.example.test/v1")),
                        ProviderAuthentication.API_KEY,
                        Duration.ofSeconds(30),
                        0,
                        ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE)));
    }

    @Test
    void 配置复制模型并拒绝空目录归档和非法双版本() {
        ArrayList<ProviderModelSpec> source = new ArrayList<>(models());
        var configuration = new ProviderConfiguration(
                " provider-main ",
                0,
                connection(),
                source,
                ProviderLifecycle.ACTIVE,
                ProviderCredentialChange.REPLACE,
                0);
        source.clear();

        assertEquals("provider-main", configuration.providerId());
        assertEquals(models(), configuration.models());
        assertThrows(
                UnsupportedOperationException.class,
                () -> configuration.models().clear());
        assertThrows(IllegalArgumentException.class, () -> configuration(-1, models(), ProviderLifecycle.ACTIVE, 0));
        assertThrows(IllegalArgumentException.class, () -> configuration(1, models(), ProviderLifecycle.ACTIVE, -1));
        assertThrows(IllegalArgumentException.class, () -> configuration(0, List.of(), ProviderLifecycle.DISABLED, 0));
        assertThrows(IllegalArgumentException.class, () -> configuration(1, models(), ProviderLifecycle.ARCHIVED, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration(1, List.of(models().getFirst(), models().getFirst()), ProviderLifecycle.ACTIVE, 1));
    }

    @Test
    void 配置拒绝空对象避免缺失字段成为默认提交意图() {
        assertThrows(
                NullPointerException.class,
                () -> new ProviderConfiguration(
                        "provider-main",
                        0,
                        null,
                        models(),
                        ProviderLifecycle.ACTIVE,
                        ProviderCredentialChange.REPLACE,
                        0));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderConfiguration(
                        "provider-main",
                        0,
                        connection(),
                        null,
                        ProviderLifecycle.ACTIVE,
                        ProviderCredentialChange.REPLACE,
                        0));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderConfiguration(
                        "provider-main", 0, connection(), models(), null, ProviderCredentialChange.REPLACE, 0));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderConfiguration(
                        "provider-main", 0, connection(), models(), ProviderLifecycle.ACTIVE, null, 0));
    }

    @Test
    void 预览拒绝空来源容器连接和凭据意图() {
        assertThrows(
                NullPointerException.class,
                () -> new ProviderModelPreviewRequest(
                        "draft-main", 1, null, Optional.empty(), ProviderCredentialChange.REPLACE));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderModelPreviewRequest(
                        "draft-main", 1, connection(), null, ProviderCredentialChange.REPLACE));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderModelPreviewRequest("draft-main", 1, connection(), Optional.empty(), null));
    }

    @Test
    void 编辑来源要求真实Provider版本而预览拥有独立草稿代次() {
        var source = new ProviderConfigurationSource(" provider-main ", 2, 1);
        var preview = new ProviderModelPreviewRequest(
                "draft-main", 3, connection(), Optional.of(source), ProviderCredentialChange.KEEP);

        assertEquals(2, preview.source().orElseThrow().providerRevision());
        assertEquals(3, preview.generation());
        assertEquals("provider-main", source.providerId());
        assertThrows(IllegalArgumentException.class, () -> new ProviderConfigurationSource("provider-main", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProviderConfigurationSource("provider-main", 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new ProviderConfigurationSource("bad id", 1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelPreviewRequest(
                        "draft-main", 0, connection(), Optional.empty(), ProviderCredentialChange.REPLACE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelPreviewRequest(
                        "bad id", 1, connection(), Optional.empty(), ProviderCredentialChange.REPLACE));
    }

    @Test
    void 完整配置回执必须与最终Provider凭据绑定一致() {
        var metadata = new CredentialMetadata(REFERENCE, 1, NOW);
        ProviderEndpoint bound = provider(Optional.of(REFERENCE));

        assertEquals(Optional.of(metadata), new ProviderConfigurationResult(bound, Optional.of(metadata)).credential());
        assertTrue(new ProviderConfigurationResult(provider(Optional.empty()), Optional.empty())
                .credential()
                .isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ProviderConfigurationResult(bound, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConfigurationResult(provider(Optional.empty()), Optional.of(metadata)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConfigurationResult(
                        bound, Optional.of(new CredentialMetadata(new CredentialRef("provider", "other"), 1, NOW))));
        assertThrows(NullPointerException.class, () -> new ProviderConfigurationResult(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new ProviderConfigurationResult(bound, null));
    }

    static ProviderConnectionSpec connection() {
        return new ProviderConnectionSpec(
                "测试服务",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("https://models.example.test/v1")),
                ProviderAuthentication.API_KEY,
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    static List<ProviderModelSpec> models() {
        return List.of(
                new ProviderModelSpec("chat-model", "对话模型", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty()));
    }

    private static ProviderConfiguration configuration(
            long revision, List<ProviderModelSpec> models, ProviderLifecycle lifecycle, long credentialRevision) {
        return new ProviderConfiguration(
                "provider-main",
                revision,
                connection(),
                models,
                lifecycle,
                ProviderCredentialChange.KEEP,
                credentialRevision);
    }

    private static ProviderEndpoint provider(Optional<CredentialRef> reference) {
        return new ProviderEndpoint(
                "provider-main",
                1,
                ProviderLifecycle.DISABLED,
                connection().toEndpointSpec(models(), reference),
                NOW,
                NOW);
    }
}
