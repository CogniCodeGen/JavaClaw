package com.javaclaw.client;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
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
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.client.facade.PreparedProviderConfiguration;
import com.javaclaw.client.facade.ProviderClient;
import com.javaclaw.client.facade.ProviderConfigurationClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.ProviderModelDiscoveryRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigurationClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");

    @Test
    void 准备不写入且保存与查回执使用同一冻结身份() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<PreparedProviderConfiguration> frozen = new AtomicReference<>();
        try (SessionSecretChannel secrets = SessionSecretChannel.open();
                RpcClientConnection connection = connection(request -> {
                    requests.incrementAndGet();
                    if (request.method().equals(ProviderConfigurationRpcContracts.SAVE_METHOD)) {
                        assertEquals(frozen.get().command(), JSON.decode(request.params(), WriteCommand.class));
                        return success(request, result());
                    }
                    var query = JSON.decode(request.params(), ProviderConfigurationRpcContracts.ResultPayload.class);
                    assertEquals(frozen.get().requestDigest(), query.requestDigest());
                    assertEquals("one-save", query.idempotencyKey());
                    return success(
                            request, new ProviderConfigurationRpcContracts.ResultResponse(Optional.of(result())));
                })) {
            ProviderConfigurationClient client = new ProviderClient(connection, secrets.publicKey()).configuration();
            frozen.set(client.prepare(
                    configuration(ProviderCredentialChange.KEEP), new char[0], new CommandOptions("one-save", 0)));
            assertEquals(0, requests.get());
            var expectedDigest = JSON.encode(
                            new DigestInput(0, frozen.get().command().payload()))
                    .sha256();
            assertEquals(expectedDigest, frozen.get().requestDigest());
            assertEquals(
                    frozen.get().payload(),
                    JSON.decode(frozen.get().command().payload(), ProviderConfigurationRpcContracts.SavePayload.class));
            assertEquals(result(), client.save(frozen.get()));
            assertEquals(Optional.of(result()), client.result(frozen.get()));
            assertEquals(2, requests.get());
        }
    }

    @Test
    void 未找到回执保持未知且从不另发保存() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (SessionSecretChannel secrets = SessionSecretChannel.open();
                RpcClientConnection connection = connection(request -> {
                    requests.incrementAndGet();
                    assertEquals(ProviderConfigurationRpcContracts.RESULT_METHOD, request.method());
                    return success(request, new ProviderConfigurationRpcContracts.ResultResponse(Optional.empty()));
                })) {
            ProviderConfigurationClient client = new ProviderConfigurationClient(connection, secrets.publicKey());
            var prepared =
                    client.prepare(configuration(ProviderCredentialChange.KEEP), new char[0], CommandOptions.create(0));
            assertTrue(client.result(prepared).isEmpty());
            assertEquals(1, requests.get());
        }
    }

    @Test
    void 预览和保存独立密封且消费输入秘密() throws Exception {
        AtomicReference<com.javaclaw.protocol.SealedSecret> previewSecret = new AtomicReference<>();
        try (SessionSecretChannel secrets = SessionSecretChannel.open();
                RpcClientConnection connection = connection(request -> {
                    var command = JSON.decode(request.params(), WriteCommand.class);
                    var payload =
                            JSON.decode(command.payload(), ProviderConfigurationRpcContracts.PreviewPayload.class);
                    assertEquals(3, command.expectedRevision());
                    previewSecret.set(payload.secret().orElseThrow());
                    assertArrayEquals(
                            "temporary-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            secrets.unseal(previewSecret.get(), ProviderConfigurationRpcContracts.PREVIEW_PURPOSE));
                    return success(request, operation(ProviderModelDiscoveryOperationState.SUCCEEDED));
                })) {
            ProviderConfigurationClient client = new ProviderConfigurationClient(connection, secrets.publicKey());
            char[] preview = "temporary-key".toCharArray();
            assertEquals(
                    previewResult(),
                    client.preview(request(ProviderCredentialChange.REPLACE), preview, new CancellationSource()));
            assertArrayEquals(new char[preview.length], preview);
            char[] saved = "temporary-key".toCharArray();
            var prepared =
                    client.prepare(configuration(ProviderCredentialChange.REPLACE), saved, CommandOptions.create(0));
            assertArrayEquals(new char[saved.length], saved);
            var saveSecret = prepared.payload().secret().orElseThrow();
            assertFalse(saveSecret.equals(previewSecret.get()));
            assertArrayEquals(
                    "temporary-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    secrets.unseal(saveSecret, ProviderConfigurationRpcContracts.SAVE_PURPOSE));
            assertThrows(
                    RuntimeException.class,
                    () -> secrets.unseal(saveSecret, ProviderConfigurationRpcContracts.SAVE_PURPOSE));
        }
    }

    @Test
    void 校验失败和预取消也清零秘密且不发送请求() throws Exception {
        try (SessionSecretChannel secrets = SessionSecretChannel.open();
                RpcClientConnection connection = connection(request -> {
                    throw new AssertionError("不得发送");
                })) {
            var client = new ProviderConfigurationClient(connection, secrets.publicKey());
            char[] wrongIntent = "key".toCharArray();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.prepare(
                            configuration(ProviderCredentialChange.KEEP), wrongIntent, CommandOptions.create(0)));
            assertArrayEquals(new char[3], wrongIntent);
            char[] mismatch = "key".toCharArray();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.prepare(
                            configuration(ProviderCredentialChange.REPLACE), mismatch, CommandOptions.create(1)));
            assertArrayEquals(new char[3], mismatch);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.prepare(
                            configuration(ProviderCredentialChange.REPLACE), new char[0], CommandOptions.create(0)));
            CancellationSource cancelled = new CancellationSource();
            cancelled.cancel("closed");
            char[] input = "key".toCharArray();
            assertThrows(
                    TurnCancelledException.class,
                    () -> client.preview(request(ProviderCredentialChange.REPLACE), input, cancelled));
            assertArrayEquals(new char[3], input);
        }
    }

    @Test
    void 运行中预览轮询到成功保持草稿代次() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (SessionSecretChannel secrets = SessionSecretChannel.open();
                RpcClientConnection connection = connection(request -> {
                    if (request.method().equals(ProviderConfigurationRpcContracts.PREVIEW_START_METHOD)) {
                        return success(request, operation(ProviderModelDiscoveryOperationState.RUNNING));
                    }
                    reads.incrementAndGet();
                    assertEquals(ProviderConfigurationRpcContracts.PREVIEW_READ_METHOD, request.method());
                    assertEquals(
                            "preview-1",
                            JSON.decode(request.params(), ProviderModelDiscoveryRpcContracts.ReadPayload.class)
                                    .operationId());
                    return success(request, operation(ProviderModelDiscoveryOperationState.SUCCEEDED));
                })) {
            var client = new ProviderConfigurationClient(connection, secrets.publicKey());
            assertEquals(
                    previewResult(),
                    client.preview(request(ProviderCredentialChange.KEEP), new char[0], new CancellationSource()));
            assertEquals(1, reads.get());
        }
    }

    @Test
    void 预览错误只返回分类且远端取消明确传播() throws Exception {
        for (var state :
                List.of(ProviderModelDiscoveryOperationState.FAILED, ProviderModelDiscoveryOperationState.CANCELLED)) {
            try (SessionSecretChannel secrets = SessionSecretChannel.open();
                    RpcClientConnection connection = connection(request -> success(request, operation(state)))) {
                var client = new ProviderConfigurationClient(connection, secrets.publicKey());
                RuntimeException failure = assertThrows(
                        RuntimeException.class,
                        () -> client.preview(
                                request(ProviderCredentialChange.KEEP), new char[0], new CancellationSource()));
                if (state == ProviderModelDiscoveryOperationState.FAILED) {
                    assertEquals("AUTHENTICATION_FAILED", failure.getMessage());
                } else {
                    assertTrue(failure instanceof TurnCancelledException);
                }
            }
        }
    }

    @Test
    void 运行中取消发送原操作版本且取消RPC失败不吞本地取消() throws Exception {
        CancellationSource cancellation = new CancellationSource();
        AtomicInteger cancels = new AtomicInteger();
        try (SessionSecretChannel secrets = SessionSecretChannel.open();
                RpcClientConnection connection = connection(request -> {
                    if (request.method().equals(ProviderConfigurationRpcContracts.PREVIEW_START_METHOD)) {
                        cancellation.cancel("closed");
                        return success(request, operation(ProviderModelDiscoveryOperationState.RUNNING));
                    }
                    cancels.incrementAndGet();
                    assertEquals(ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD, request.method());
                    WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                    assertEquals(1, command.expectedRevision());
                    assertEquals(
                            ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED,
                            JSON.decode(command.payload(), ProviderModelDiscoveryRpcContracts.CancelPayload.class)
                                    .reason());
                    throw new IllegalStateException("connection closed");
                })) {
            var client = new ProviderConfigurationClient(connection, secrets.publicKey());
            assertThrows(
                    TurnCancelledException.class,
                    () -> client.preview(request(ProviderCredentialChange.KEEP), new char[0], cancellation));
            assertEquals(1, cancels.get());
        }
    }

    @Test
    void 等待线程中断向服务器取消并恢复中断标记() throws Exception {
        try (SessionSecretChannel secrets = SessionSecretChannel.open();
                RpcClientConnection connection = connection(request -> {
                    if (request.method().equals(ProviderConfigurationRpcContracts.PREVIEW_START_METHOD)) {
                        return success(request, operation(ProviderModelDiscoveryOperationState.RUNNING));
                    }
                    var command = JSON.decode(request.params(), WriteCommand.class);
                    assertEquals(
                            ProviderModelDiscoveryRpcContracts.THREAD_INTERRUPTED,
                            JSON.decode(command.payload(), ProviderModelDiscoveryRpcContracts.CancelPayload.class)
                                    .reason());
                    return success(request, operation(ProviderModelDiscoveryOperationState.CANCELLED));
                })) {
            var client = new ProviderConfigurationClient(connection, secrets.publicKey());
            // 在 CancellationToken 的轮询点设置中断，避免中断初次 RPC 等待而改变测试路径。
            AtomicInteger checks = new AtomicInteger();
            com.javaclaw.api.CancellationToken token = new com.javaclaw.api.CancellationToken() {
                @Override
                public Optional<String> reason() {
                    return Optional.empty();
                }

                @Override
                public boolean isCancelled() {
                    if (checks.incrementAndGet() == 2) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                }
            };
            try {
                assertThrows(
                        TurnCancelledException.class,
                        () -> client.preview(request(ProviderCredentialChange.KEEP), new char[0], token));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static RpcClientConnection connection(Function<JsonRpcRequest, JsonRpcResponse> response) {
        return new RpcClientConnection(new ScriptedRpcConnection(response), JSON, ignored -> {});
    }

    private static JsonRpcResponse success(JsonRpcRequest request, Object value) {
        return JsonRpcResponse.success(request.id(), JSON.encode(value));
    }

    private static ProviderConnectionSpec connectionSpec(ProviderCredentialChange change) {
        return new ProviderConnectionSpec(
                "Service",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("https://models.example/v1")),
                change == ProviderCredentialChange.REPLACE
                        ? ProviderAuthentication.API_KEY
                        : ProviderAuthentication.NONE,
                Duration.ofSeconds(60),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private static ProviderConfiguration configuration(ProviderCredentialChange change) {
        return new ProviderConfiguration(
                "service-1",
                0,
                connectionSpec(change),
                List.of(new ProviderModelSpec(
                        "vector", "Vector", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.of(1024))),
                ProviderLifecycle.ACTIVE,
                change,
                0);
    }

    private static ProviderConfigurationResult result() {
        ProviderConfiguration config = configuration(ProviderCredentialChange.KEEP);
        return new ProviderConfigurationResult(
                new ProviderEndpoint(
                        config.providerId(),
                        1,
                        config.lifecycle(),
                        config.connection().toEndpointSpec(config.models(), Optional.empty()),
                        NOW,
                        NOW),
                Optional.empty());
    }

    private static ProviderModelPreviewRequest request(ProviderCredentialChange change) {
        return new ProviderModelPreviewRequest("draft-1", 3, connectionSpec(change), Optional.empty(), change);
    }

    private static ProviderModelPreviewResult previewResult() {
        return new ProviderModelPreviewResult("draft-1", 3, List.of(), false, NOW);
    }

    private static ProviderModelPreviewOperation operation(ProviderModelDiscoveryOperationState state) {
        return new ProviderModelPreviewOperation(
                "preview-1",
                1,
                "draft-1",
                3,
                state,
                state == ProviderModelDiscoveryOperationState.SUCCEEDED
                        ? Optional.of(previewResult())
                        : Optional.empty(),
                state == ProviderModelDiscoveryOperationState.FAILED
                        ? Optional.of("AUTHENTICATION_FAILED")
                        : Optional.empty(),
                NOW,
                NOW);
    }

    private record DigestInput(long expectedRevision, CanonicalPayload payload) {}
}
