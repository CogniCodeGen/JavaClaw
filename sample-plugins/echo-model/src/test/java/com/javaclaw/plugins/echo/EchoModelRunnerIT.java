package com.javaclaw.plugins.echo;

import com.google.gson.*;
import com.javaclaw.plugin.protocol.v2.*;
import com.javaclaw.plugin.sdk.BearerTokens;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 子进程仅使用独立 Runner 发行 JAR，模型夹具不进入宿主或测试启动类路径。 */
class EchoModelRunnerIT {
    @TempDir Path temp;
    private static final Gson JSON = new Gson();
    private static final String TOKEN = "echo-control-token-012345678901234567890123456789";
    private static final String HOST_TOKEN = "echo-callback-token-012345678901234567890123456789";

    @Test void secondModelUsesGenericProtocolForInspectionLifecycleChatStreamingAndEmbedding() throws Exception {
        assertFalse(Files.exists(Path.of("fixtures/minimal/config.json")));
        try (var session = new Session()) {
            var metadata = session.control.initialize(session.initialize()).getContributions(0);
            assertEquals(CapabilityKind.MODEL, metadata.getKind());
            assertEquals("inference", metadata.getId());
            assertEquals("deterministic-echo-fixture", JsonParser.parseString(metadata.getMetadataJson())
                    .getAsJsonObject().get("engine").getAsString());
            JsonObject inspected = session.result("model.inspect", Map.of("modelPath", session.modelPath));
            assertEquals("echo", inspected.get("modelType").getAsString());
            assertEquals(4096, inspected.get("declaredContextLength").getAsInt());
            assertEquals("ECHO_JSON", session.result("asset.inspect", Map.of("modelPath", session.modelPath)).get("format").getAsString());
            assertEquals(4096, session.result("recommend", Map.of("model", inspected)).get("contextLength").getAsInt());
            assertEquals(1, session.result("catalog.search", Map.of("query", "echo")).getAsJsonArray("models").size());
            assertTrue(session.result("catalog.detail", Map.of("repository", "javaclaw/echo-fixture"))
                    .getAsJsonObject("summary").get("gated").getAsBoolean());
            var probe = session.result("probe", Map.of("profileId", "draft", "startup", session.startup("GENERATION")))
                    .getAsJsonObject("model");
            assertFalse(probe.get("loaded").getAsBoolean());
            assertEquals("deterministic-fixture", probe.get("selectedBackend").getAsString());
            session.result("load", Map.of("profileId", "chat", "startup", session.startup("GENERATION")));
            session.result("load", Map.of("profileId", "embedding", "startup", session.startup("EMBEDDING")));
            assertEquals(2, session.result("list", Map.of()).getAsJsonArray("models").size());
            session.result("models.sync", Map.of("profiles", List.of(), "aliases", Map.of()));
            JsonObject chat = session.result("chat", chat("chat", "chat-1", false, Map.of()));
            assertEquals("验证：你好🌟", chat.get("content").getAsString());
            assertEquals(3, chat.getAsJsonObject("usage").get("promptTokens").getAsInt());
            List<JsonObject> stream = session.data("chat", chat("chat", "chat-2", true, Map.of()));
            String deltas = stream.stream().filter(value -> value.has("type") && value.get("type").getAsString().equals("DELTA"))
                    .map(value -> value.get("text").getAsString()).reduce("", String::concat);
            assertEquals(chat.get("content").getAsString(), deltas);
            assertEquals(chat.get("content"), stream.getLast().get("content"));
            assertTrue(stream.stream().anyMatch(value -> value.has("type") && value.get("type").getAsString().equals("COMPLETE")));
            var embedding = session.result("embedding", Map.of("profileId", "embedding", "request",
                    Map.of("requestId", "embedding-1", "input", List.of("你好", "你好", "世界"))));
            var vectors = embedding.getAsJsonArray("embeddings");
            assertEquals(8, embedding.get("dimensions").getAsInt());
            assertEquals(8, vectors.get(0).getAsJsonArray().size());
            assertEquals(vectors.get(0), vectors.get(1));
            assertNotEquals(vectors.get(0), vectors.get(2));
            session.result("unload", Map.of("profileId", "chat"));
            assertEquals(1, session.result("list", Map.of()).getAsJsonArray("models").size());
        }
    }

    @Test void cancellationTerminatesARealStreamingRequestWithoutReplayingIt() throws Exception {
        try (var session = new Session()) {
            session.control.initialize(session.initialize());
            session.result("load", Map.of("profileId", "chat", "startup", session.startup("GENERATION")));
            CallRequest request = session.request("chat", chat("chat", "cancel-me", true, Map.of("delayMillis", 1000)));
            CountDownLatch first = new CountDownLatch(1), terminal = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            InferenceServiceGrpc.newStub(session.channel).withInterceptors(BearerTokens.client(TOKEN)).invoke(request,
                    new StreamObserver<>() {
                        public void onNext(RpcEvent event) { first.countDown(); }
                        public void onError(Throwable error) { failure.set(error); terminal.countDown(); }
                        public void onCompleted() { terminal.countDown(); }
                    });
            assertTrue(first.await(5, TimeUnit.SECONDS));
            session.control.cancel(CancelRequest.newBuilder().setIdentity(session.identity)
                    .setRequestId(request.getContext().getRequestId()).build());
            assertTrue(terminal.await(5, TimeUnit.SECONDS));
            assertEquals(Status.Code.CANCELLED, Status.fromThrowable(failure.get()).getCode());
            assertTrue(session.control.drain(DrainRequest.newBuilder().setIdentity(session.identity)
                    .setTimeoutMillis(5000).build()).getDrained());
            var replay = InferenceServiceGrpc.newBlockingStub(session.channel).withInterceptors(BearerTokens.client(TOKEN));
            assertThrows(io.grpc.StatusRuntimeException.class, () -> replay.invoke(request).hasNext());
        }
    }

