package com.javaclaw.server.rpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderModelPreviewOperation;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelPreviewSessionCancellationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 关闭草稿RpcSession取消实际Http且不创建Provider() throws Exception {
        try (BlockingHttpServer server = new BlockingHttpServer();
                AppServerBootstrap.Components components = AppServerBootstrap.create(
                        temporaryDirectory.resolve("data-v6"), Clock.systemUTC(), new NoOpModel())) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            ProviderModelPreviewRequest draft = draft(server.baseUri());
            WriteCommand start = new WriteCommand(
                    "preview-start",
                    draft.generation(),
                    components
                            .json()
                            .encode(new ProviderConfigurationRpcContracts.PreviewPayload(draft, Optional.empty())));

            ProviderModelPreviewOperation operation = components
                    .json()
                    .decode(
                            session.handle(request(
                                            "start", ProviderConfigurationRpcContracts.PREVIEW_START_METHOD, start))
                                    .result()
                                    .orElseThrow(),
                            ProviderModelPreviewOperation.class);
            assertEquals(draft.draftId(), operation.draftId());
            assertTrue(server.awaitRequest());

            var providers = components
                    .json()
                    .decode(
                            session.handle(request("list", "provider/list", Map.of()))
                                    .result()
                                    .orElseThrow(),
                            com.javaclaw.protocol.ProviderRpcContracts.ProviderListResult.class);
            assertTrue(providers.providers().isEmpty());
            session.close();
            assertTrue(server.awaitPeerClosed());
        }
    }

    private static void initialize(AppServerSession session, AppServerBootstrap.Components components) {
        InitializeParams params = new InitializeParams(
                3,
                new ClientInfo("test", "6"),
                new CapabilityAdvertisement(Set.of(ProviderConfigurationRpcContracts.CAPABILITY), Set.of()));
        assertTrue(session.handle(request("initialize", "initialize/session", params, components))
                .result()
                .isPresent());
    }

    private static ProviderModelPreviewRequest draft(URI baseUri) {
        ProviderConnectionSpec connection = new ProviderConnectionSpec(
                "Blocking",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(baseUri),
                ProviderAuthentication.NONE,
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        return new ProviderModelPreviewRequest(
                "blocking-draft", 1, connection, Optional.empty(), ProviderCredentialChange.CLEAR);
    }

    private static JsonRpcRequest request(String id, String method, Object params) {
        return new JsonRpcRequest(new RpcId(id), method, new com.javaclaw.protocol.CanonicalJson().encode(params));
    }

    private static JsonRpcRequest request(
            String id, String method, Object params, AppServerBootstrap.Components components) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(params));
    }

    private static final class BlockingHttpServer implements AutoCloseable {
        private final ServerSocket server;
        private final CountDownLatch request = new CountDownLatch(1);
        private final AtomicBoolean peerClosed = new AtomicBoolean();
        private final Thread worker;
        private volatile Socket connection;

        private BlockingHttpServer() throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            worker = Thread.ofVirtual().start(this::serve);
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/v1");
        }

        boolean awaitRequest() throws InterruptedException {
            return request.await(2, TimeUnit.SECONDS);
        }

        boolean awaitPeerClosed() throws InterruptedException {
            worker.join(TimeUnit.SECONDS.toMillis(2));
            return peerClosed.get();
        }

        private void serve() {
            try (Socket accepted = server.accept()) {
                connection = accepted;
                readHeaders(accepted);
                request.countDown();
                accepted.setSoTimeout(Math.toIntExact(TimeUnit.SECONDS.toMillis(2)));
                peerClosed.set(accepted.getInputStream().read() < 0);
            } catch (IOException ignored) {
                // 断言通过 latch 和 peerClosed 给出；close 只负责测试清理。
            }
        }

        private static void readHeaders(Socket socket) throws IOException {
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            int value;
            while ((value = socket.getInputStream().read()) >= 0) {
                received.write(value);
                byte[] bytes = received.toByteArray();
                int length = bytes.length;
                if (length >= 4
                        && bytes[length - 4] == '\r'
                        && bytes[length - 3] == '\n'
                        && bytes[length - 2] == '\r'
                        && bytes[length - 1] == '\n') {
                    return;
                }
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            if (connection != null) {
                connection.close();
            }
            worker.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    private static final class NoOpModel implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            return new ModelInvocationResult(
                    "", List.of(), ModelUsage.zero(), Optional.empty(), Optional.empty(), ModelFinishReason.COMPLETE);
        }
    }
}
