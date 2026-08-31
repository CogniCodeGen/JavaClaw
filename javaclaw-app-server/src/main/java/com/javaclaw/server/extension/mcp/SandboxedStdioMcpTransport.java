package com.javaclaw.server.extension.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionFrame;

/** Newline-delimited MCP 2026-07-28 over a process owned by Sandbox Supervisor. */
public final class SandboxedStdioMcpTransport implements McpTransport {
    private static final int MAX_PENDING = 128;
    private static final int MAX_STDERR_BYTES = 64 * 1024;

    private final SandboxSession session;
    private final ObjectMapper json;
    private final McpCodec codec;
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pending> progressPending = new ConcurrentHashMap<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final ThreadPoolExecutor notificationExecutor = new ThreadPoolExecutor(
            1,
            1,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            Thread.ofPlatform().name("javaclaw-mcp-notification-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final Thread reader;

    /** 接管已由 Supervisor 打开的沙箱会话，建立有界 JSONL 读取和脱敏 stderr 缓冲；close 终止会话。 */
    public SandboxedStdioMcpTransport(SandboxSession session, ObjectMapper json, McpCodec codec) {
        this.session = Objects.requireNonNull(session, "session");
        this.json = Objects.requireNonNull(json, "json");
        this.codec = Objects.requireNonNull(codec, "codec");
        reader = Thread.ofVirtual()
                .name("javaclaw-mcp-stdio-reader-" + session.id())
                .start(this::readLoop);
    }

    @Override
    public JsonNode exchange(
            ObjectNode request, Map<String, String> ignoredHeaders, Duration timeout, Consumer<JsonNode> notifications)
            throws Exception {
        requireOpen();
        Objects.requireNonNull(request, "request");
        Duration limit = requireTimeout(timeout);
        String id = key(request.get("id"));
        if (pending.size() >= MAX_PENDING) {
            throw new IllegalStateException("MCP stdio pending request limit reached");
        }
        Pending value = new Pending(new CompletableFuture<>(), notifications == null ? ignored -> {} : notifications);
        if (pending.putIfAbsent(id, value) != null) {
            throw new IllegalArgumentException("duplicate MCP request id");
        }
        JsonNode progressToken = request.path("params").path("_meta").get("progressToken");
        String progressKey = progressToken == null ? null : key(progressToken);
        if (progressKey != null && progressPending.putIfAbsent(progressKey, value) != null) {
            pending.remove(id, value);
            throw new IllegalArgumentException("duplicate MCP progress token");
        }
        try {
            writeLine(request);
            return value.response().get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            cancel(request.get("id"), "request timed out");
            throw new IOException("MCP stdio request timed out", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            cancel(request.get("id"), "caller interrupted");
            throw failure;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new IOException("MCP stdio request failed", cause);
        } finally {
            pending.remove(id, value);
            if (progressKey != null) {
                progressPending.remove(progressKey, value);
            }
        }
    }

    @Override
    public void cancel(JsonNode requestId, String reason) {
        if (!open.get() || requestId == null) {
            return;
        }
        try {
            writeLine(codec.cancelled(requestId, reason));
        } catch (Exception ignored) {
        }
    }

    /** 返回有界、脱敏的 stderr 诊断快照，不将 stderr 混入 MCP 响应。 */
    public String stderrSnapshot() {
        synchronized (stderr) {
            return redact(new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        session.terminate();
        reader.interrupt();
        notificationExecutor.shutdownNow();
        failAll(new IOException("MCP stdio transport closed"));
    }

    private void readLoop() {
        try {
            while (open.get()) {
                SandboxSessionFrame frame = session.read(Duration.ofSeconds(1));
                if (frame == null) {
                    if (!session.isAlive()) {
                        throw new IOException("MCP process exited");
                    }
                    continue;
                }
                switch (frame.kind()) {
                    case STDOUT -> acceptStdout(frame.data());
                    case STDERR -> acceptStderr(frame.data());
                    case EXIT -> throw new IOException("MCP process exited with " + frame.exitCode());
                    case ERROR -> throw new IOException(frame.detail());
                    case READY -> throw new IOException("duplicate sandbox READY frame");
                }
            }
        } catch (Throwable failure) {
            if (open.compareAndSet(true, false)) {
                session.terminate();
                failAll(failure);
            }
        }
    }

    private void acceptStdout(byte[] bytes) throws Exception {
        synchronized (stdout) {
            stdout.write(bytes);
            if (stdout.size() > McpProtocol.MAX_MESSAGE_BYTES) {
                throw new IOException("MCP stdio frame exceeds 16 MiB");
            }
            byte[] all = stdout.toByteArray();
            int start = 0;
            for (int index = 0; index < all.length; index++) {
                if (all[index] != '\n') {
                    continue;
                }
                int end = index > start && all[index - 1] == '\r' ? index - 1 : index;
                if (end == start) {
                    throw new IOException("MCP emitted an empty JSONL frame");
                }
                acceptMessage(json.readTree(new String(all, start, end - start, StandardCharsets.UTF_8)));
                start = index + 1;
            }
            if (start > 0) {
                stdout.reset();
                stdout.write(all, start, all.length - start);
            }
        }
    }

    private void acceptMessage(JsonNode message) throws Exception {
        if (message == null
                || !message.isObject()
                || !"2.0".equals(message.path("jsonrpc").asText())) {
            throw new IOException("MCP emitted invalid JSON-RPC");
        }
        if (message.has("id") && (message.has("result") || message.has("error"))) {
            Pending target = pending.get(key(message.get("id")));
            if (target == null) {
                throw new IOException("MCP emitted an unknown response id");
            }
            target.complete(message.deepCopy());
            return;
        }
        if (message.path("method").isTextual() && !message.has("id")) {
            JsonNode correlation = message.path("params").path("_meta").get("io.modelcontextprotocol/subscriptionId");
            Pending target = correlation == null ? null : pending.get(key(correlation));
            if (target == null
                    && McpProtocol.PROGRESS.equals(message.path("method").asText())) {
                correlation = message.path("params").get("progressToken");
                target = correlation == null ? null : progressPending.get(key(correlation));
            }
            if (correlation == null
                    && McpProtocol.CANCELLED.equals(message.path("method").asText())) {
                correlation = message.path("params").get("requestId");
                target = correlation == null ? null : pending.get(key(correlation));
            }
            if (correlation == null) {
                throw new IOException("MCP emitted an uncorrelated stdio notification");
            }
            if (target == null) {
                throw new IOException("MCP emitted a notification for an unknown request");
            }
            JsonNode copy = message.deepCopy();
            target.accept(copy, notificationExecutor);
            return;
        }
        if (message.path("method").isTextual() && message.has("id")) {
            ObjectNode error = json.createObjectNode();
            error.put("jsonrpc", "2.0");
            error.set("id", message.get("id").deepCopy());
            ObjectNode detail = error.putObject("error");
            detail.put("code", -32601);
            detail.put("message", "MCP 2026-07-28 uses MRTR input_required results");
            writeLine(error);
            return;
        }
        throw new IOException("MCP emitted an unsupported JSON-RPC message");
    }

    private void acceptStderr(byte[] bytes) {
        synchronized (stderr) {
            int remaining = MAX_STDERR_BYTES - stderr.size();
            if (remaining > 0) {
                stderr.write(bytes, 0, Math.min(remaining, bytes.length));
            }
        }
    }

    private void writeLine(JsonNode value) throws Exception {
        byte[] frame = (codec.encode(value) + "\n").getBytes(StandardCharsets.UTF_8);
        if (frame.length > McpProtocol.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("MCP request exceeds 16 MiB");
        }
        synchronized (writeLock) {
            session.write(frame);
        }
    }

    private void failAll(Throwable failure) {
        pending.values().forEach(value -> value.response().completeExceptionally(failure));
        pending.clear();
    }

    private void requireOpen() throws IOException {
        if (!open.get() || !session.isAlive()) {
            throw new IOException("MCP transport is closed");
        }
    }

    private static Duration requireTimeout(Duration value) {
        Duration result = value == null ? Duration.ofSeconds(30) : value;
        if (result.isZero() || result.isNegative() || result.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("MCP timeout must be between 1ns and 10m");
        }
        return result;
    }

    private static String key(JsonNode id) {
        if (id == null || id.isNull() || (!id.isTextual() && !id.isIntegralNumber())) {
            throw new IllegalArgumentException("invalid MCP request id");
        }
        return id.isTextual() ? "s:" + id.asText() : "n:" + id.asText();
    }

    private static String redact(String value) {
        return value.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[-_ ]?key[=: ]+)[^\\s,;]+", "$1[REDACTED]");
    }

    private static final class Pending {
        private final CompletableFuture<JsonNode> response;
        private final Consumer<JsonNode> notifications;
        private final AtomicReference<CompletableFuture<Void>> notificationTail =
                new AtomicReference<>(CompletableFuture.completedFuture(null));

        private Pending(CompletableFuture<JsonNode> response, Consumer<JsonNode> notifications) {
            this.response = response;
            this.notifications = notifications;
        }

        CompletableFuture<JsonNode> response() {
            return response;
        }

        void accept(JsonNode notification, java.util.concurrent.Executor executor) {
            CompletableFuture<Void> next = notificationTail.updateAndGet(
                    previous -> previous.thenRunAsync(() -> notifications.accept(notification), executor));
            if (McpProtocol.CANCELLED.equals(notification.path("method").asText())) {
                String reason = notification.path("params").path("reason").asText("cancelled by MCP server");
                next.whenComplete((ignored, callbackFailure) -> {
                    if (callbackFailure != null) {
                        response.completeExceptionally(
                                new IOException("MCP cancellation notification failed validation", callbackFailure));
                    } else {
                        response.completeExceptionally(new IOException("MCP request cancelled by server: " + reason));
                    }
                });
            }
        }

        void complete(JsonNode value) {
            notificationTail.get().whenComplete((ignored, failure) -> {
                if (failure == null) {
                    response.complete(value);
                } else {
                    response.completeExceptionally(new IOException("MCP notification validation failed", failure));
                }
            });
        }
    }
}
