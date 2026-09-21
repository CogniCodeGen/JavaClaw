package com.javaclaw.model;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.TurnCancelledException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelPreviewAdapterTest {
    @Test
    void 临时材料直接读取草稿目录且不调用Vault解析器() throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.json(
                        "{\"object\":\"list\",\"data\":[{\"id\":\"preview-model\",\"object\":\"model\","
                                + "\"created\":0,\"owned_by\":\"test\"}]}");
                CredentialMaterial material = new CredentialMaterial("temporary".toCharArray())) {
            var result = adapter()
                    .preview(
                            "draft",
                            7,
                            connection(
                                    ProviderAdapter.OPENAI_COMPATIBLE,
                                    server.baseUri(),
                                    ProviderAuthentication.API_KEY),
                            Optional.of(material),
                            new CancellationSource());
            assertEquals("draft", result.draftId());
            assertEquals(7, result.generation());
            assertEquals("preview-model", result.candidates().getFirst().modelId());
            assertFalse(result.truncated());
            assertTrue(server.authorizationPresent(0));
            assertArrayEquals("temporary".toCharArray(), material.copy());
        }
    }

    @Test
    void 无鉴权草稿不发送Authorization且错误的材料形状在请求前拒绝() throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.json("{\"object\":\"list\",\"data\":[]}");
                CredentialMaterial material = new CredentialMaterial("temporary".toCharArray())) {
            var connection =
                    connection(ProviderAdapter.OPENAI_COMPATIBLE, server.baseUri(), ProviderAuthentication.NONE);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> adapter().preview("draft", 1, connection, Optional.of(material), new CancellationSource()));
            var result = adapter().preview("draft", 1, connection, Optional.empty(), new CancellationSource());
            assertTrue(result.candidates().isEmpty());
            assertFalse(server.authorizationPresent(0));
            var apiKey =
                    connection(ProviderAdapter.OPENAI_COMPATIBLE, server.baseUri(), ProviderAuthentication.API_KEY);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> adapter().preview("draft", 1, apiKey, Optional.empty(), new CancellationSource()));
        }
    }

    @Test
    void 四种厂商路径均返回稳定Http分类且不带敏感正文() throws Exception {
        Map<Integer, ProviderModelPreviewException.Code> cases = Map.of(
                401, ProviderModelPreviewException.Code.AUTHENTICATION_FAILED,
                403, ProviderModelPreviewException.Code.AUTHENTICATION_FAILED,
                400, ProviderModelPreviewException.Code.INVALID_ADDRESS,
                404, ProviderModelPreviewException.Code.CATALOG_UNSUPPORTED,
                405, ProviderModelPreviewException.Code.CATALOG_UNSUPPORTED,
                501, ProviderModelPreviewException.Code.CATALOG_UNSUPPORTED,
                408, ProviderModelPreviewException.Code.TIMEOUT,
                504, ProviderModelPreviewException.Code.TIMEOUT);
        for (ProviderAdapter adapter : ProviderAdapter.values()) {
            for (var expected : cases.entrySet()) {
                try (LocalDiscoveryServer server = LocalDiscoveryServer.failure(expected.getKey());
                        CredentialMaterial material = new CredentialMaterial("temporary".toCharArray())) {
                    var connection = connection(adapter, server.baseUri(), ProviderAuthentication.API_KEY);
                    var failure = assertThrows(
                            ProviderModelPreviewException.class,
                            () -> adapter()
                                    .preview("draft", 1, connection, Optional.of(material), new CancellationSource()));
                    assertEquals(expected.getValue(), failure.code());
                    assertEquals(expected.getValue().name(), failure.getMessage());
                    assertNull(failure.getCause());
                }
            }
        }
    }

    @Test
    void 超时网络未知与重定向故障均不输出原始消息() throws Exception {
        assertEquals(
                ProviderModelPreviewException.Code.TIMEOUT,
                ProviderModelPreviewException.classify(
                                new IllegalStateException("secret", new SocketTimeoutException()))
                        .code());
        assertEquals(
                ProviderModelPreviewException.Code.NETWORK_ERROR,
                ProviderModelPreviewException.classify(new IllegalStateException("secret", new IOException()))
                        .code());
        assertEquals(
                ProviderModelPreviewException.Code.PREVIEW_FAILED,
                ProviderModelPreviewException.classify(new IllegalStateException("secret"))
                        .code());
        try (LocalDiscoveryServer target = LocalDiscoveryServer.json("{}");
                LocalDiscoveryServer server = LocalDiscoveryServer.redirect(target.baseUri());
                CredentialMaterial material = new CredentialMaterial("temporary".toCharArray())) {
            var failure = assertThrows(
                    ProviderModelPreviewException.class,
                    () -> adapter()
                            .preview(
                                    "draft",
                                    1,
                                    connection(
                                            ProviderAdapter.OPENAI_COMPATIBLE,
                                            server.baseUri(),
                                            ProviderAuthentication.API_KEY),
                                    Optional.of(material),
                                    new CancellationSource()));
            assertEquals(ProviderModelPreviewException.Code.INVALID_ADDRESS, failure.code());
            assertEquals(0, target.requests());
        }
    }

    @Test
    void 草稿取消立即关闭实际Http连接并保持取消语义() throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.blocking();
                CredentialMaterial material = new CredentialMaterial("temporary".toCharArray());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancellation = new CancellationSource();
            var task = executor.submit(() -> adapter()
                    .preview(
                            "draft",
                            1,
                            connection(
                                    ProviderAdapter.OPENAI_COMPATIBLE,
                                    server.baseUri(),
                                    ProviderAuthentication.API_KEY),
                            Optional.of(material),
                            cancellation));
            assertTrue(server.awaitRequest());
            cancellation.cancel("CLIENT_CANCELLED");
            var failure =
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TurnCancelledException);
            assertTrue(server.awaitPeerClosed());
        }
    }

    @Test
    void 草稿总超时关闭阻塞请求并返回Timeout分类() throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.blocking();
                CredentialMaterial material = new CredentialMaterial("temporary".toCharArray())) {
            var connection = new ProviderConnectionSpec(
                    "Preview",
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    Optional.of(server.baseUri()),
                    ProviderAuthentication.API_KEY,
                    Duration.ofSeconds(1),
                    0,
                    ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
            var failure = assertThrows(
                    ProviderModelPreviewException.class,
                    () -> adapter().preview("draft", 1, connection, Optional.of(material), new CancellationSource()));
            assertEquals(ProviderModelPreviewException.Code.TIMEOUT, failure.code());
            assertTrue(server.awaitPeerClosed());
        }
    }

    private static ProviderModelDiscoveryAdapter adapter() {
        return new ProviderModelDiscoveryAdapter(
                reference -> {
                    throw new AssertionError("草稿不能解析任意 CredentialRef");
                },
                Clock.systemUTC());
    }

    private static ProviderConnectionSpec connection(
            ProviderAdapter adapter, URI baseUri, ProviderAuthentication authentication) {
        URI root = adapter == ProviderAdapter.GOOGLE_GENAI ? baseUri.resolve("/") : baseUri;
        return new ProviderConnectionSpec(
                "Preview",
                adapter,
                Optional.of(root),
                authentication,
                Duration.ofSeconds(2),
                0,
                ProviderAdapterOptions.defaults(adapter));
    }
}
