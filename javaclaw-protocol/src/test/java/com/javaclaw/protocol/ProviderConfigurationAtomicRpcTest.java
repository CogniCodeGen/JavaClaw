package com.javaclaw.protocol;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderConfigurationSource;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelPreviewOperation;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigurationAtomicRpcTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final Instant NOW = Instant.parse("2026-09-20T01:00:00Z");

    @Test
    void 五个新方法需要双方协商且不会改变旧Provider接口能力() {
        Map<String, RpcMethodKind> methods = Map.of(
                ProviderConfigurationRpcContracts.SAVE_METHOD, RpcMethodKind.COMMAND,
                ProviderConfigurationRpcContracts.RESULT_METHOD, RpcMethodKind.QUERY,
                ProviderConfigurationRpcContracts.PREVIEW_START_METHOD, RpcMethodKind.COMMAND,
                ProviderConfigurationRpcContracts.PREVIEW_READ_METHOD, RpcMethodKind.QUERY,
                ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD, RpcMethodKind.COMMAND);
        NegotiatedCapabilities absent = negotiated(Set.of());
        NegotiatedCapabilities present = negotiated(Set.of(ProviderConfigurationRpcContracts.CAPABILITY));

        for (var entry : methods.entrySet()) {
            var failure = assertThrows(ProtocolException.class, () -> MethodCatalog.require(entry.getKey(), absent));
            assertEquals(ProtocolErrorCode.CAPABILITY_NOT_NEGOTIATED, failure.code());
            RpcMethod method = MethodCatalog.require(entry.getKey(), present);
            assertEquals(entry.getValue(), method.kind());
            assertEquals(Optional.of(ProviderConfigurationRpcContracts.CAPABILITY), method.capability());
            assertFalse(method.experimental());
        }
        for (String old : List.of(
                "provider/create",
                "provider/update",
                "provider/credential/set",
                "provider/credential/clear",
                ProviderModelDiscoveryRpcContracts.START_METHOD)) {
            assertEquals(
                    RpcMethodKind.COMMAND, MethodCatalog.require(old, absent).kind());
        }
        assertTrue(StableCapabilities.all().contains(ProviderConfigurationRpcContracts.CAPABILITY));
        assertTrue(StableCapabilities.withMcp().contains(ProviderConfigurationRpcContracts.CAPABILITY));
        assertFalse(new ProtocolNegotiator(Set.of(), Set.of())
                .negotiate(new InitializeParams(
                        ProtocolVersion.CURRENT,
                        new ClientInfo("Desktop", "test"),
                        new CapabilityAdvertisement(Set.of(ProviderConfigurationRpcContracts.CAPABILITY), Set.of())))
                .allows(ProviderConfigurationRpcContracts.CAPABILITY));
    }

    @Test
    void 完整配置来源回执与草稿操作通过共享Codec往返() {
        ProviderConfiguration configuration = configuration(ProviderCredentialChange.REPLACE);
        var source = new ProviderConfigurationSource("provider-main", 2, 1);
        var preview = new ProviderModelPreviewRequest(
                "draft-main", 3, connection(), Optional.of(source), ProviderCredentialChange.KEEP);
        var result = new ProviderModelPreviewResult("draft-main", 3, List.of(), false, NOW);
        var operation = new ProviderModelPreviewOperation(
                "preview-main",
                2,
                "draft-main",
                3,
                ProviderModelDiscoveryOperationState.SUCCEEDED,
                Optional.of(result),
                Optional.empty(),
                NOW,
                NOW);

        roundTrip(connection(), ProviderConnectionSpec.class);
        roundTrip(configuration, ProviderConfiguration.class);
        roundTrip(source, ProviderConfigurationSource.class);
        roundTrip(preview, ProviderModelPreviewRequest.class);
        roundTrip(result, ProviderModelPreviewResult.class);
        roundTrip(operation, ProviderModelPreviewOperation.class);
        roundTrip(saved(), ProviderConfigurationResult.class);
        for (ProviderCredentialChange change : ProviderCredentialChange.values()) {
            roundTrip(configuration(change), ProviderConfiguration.class);
        }
        assertFalse(JSON.encode(configuration).json().contains("credentialRef"));
        assertEquals(
                Set.of("providerId", "providerRevision", "credentialRevision"), JSON.fieldNames(JSON.encode(source)));
    }

    @Test
    void 保存与预览密文用途严格分离且只有替换意图可携带密文() {
        var saveSecret = sealed(ProviderConfigurationRpcContracts.SAVE_PURPOSE);
        var previewSecret = sealed(ProviderConfigurationRpcContracts.PREVIEW_PURPOSE);
        var save = new ProviderConfigurationRpcContracts.SavePayload(
                configuration(ProviderCredentialChange.REPLACE), Optional.of(saveSecret));
        var preview = new ProviderConfigurationRpcContracts.PreviewPayload(
                preview(ProviderCredentialChange.REPLACE), Optional.of(previewSecret));

        roundTrip(save, ProviderConfigurationRpcContracts.SavePayload.class);
        roundTrip(preview, ProviderConfigurationRpcContracts.PreviewPayload.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConfigurationRpcContracts.SavePayload(
                        configuration(ProviderCredentialChange.REPLACE), Optional.of(previewSecret)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConfigurationRpcContracts.PreviewPayload(
                        preview(ProviderCredentialChange.REPLACE), Optional.of(saveSecret)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConfigurationRpcContracts.SavePayload(
                        configuration(ProviderCredentialChange.REPLACE), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConfigurationRpcContracts.PreviewPayload(
                        preview(ProviderCredentialChange.REPLACE), Optional.empty()));
    }

    @Test
    void 保留清除和空容器不能隐式写入密钥() {
        for (ProviderCredentialChange change : List.of(ProviderCredentialChange.KEEP, ProviderCredentialChange.CLEAR)) {
            roundTrip(
                    new ProviderConfigurationRpcContracts.SavePayload(configuration(change), Optional.empty()),
                    ProviderConfigurationRpcContracts.SavePayload.class);
            roundTrip(
                    new ProviderConfigurationRpcContracts.PreviewPayload(preview(change), Optional.empty()),
                    ProviderConfigurationRpcContracts.PreviewPayload.class);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProviderConfigurationRpcContracts.SavePayload(
                            configuration(change),
                            Optional.of(sealed(ProviderConfigurationRpcContracts.SAVE_PURPOSE))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProviderConfigurationRpcContracts.PreviewPayload(
                            preview(change), Optional.of(sealed(ProviderConfigurationRpcContracts.PREVIEW_PURPOSE))));
        }
        assertThrows(
                NullPointerException.class,
                () -> new ProviderConfigurationRpcContracts.SavePayload(null, Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderConfigurationRpcContracts.SavePayload(
                        configuration(ProviderCredentialChange.KEEP), null));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderConfigurationRpcContracts.PreviewPayload(null, Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderConfigurationRpcContracts.PreviewPayload(
                        preview(ProviderCredentialChange.KEEP), null));
    }

    @Test
    void 结果查询保持精确摘要身份且空结果明确表达未知() {
        var query = new ProviderConfigurationRpcContracts.ResultPayload(" command-main ", 2, "a".repeat(64));
        assertEquals("command-main", query.idempotencyKey());
        roundTrip(query, ProviderConfigurationRpcContracts.ResultPayload.class);
        roundTrip(
                new ProviderConfigurationRpcContracts.ResultResponse(Optional.of(saved())),
                ProviderConfigurationRpcContracts.ResultResponse.class);
        var unknown = new ProviderConfigurationRpcContracts.ResultResponse(Optional.empty());
        roundTrip(unknown, ProviderConfigurationRpcContracts.ResultResponse.class);
        assertTrue(JSON.encode(unknown).json().contains("\"result\":null"));
        for (String key : List.of("", " ", "x".repeat(241))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProviderConfigurationRpcContracts.ResultPayload(key, 0, "a".repeat(64)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConfigurationRpcContracts.ResultPayload("key", -1, "a".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConfigurationRpcContracts.ResultPayload("key", 0, "A".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConfigurationRpcContracts.ResultPayload("key", 0, "a".repeat(63)));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderConfigurationRpcContracts.ResultPayload(null, 0, "a".repeat(64)));
        assertThrows(
                NullPointerException.class, () -> new ProviderConfigurationRpcContracts.ResultPayload("key", 0, null));
        assertThrows(NullPointerException.class, () -> new ProviderConfigurationRpcContracts.ResultResponse(null));
    }

    @Test
    void 旧凭据及目录查询参数保持原Wire形状并拒绝预览用途密文() {
        var secret = sealed(ProviderCredentialRpcContracts.SET_PURPOSE);
        var old = new ProviderCredentialRpcContracts.SetPayload("provider-main", 2, 1, secret);
        roundTrip(old, ProviderCredentialRpcContracts.SetPayload.class);
        assertEquals(
                Set.of("providerId", "providerExpectedRevision", "credentialExpectedRevision", "secret"),
                JSON.fieldNames(JSON.encode(old)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderCredentialRpcContracts.SetPayload(
                        "provider-main", 2, 1, sealed(ProviderConfigurationRpcContracts.PREVIEW_PURPOSE)));
        var read = new ProviderModelDiscoveryRpcContracts.ReadPayload("preview-main");
        var cancel = new ProviderModelDiscoveryRpcContracts.CancelPayload(
                "preview-main", ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED);
        roundTrip(read, ProviderModelDiscoveryRpcContracts.ReadPayload.class);
        roundTrip(cancel, ProviderModelDiscoveryRpcContracts.CancelPayload.class);
        assertEquals(Set.of("operationId"), JSON.fieldNames(JSON.encode(read)));
        assertEquals(Set.of("operationId", "reason"), JSON.fieldNames(JSON.encode(cancel)));
    }

    private static NegotiatedCapabilities negotiated(Set<String> client) {
        return new ProtocolNegotiator(StableCapabilities.all(), Set.of())
                .negotiate(new InitializeParams(
                        ProtocolVersion.CURRENT,
                        new ClientInfo("Desktop", "test"),
                        new CapabilityAdvertisement(client, Set.of())));
    }

    private static ProviderConnectionSpec connection() {
        return new ProviderConnectionSpec(
                "测试服务",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("https://models.example.test/v1")),
                ProviderAuthentication.API_KEY,
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private static ProviderConfiguration configuration(ProviderCredentialChange change) {
        var model = new ProviderModelSpec("chat-model", "对话模型", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty());
        return new ProviderConfiguration(
                "provider-main", 0, connection(), List.of(model), ProviderLifecycle.ACTIVE, change, 0);
    }

    private static ProviderModelPreviewRequest preview(ProviderCredentialChange change) {
        return new ProviderModelPreviewRequest("draft-main", 1, connection(), Optional.empty(), change);
    }

    private static ProviderConfigurationResult saved() {
        CredentialRef reference = new CredentialRef("provider", "key-main");
        ProviderConfiguration request = configuration(ProviderCredentialChange.REPLACE);
        ProviderEndpoint endpoint = new ProviderEndpoint(
                "provider-main",
                1,
                ProviderLifecycle.ACTIVE,
                request.connection().toEndpointSpec(request.models(), Optional.of(reference)),
                NOW,
                NOW);
        return new ProviderConfigurationResult(endpoint, Optional.of(new CredentialMetadata(reference, 1, NOW)));
    }

    private static SealedSecret sealed(String purpose) {
        return new SealedSecret("session-test", purpose, "AA", "AA", "AA");
    }

    private static void roundTrip(Object value, Class<?> type) {
        assertEquals(value, JSON.decode(JSON.encode(value), type));
    }
}