    private Map<String, Object> chat(String profile, String id, boolean stream, Map<String, Object> parameters) {
        return Map.of("profileId", profile, "request", Map.of("requestId", id,
                "messages", List.of(Map.of("role", "user", "content", "你好🌟")), "tools", List.of(),
                "parameters", parameters, "toolChoice", Map.of("mode", "NONE"), "parallelToolCalls", false, "stream", stream));
    }

    private final class Session implements AutoCloseable {
        private final PluginIdentity identity = PluginIdentity.newBuilder().setPluginId("echo-model")
                .setWorkspaceId("fixture-workspace").setArtifactHash("a".repeat(64)).setConfigurationRevision(1)
                .setInstanceId(UUID.randomUUID().toString()).build();
        private final String modelPath = Path.of("fixtures/minimal").toAbsolutePath().normalize().toString();
        private final Process process;
        private final ManagedChannel channel;
        private final PluginControlGrpc.PluginControlBlockingStub control;

        private Session() throws Exception {
            Path runner = Path.of(System.getProperty("plugin.runner.jar")).toAbsolutePath().normalize();
            Path artifact = Path.of("target/plugin.jar").toAbsolutePath().normalize();
            assertTrue(Files.isRegularFile(runner), "请先 package plugin-runner-java 生成独立 Runner");
            assertTrue(Files.isRegularFile(artifact));
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-jar", runner.toString());
            builder.redirectError(temp.resolve("runner-" + identity.getInstanceId() + ".log").toFile());
            var env = builder.environment();
            env.put("JAVACLAW_PLUGIN_HOST_PORT", "65534");
            env.put("JAVACLAW_PLUGIN_TOKEN", TOKEN); env.put("JAVACLAW_HOST_TOKEN", HOST_TOKEN);
            env.put("JAVACLAW_PLUGIN_ID", identity.getPluginId()); env.put("JAVACLAW_PLUGIN_HASH", identity.getArtifactHash());
            env.put("JAVACLAW_PLUGIN_REVISION", "1"); env.put("JAVACLAW_WORKSPACE_ID", identity.getWorkspaceId());
            env.put("JAVACLAW_PLUGIN_INSTANCE_ID", identity.getInstanceId()); env.put("JAVACLAW_PLUGIN_ARTIFACT", artifact.toString());
            env.put("JAVACLAW_PLUGIN_ARTIFACT_FILE_HASH", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
            env.put("JAVACLAW_PLUGIN_MAIN", "com.javaclaw.plugins.echo.EchoModelPlugin");
            env.put("JAVACLAW_PLUGIN_DATA_DIRECTORY", temp.resolve(identity.getInstanceId()).toString());
            env.put("JAVACLAW_PLUGIN_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
            process = builder.start();
            try {
                String line;
                var reader = process.inputReader();
                var readers = Executors.newVirtualThreadPerTaskExecutor();
                try { line = readers.submit(reader::readLine).get(15, TimeUnit.SECONDS); }
                finally { readers.shutdownNow(); }
                assertNotNull(line);
                assertTrue(line.startsWith("JAVACLAW_PLUGIN_V2_READY "), line);
                int port = JsonParser.parseString(line.substring(line.indexOf('{'))).getAsJsonObject().get("port").getAsInt();
                channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().disableRetry().build();
                control = PluginControlGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN)).withDeadlineAfter(15, TimeUnit.SECONDS);
                assertEquals("STARTING", control.health(identity).getState());
            } catch (Throwable failure) { process.destroyForcibly(); throw failure; }
        }
        private InitializeRequest initialize() throws Exception {
            return InitializeRequest.newBuilder().setIdentity(identity).setProtocolMajor(2).setProtocolMinor(0)
                    .setManifestJson(Files.readString(Path.of("plugin.json"))).setConfigurationJson("{\"prefix\":\"验证：\"}").build();
        }
        private Map<String, Object> startup(String kind) {
            return Map.of("runtimeId", "echo-model", "modelPath", modelPath, "modelKind", kind, "modelName", "验证模型",
                    "contextLength", 1024, "embeddingDimensions", 8, "loadParameters", Map.of(), "defaultParameters", Map.of(), "probeMode", false);
        }
        private CallRequest request(String operation, Object payload) {
            return CallRequest.newBuilder().setContributionId("inference").setOperation(operation).setPayloadJson(JSON.toJson(payload))
                    .setContext(CallContext.newBuilder().setIdentity(identity).setRequestId(UUID.randomUUID().toString())
                            .setRunId("contract-run").setDeadlineEpochMillis(System.currentTimeMillis() + 15_000)).build();
        }
        private List<JsonObject> data(String operation, Object payload) {
            var events = InferenceServiceGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN))
                    .withDeadlineAfter(10, TimeUnit.SECONDS).invoke(request(operation, payload));
            var result = new ArrayList<JsonObject>();
            EventType last = EventType.DATA;
            while (events.hasNext()) {
                RpcEvent event = events.next(); last = event.getEventType();
                assertNotEquals(EventType.ERROR, last);
                if (last == EventType.DATA) result.add(JsonParser.parseString(event.getPayloadJson()).getAsJsonObject());
            }
            assertEquals(EventType.COMPLETE, last);
            assertFalse(result.isEmpty());
            return result;
        }
        private JsonObject result(String operation, Object payload) { return data(operation, payload).getLast(); }
        @Override public void close() throws Exception {
            try {
                if (process.isAlive()) control.withDeadlineAfter(3, TimeUnit.SECONDS).shutdown(identity);
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            } finally {
                channel.shutdownNow(); channel.awaitTermination(5, TimeUnit.SECONDS);
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }
}
