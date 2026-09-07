package com.javaclaw.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderCapabilities;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelDiscoveryOperation;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelDiscoveryRequest;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.api.ProviderVerificationUsage;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.client.facade.CredentialClient;
import com.javaclaw.client.facade.ProviderClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProviderCredentialRpcContracts;
import com.javaclaw.protocol.ProviderModelDiscoveryRpcContracts;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

    @Test
    void SDK容量契约保持精确版本且空值不伪装成无限窗口() {
        var provider = new ProviderRef("provider-main", 1, "test-model");
        var unknown = com.javaclaw.api.ModelContextLimits.unknown(provider);
        var saved = new com.javaclaw.api.ModelContextLimits(
                new ProviderRef("provider-main", 2, "test-model"),
                java.util.OptionalLong.of(128000),
                java.util.OptionalLong.empty());
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            ProviderClient client = client(secrets, request -> {
                if (request.method().equals(com.javaclaw.protocol.ProviderContextRpcContracts.READ_METHOD)) {
                    var query = JSON.decode(
                            request.params(), com.javaclaw.protocol.ProviderContextRpcContracts.ReadPayload.class);
                    assertEquals(provider, query.provider());
                    return JsonRpcResponse.success(request.id(), JSON.encode(unknown));
                }
                assertEquals(com.javaclaw.protocol.ProviderContextRpcContracts.UPDATE_METHOD, request.method());
                var command = JSON.decode(request.params(), WriteCommand.class);
                assertEquals(1, command.expectedRevision());
                assertEquals(
                        provider,
                        JSON.decode(command.payload(), com.javaclaw.api.ModelContextLimits.class)
                                .provider());
                return JsonRpcResponse.success(request.id(), JSON.encode(saved));
            });
            assertEquals(unknown, client.contextLimits(provider));
            assertEquals(saved, client.updateContextLimits(unknown, new CommandOptions("capacity", 1)));
        }
    }

    @Test
    void SDK密封Secret并分别携带Provider和CredentialRevision() {
        CredentialRef reference = new CredentialRef("provider", "credential-1");
        ProviderCredentialBinding binding = new ProviderCredentialBinding(
                provider(2, Optional.of(reference)), new CredentialMetadata(reference, 1, NOW));
        ProviderCredentialClearResult cleared = new ProviderCredentialClearResult(
                provider(3, Optional.empty()), new CredentialClearReceipt(reference, 1, NOW));
        AtomicReference<String> unsealed = new AtomicReference<>();
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            ProviderClient client = client(secrets, request -> response(request, secrets, binding, cleared, unsealed));

            assertEquals(
                    binding,
                    client.setCredential(
                            "provider-main",
                            1,
                            0,
                            "provider-secret".toCharArray(),
                            new CommandOptions("set-provider-secret", 1)));
            assertEquals("provider-secret", unsealed.get());
            assertEquals(
                    cleared,
                    client.clearCredential(
                            "provider-main", 2, reference, 1, new CommandOptions("clear-provider-secret", 2)));
        }
    }

    @Test
    void SDK在发送前拒绝不一致的ProviderExpectedRevision() {
        AtomicInteger requests = new AtomicInteger();
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            Function<JsonRpcRequest, JsonRpcResponse> rejected = request -> {
                requests.incrementAndGet();
                throw new AssertionError("revision 不一致时不得发送 RPC");
            };
            ProviderClient client = client(secrets, rejected);
            CredentialClient generic = new CredentialClient(
                    new RpcClientConnection(new ScriptedRpcConnection(rejected), JSON, ignored -> {}),
                    secrets.publicKey());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.setCredential(
                            "provider-main", 2, 0, "secret".toCharArray(), new CommandOptions("mismatch", 1)));
            CredentialRef providerSecret = new CredentialRef("provider", "credential-1");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> generic.create("provider", "secret".toCharArray(), new CommandOptions("create", 0)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> generic.rotate(providerSecret, "secret".toCharArray(), new CommandOptions("rotate", 1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> generic.clear(providerSecret, new CommandOptions("clear", 1)));
            assertEquals(0, requests.get());
        }
    }

    @Test
    void SDK只发送精确Provider和双重计费确认() {
        ProviderRef provider = new ProviderRef("provider-main", 2, "test-model");
        ProviderVerificationResult expected = new ProviderVerificationResult(
                provider,
                ProviderModelPurpose.CHAT,
                ProviderVerificationState.SUCCEEDED,
                9,
                Optional.of(new ProviderVerificationUsage(2, 1, 0, 0)),
                new ProviderCapabilities(
                        Set.of(ProviderModelPurpose.CHAT), true, true, true, false, false, false, false),
                Optional.empty(),
                NOW);
        AtomicInteger requests = new AtomicInteger();
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            ProviderClient client = client(secrets, request -> {
                requests.incrementAndGet();
                WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                var payload = JSON.decode(command.payload(), ProviderVerificationRpcContracts.VerifyPayload.class);
                assertEquals(ProviderVerificationRpcContracts.METHOD, request.method());
                assertEquals(2, command.expectedRevision());
                assertEquals(provider, payload.provider());
                assertEquals(ProviderModelPurpose.CHAT, payload.purpose());
                return JsonRpcResponse.success(request.id(), JSON.encode(expected));
            });

            assertEquals(
                    expected,
                    client.verifyRoundTrip(
                            provider,
                            ProviderModelPurpose.CHAT,
                            true,
                            ProviderVerificationRpcContracts.BILLING_CONFIRMATION,
                            new CommandOptions("verify", 2)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.verifyRoundTrip(
                            provider,
                            ProviderModelPurpose.CHAT,
                            false,
                            ProviderVerificationRpcContracts.BILLING_CONFIRMATION,
                            new CommandOptions("unconfirmed", 2)));
            assertEquals(1, requests.get());
        }
    }

    @Test
    void SDK暴露禁用连接创建目录发现和精确Embedding绑定() {
        ProviderEndpoint disabled =
                new ProviderEndpoint("provider-main", 1, ProviderLifecycle.DISABLED, emptyProviderSpec(), NOW, NOW);
        ProviderModelDiscoveryResult discovery = new ProviderModelDiscoveryResult(
                "provider-main",
                1,
                List.of(new ProviderModelDiscoveryCandidate("chat", "Chat", Set.of(), OptionalInt.empty())),
                false,
                NOW);
        EmbeddingBinding binding = new EmbeddingBinding(new ProviderRef("provider-main", 2, "embedding"), 1, NOW);

        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            ProviderClient client = client(secrets, request -> switch (request.method()) {
                case "provider/create" -> {
                    WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                    var payload = JSON.decode(
                            command.payload(), com.javaclaw.protocol.ProviderRpcContracts.ProviderCreatePayload.class);
                    assertEquals(ProviderLifecycle.DISABLED, payload.lifecycle());
                    yield JsonRpcResponse.success(request.id(), JSON.encode(disabled));
                }
                case ProviderModelDiscoveryRpcContracts.START_METHOD -> {
                    WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                    assertEquals(
                            new ProviderModelDiscoveryRequest("provider-main", 1),
                            JSON.decode(command.payload(), ProviderModelDiscoveryRequest.class));
                    assertEquals(1, command.expectedRevision());
                    yield JsonRpcResponse.success(
                            request.id(),
                            JSON.encode(new ProviderModelDiscoveryOperation(
                                    "11111111-1111-1111-1111-111111111111",
                                    2,
                                    "provider-main",
                                    1,
                                    ProviderModelDiscoveryOperationState.SUCCEEDED,
                                    Optional.of(discovery),
                                    Optional.empty(),
                                    NOW,
                                    NOW)));
                }
                case "provider/embeddingBinding/read" ->
                    JsonRpcResponse.success(
                            request.id(),
                            JSON.encode(new com.javaclaw.protocol.ProviderRpcContracts.EmbeddingBindingReadResult(
                                    Optional.of(binding))));
                case "provider/embeddingBinding/update" -> JsonRpcResponse.success(request.id(), JSON.encode(binding));
                default -> throw new AssertionError("unexpected method " + request.method());
            });

            assertEquals(
                    disabled,
                    client.create(
                            disabled.id(),
                            disabled.spec(),
                            ProviderLifecycle.DISABLED,
                            new CommandOptions("create-shell", 0)));
            assertEquals(discovery, client.discoverModels("provider-main", 1, new CancellationSource()));
            assertEquals(Optional.of(binding), client.embeddingBinding());
            assertEquals(binding, client.bindEmbedding(binding.provider(), new CommandOptions("bind-embedding", 0)));
        }
    }

    @Test
    void SDK取消令牌会发送Cancel命令而不是只丢弃响应() {
        CancellationSource cancellation = new CancellationSource();
        AtomicInteger cancels = new AtomicInteger();
        Instant createdAt = NOW;
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            ProviderClient client = client(secrets, request -> {
                if (ProviderModelDiscoveryRpcContracts.START_METHOD.equals(request.method())) {
                    cancellation.cancel("页面切换");
                    return JsonRpcResponse.success(
                            request.id(),
                            JSON.encode(new ProviderModelDiscoveryOperation(
                                    "11111111-1111-1111-1111-111111111111",
                                    1,
                                    "provider-main",
                                    1,
                                    ProviderModelDiscoveryOperationState.RUNNING,
                                    Optional.empty(),
                                    Optional.empty(),
                                    createdAt,
                                    createdAt)));
                }
                assertEquals(ProviderModelDiscoveryRpcContracts.CANCEL_METHOD, request.method());
                WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                var payload = JSON.decode(command.payload(), ProviderModelDiscoveryRpcContracts.CancelPayload.class);
                assertEquals("11111111-1111-1111-1111-111111111111", payload.operationId());
                assertEquals(1, command.expectedRevision());
                cancels.incrementAndGet();
                return JsonRpcResponse.success(
                        request.id(),
                        JSON.encode(new ProviderModelDiscoveryOperation(
                                payload.operationId(),
                                2,
                                "provider-main",
                                1,
                                ProviderModelDiscoveryOperationState.CANCELLED,
                                Optional.empty(),
                                Optional.empty(),
                                createdAt,
                                createdAt)));
            });

            assertThrows(TurnCancelledException.class, () -> client.discoverModels("provider-main", 1, cancellation));
            assertEquals(1, cancels.get());
        }
    }

    private static JsonRpcResponse response(
            JsonRpcRequest request,
            SessionSecretChannel secrets,
            ProviderCredentialBinding binding,
            ProviderCredentialClearResult cleared,
            AtomicReference<String> unsealed) {
        WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
        return switch (request.method()) {
            case "provider/credential/set" -> setResponse(request, command, secrets, binding, unsealed);
            case "provider/credential/clear" -> clearResponse(request, command, cleared);
            default -> throw new AssertionError("unexpected method " + request.method());
        };
    }

    private static JsonRpcResponse setResponse(
            JsonRpcRequest request,
            WriteCommand command,
            SessionSecretChannel secrets,
            ProviderCredentialBinding binding,
            AtomicReference<String> unsealed) {
        var payload = JSON.decode(command.payload(), ProviderCredentialRpcContracts.SetPayload.class);
        assertEquals(1, command.expectedRevision());
        assertEquals(1, payload.providerExpectedRevision());
        assertEquals(0, payload.credentialExpectedRevision());
        byte[] plaintext = secrets.unseal(payload.secret(), ProviderCredentialRpcContracts.SET_PURPOSE);
        try {
            unsealed.set(new String(plaintext, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
        return JsonRpcResponse.success(request.id(), JSON.encode(binding));
    }

    private static JsonRpcResponse clearResponse(
            JsonRpcRequest request, WriteCommand command, ProviderCredentialClearResult cleared) {
        var payload = JSON.decode(command.payload(), ProviderCredentialRpcContracts.ClearPayload.class);
        assertEquals(2, command.expectedRevision());
        assertEquals(2, payload.providerExpectedRevision());
        assertEquals(1, payload.credentialExpectedRevision());
        assertEquals(cleared.receipt().reference(), payload.credential());
        return JsonRpcResponse.success(request.id(), JSON.encode(cleared));
    }

    private static ProviderClient client(
            SessionSecretChannel secrets, Function<JsonRpcRequest, JsonRpcResponse> responses) {
        RpcClientConnection connection =
                new RpcClientConnection(new ScriptedRpcConnection(responses), JSON, ignored -> {});
        return new ProviderClient(connection, secrets.publicKey());
    }

    private static ProviderEndpoint provider(long revision, Optional<CredentialRef> credential) {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec(
                        "test-model", "Test model", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                credential,
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        return new ProviderEndpoint("provider-main", revision, ProviderLifecycle.ACTIVE, spec, NOW, NOW);
    }

    private static ProviderEndpointSpec emptyProviderSpec() {
        return new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                ProviderAuthentication.API_KEY,
                List.of(),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }
}
