package com.javaclaw.desktop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.sdk.JavaClawClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSubmissionIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    @Timeout(15)
    void switchingThreadsDoesNotSilentlyRejectAnIndependentAttachmentSubmission() throws Exception {
        Path firstFile = Files.writeString(temporary.resolve("first.txt"), "first");
        Path secondFile = Files.writeString(temporary.resolve("second.txt"), "second");
        try (var server = new SubmissionServer(2, false, "");
                JavaClawClient client = JavaClawClient.connect(server.clientInput(), server.clientOutput());
                DesktopViewModel model = new DesktopViewModel(client, Runnable::run, true)) {
            client.initialize("submission-test", "4").get(2, TimeUnit.SECONDS);
            model.initialize();
            await(() -> model.profiles().size() == 1 && model.threads().size() == 2);
            server.assertHealthy();
            assertTrue(
                    model.profiles().size() == 1 && model.threads().size() == 2,
                    "initialization failed: " + model.errorProperty().get());
            var profile = model.profiles().getFirst();
            var first = model.threads().stream()
                    .filter(value -> value.id().equals("thread-1"))
                    .findFirst()
                    .orElseThrow();
            var second = model.threads().stream()
                    .filter(value -> value.id().equals("thread-2"))
                    .findFirst()
                    .orElseThrow();
            var accepted = new CountDownLatch(2);

            model.selectThread(first);
            model.submitMessage(
                    "first request", java.util.List.of(firstFile), profile, ignored -> accepted.countDown());
            model.selectThread(second);
            model.submitMessage(
                    "second request", java.util.List.of(secondFile), profile, ignored -> accepted.countDown());

            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(2, server.turnStarts.get());
            assertEquals(2, server.uploadStarts.get());
            assertEquals(
                    java.util.List.of(2, 2),
                    server.turnInputCounts.values().stream().sorted().toList());
            assertTrue(
                    model.errorProperty().get().isBlank(), model.errorProperty().get());
            server.assertHealthy();
        }
    }

    @Test
    @Timeout(15)
    void cancellingAnUploadNeverStartsATurnAndReleasesTheCompletedAttachment() throws Exception {
        Path file = Files.writeString(temporary.resolve("cancel.txt"), "cancel");
        try (var server = new SubmissionServer(1, true, "");
                JavaClawClient client = JavaClawClient.connect(server.clientInput(), server.clientOutput());
                DesktopViewModel model = new DesktopViewModel(client, Runnable::run, true)) {
            client.initialize("submission-test", "4").get(2, TimeUnit.SECONDS);
            model.initialize();
            await(() -> model.profiles().size() == 1 && model.threads().size() == 2);
            var profile = model.profiles().getFirst();
            var first = model.threads().getFirst();
            var accepted = new CountDownLatch(1);

            model.selectThread(first);
            model.submitMessage("cancel request", java.util.List.of(file), profile, ignored -> accepted.countDown());
            assertTrue(server.uploadRequestsStarted.await(2, TimeUnit.SECONDS));
            model.submitMessage("duplicate request", java.util.List.of(file), profile, ignored -> accepted.countDown());
            assertEquals(1, server.uploadStarts.get());
            model.cancelSubmission();
            server.allowUploadResponses.countDown();

            await(() -> server.releaseStarts.get() == 1
                    && model.composerActivityProperty().get().phase() == ComposerActivity.Phase.IDLE);
            assertEquals(0, server.turnStarts.get());
            assertEquals(1, server.releaseStarts.get());
            assertFalse(accepted.await(50, TimeUnit.MILLISECONDS));
            server.assertHealthy();
        }
    }

    @Test
    @Timeout(15)
    void oneFailedAttachmentPreventsTheWholeTurnAndReleasesSuccessfulUploads() throws Exception {
        Path first = Files.writeString(temporary.resolve("good.txt"), "good");
        Path second = Files.writeString(temporary.resolve("broken.txt"), "broken");
        try (var server = new SubmissionServer(2, false, "broken.txt");
                JavaClawClient client = JavaClawClient.connect(server.clientInput(), server.clientOutput());
                DesktopViewModel model = new DesktopViewModel(client, Runnable::run, true)) {
            client.initialize("submission-test", "4").get(2, TimeUnit.SECONDS);
            model.initialize();
            await(() -> model.profiles().size() == 1 && model.threads().size() == 2);
            var accepted = new CountDownLatch(1);

            model.selectThread(model.threads().getFirst());
            model.submitMessage(
                    "failure request",
                    java.util.List.of(first, second),
                    model.profiles().getFirst(),
                    ignored -> accepted.countDown());

            await(() -> server.releaseStarts.get() == 1
                    && model.composerActivityProperty().get().phase() == ComposerActivity.Phase.IDLE);
            assertEquals(2, server.uploadStarts.get());
            assertEquals(0, server.turnStarts.get());
            assertEquals(1, server.releaseStarts.get());
            assertFalse(accepted.await(50, TimeUnit.MILLISECONDS));
            assertTrue(model.errorProperty().get().contains("synthetic upload failure"));
            server.assertHealthy();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static final class SubmissionServer implements AutoCloseable {
        private static final String NOW = "2026-08-30T00:00:00Z";
        private static final String CLOSED = "\u0000";
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final QueueInputStream clientInput = new QueueInputStream();
        private final java.util.concurrent.BlockingQueue<String> requests =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private final LineQueueOutputStream clientOutput = new LineQueueOutputStream(requests);
        private final Thread reader;
        private final CountDownLatch uploadRequestsStarted;
        private final CountDownLatch allowUploadResponses;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
        private final Map<String, Integer> turnInputCounts = new ConcurrentHashMap<>();
        private final AtomicInteger uploadIds = new AtomicInteger();
        private final AtomicInteger uploadStarts = new AtomicInteger();
        private final AtomicInteger releaseStarts = new AtomicInteger();
        private final AtomicInteger turnStarts = new AtomicInteger();
        private final String rejectedUploadName;

        private SubmissionServer(int expectedUploads, boolean pauseUploadResponses, String rejectedUploadName) {
            uploadRequestsStarted = new CountDownLatch(expectedUploads);
            allowUploadResponses = new CountDownLatch(pauseUploadResponses ? 1 : 0);
            this.rejectedUploadName = rejectedUploadName;
            reader = Thread.startVirtualThread(this::serve);
        }

        private InputStream clientInput() {
            return clientInput;
        }

        private OutputStream clientOutput() {
            return clientOutput;
        }

        private void serve() {
            try {
                while (true) {
                    String request = requests.take();
                    if (CLOSED.equals(request)) {
                        clientInput.close();
                        return;
                    }
                    Thread.startVirtualThread(() -> respond(request));
                }
            } catch (InterruptedException closed) {
                Thread.currentThread().interrupt();
                if (!Thread.currentThread().isInterrupted()) {
                    failure.compareAndSet(null, closed);
                }
            }
        }

        private void respond(String line) {
            long id = -1;
            try {
                JsonNode request = json.readTree(line);
                id = request.path("id").asLong();
                String method = request.path("method").asText();
                if ("initialized".equals(method) && !request.has("id")) {
                    return;
                }
                JsonNode params = request.path("params");
                JsonNode result =
                        switch (method) {
                            case "initialize" -> initialize();
                            case "workspace/list" -> array(workspace());
                            case "profile/list" -> array(profile());
                            case "thread/list" -> array(thread("thread-1"), thread("thread-2"));
                            case "thread/resume" ->
                                resume(params.path("threadId").asText());
                            case "thread/read" ->
                                snapshot(params.path("threadId").asText());
                            case "thread/execution/summary" ->
                                json.createObjectNode()
                                        .put("threadId", params.path("threadId").asText())
                                        .set("turns", json.createArrayNode());
                            case "event/list" -> json.createArrayNode();
                            case "attachment/upload/start" -> startUpload(params);
                            case "attachment/upload/chunk" -> appendChunk(params);
                            case "attachment/upload/complete" -> completeUpload(params);
                            case "attachment/release" -> releaseAttachment();
                            case "turn/start" -> startTurn(params);
                            default -> throw new AssertionError("unexpected SDK call: " + method);
                        };
                writeSuccess(id, result);
            } catch (ExpectedRpcFailure expected) {
                if (id >= 0) {
                    writeFailure(id, expected);
                }
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
                if (id >= 0) {
                    writeFailure(id, problem);
                }
            }
        }

        private JsonNode startUpload(JsonNode params) throws Exception {
            String id = "upload-" + uploadIds.incrementAndGet();
            var upload = new Upload(
                    params.path("sha256").asText(),
                    params.path("mediaType").asText(),
                    params.path("displayName").asText(),
                    params.path("sizeBytes").asLong());
            uploads.put(id, upload);
            uploadStarts.incrementAndGet();
            uploadRequestsStarted.countDown();
            assertTrue(uploadRequestsStarted.await(2, TimeUnit.SECONDS), "预期附件应允许同时进入上传");
            assertTrue(allowUploadResponses.await(2, TimeUnit.SECONDS), "测试应释放附件上传响应");
            return uploadState(id, upload);
        }

        private JsonNode releaseAttachment() {
            releaseStarts.incrementAndGet();
            return json.createObjectNode().put("released", true);
        }

        private JsonNode appendChunk(JsonNode params) {
            Upload upload = uploads.get(params.path("uploadId").asText());
            upload.received.addAndGet(
                    Base64.getDecoder().decode(params.path("data").asText()).length);
            return uploadState(params.path("uploadId").asText(), upload);
        }

        private JsonNode completeUpload(JsonNode params) {
            Upload upload = uploads.get(params.path("uploadId").asText());
            if (upload.name.equals(rejectedUploadName)) {
                throw new ExpectedRpcFailure("synthetic upload failure");
            }
            return json.createObjectNode()
                    .put("sha256", upload.sha256)
                    .put("mediaType", upload.mediaType)
                    .put("sizeBytes", upload.size)
                    .put("referenceCount", 0);
        }

        private JsonNode startTurn(JsonNode params) {
            int number = turnStarts.incrementAndGet();
            String thread = params.path("threadId").asText();
            turnInputCounts.put(thread, params.path("input").size());
            ObjectNode result = json.createObjectNode()
                    .put("id", "turn-" + number)
                    .put("threadId", thread)
                    .put("status", "RUNNING")
                    .put("attemptId", "attempt-" + number);
            result.set("input", params.path("input").deepCopy());
            result.set("config", json.createObjectNode());
            result.putNull("error");
            result.put("startedAt", NOW);
            result.putNull("completedAt");
            return result;
        }

        private ObjectNode uploadState(String id, Upload upload) {
            return json.createObjectNode()
                    .put("uploadId", id)
                    .put("expectedSha256", upload.sha256)
                    .put("mediaType", upload.mediaType)
                    .put("displayName", upload.name)
                    .put("expectedSize", upload.size)
                    .put("receivedBytes", upload.received.get())
                    .put("chunkSize", 1_048_576)
                    .put("expiresAt", Instant.parse(NOW).plusSeconds(60).toString());
        }

        private ObjectNode resume(String threadId) {
            ObjectNode value = json.createObjectNode();
            value.set("snapshot", snapshot(threadId));
            value.set("events", json.createArrayNode());
            value.set("liveItems", json.createArrayNode());
            return value;
        }

        private ObjectNode snapshot(String threadId) {
            ObjectNode value = json.createObjectNode();
            value.set("thread", thread(threadId));
            value.set("turns", json.createArrayNode());
            value.set("items", json.createArrayNode());
            return value;
        }

        private ObjectNode workspace() {
            return json.createObjectNode()
                    .put("id", "workspace")
                    .put("name", "workspace")
                    .put("root", "/tmp")
                    .put("revision", 1)
                    .put("locked", false)
                    .put("lockReason", "")
                    .put("createdAt", NOW)
                    .put("updatedAt", NOW);
        }

        private ObjectNode initialize() {
            ObjectNode value = json.createObjectNode()
                    .put("protocolVersion", 1)
                    .put("serverName", "submission-test")
                    .put("serverVersion", "4")
                    .put("connectionId", "submission-connection");
            value.set("capabilities", json.createObjectNode());
            return value;
        }

        private ObjectNode profile() {
            ObjectNode value = json.createObjectNode()
                    .put("id", "profile_chat")
                    .put("name", "Chat")
                    .put("kind", "CHAT")
                    .put("provider", "fixture")
                    .put("model", "fixture")
                    .put("systemPrompt", "")
                    .put("requestedSandboxMode", "READ_ONLY")
                    .put("maxIterations", 4)
                    .put("maxModelCalls", 4)
                    .put("revision", 1)
                    .put("updatedAt", NOW);
            value.set("enabledTools", json.createArrayNode());
            value.set("attributes", json.createObjectNode());
            return value;
        }

        private ObjectNode thread(String id) {
            return json.createObjectNode()
                    .put("id", id)
                    .put("workspaceId", "workspace")
                    .putNull("parentThreadId")
                    .putNull("forkedFromTurnId")
                    .put("title", id)
                    .put("cwd", "/tmp")
                    .put("status", "ACTIVE")
                    .put("baseSequence", 0)
                    .put("lastSequence", 0)
                    .put("revision", 1)
                    .put("createdAt", NOW)
                    .put("updatedAt", NOW);
        }

        private ArrayNode array(JsonNode... values) {
            ArrayNode result = json.createArrayNode();
            for (JsonNode value : values) {
                result.add(value);
            }
            return result;
        }

        private void writeSuccess(long id, JsonNode result) throws Exception {
            ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0").put("id", id);
            response.set("result", result);
            write(response);
        }

        private void writeFailure(long id, Throwable problem) {
            try {
                ObjectNode response =
                        json.createObjectNode().put("jsonrpc", "2.0").put("id", id);
                response.set(
                        "error",
                        json.createObjectNode()
                                .put("code", -32603)
                                .put("message", String.valueOf(problem.getMessage())));
                write(response);
            } catch (Exception ignored) {
                // 原始失败由 assertHealthy 报告。
            }
        }

        private void write(JsonNode response) throws Exception {
            clientInput.offer(json.writeValueAsBytes(response));
        }

        private void assertHealthy() {
            if (failure.get() != null) {
                throw new AssertionError("fake server failed", failure.get());
            }
        }

        @Override
        public void close() throws Exception {
            clientOutput.close();
            clientInput.close();
            reader.interrupt();
            reader.join(1_000);
        }

        /** 将 SDK 写出的 JSONL 帧转成消息队列，避免 {@link java.io.PipedInputStream} 将任意一次请求线程结束误判为写端死亡。 */
        private static final class LineQueueOutputStream extends OutputStream {
            private final java.util.concurrent.BlockingQueue<String> lines;
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();
            private boolean closed;

            private LineQueueOutputStream(java.util.concurrent.BlockingQueue<String> lines) {
                this.lines = lines;
            }

            @Override
            public synchronized void write(int value) throws IOException {
                if (closed) {
                    throw new IOException("request stream is closed");
                }
                if (value == '\n') {
                    lines.add(line.toString(StandardCharsets.UTF_8));
                    line.reset();
                } else if (value != '\r') {
                    line.write(value);
                }
            }

            @Override
            public synchronized void close() {
                if (!closed) {
                    closed = true;
                    lines.add(CLOSED);
                }
            }
        }

        /** 向 SDK 的单一读取线程提供可并发投递的完整 JSONL 响应。 */
        private static final class QueueInputStream extends InputStream {
            private static final byte[] END = new byte[0];
            private final java.util.concurrent.BlockingQueue<byte[]> chunks =
                    new java.util.concurrent.LinkedBlockingQueue<>();
            private ByteArrayInputStream current = new ByteArrayInputStream(END);
            private boolean closed;

            private void offer(byte[] response) {
                byte[] framed = java.util.Arrays.copyOf(response, response.length + 1);
                framed[response.length] = '\n';
                chunks.add(framed);
            }

            @Override
            public int read() throws IOException {
                while (true) {
                    int value = current.read();
                    if (value >= 0) {
                        return value;
                    }
                    if (closed) {
                        return -1;
                    }
                    try {
                        byte[] next = chunks.take();
                        if (next == END) {
                            closed = true;
                            return -1;
                        }
                        current = new ByteArrayInputStream(next);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException("response stream interrupted", failure);
                    }
                }
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                int first = read();
                if (first < 0) {
                    return -1;
                }
                target[offset] = (byte) first;
                int remaining = current.read(target, offset + 1, length - 1);
                return remaining < 0 ? 1 : remaining + 1;
            }

            @Override
            public void close() {
                if (!closed) {
                    chunks.add(END);
                }
            }
        }

        private static final class Upload {
            private final String sha256;
            private final String mediaType;
            private final String name;
            private final long size;
            private final AtomicInteger received = new AtomicInteger();

            private Upload(String sha256, String mediaType, String name, long size) {
                this.sha256 = sha256;
                this.mediaType = mediaType;
                this.name = name;
                this.size = size;
            }
        }

        private static final class ExpectedRpcFailure extends RuntimeException {
            private ExpectedRpcFailure(String message) {
                super(message);
            }
        }
    }
}
