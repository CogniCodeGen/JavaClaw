package com.javaclaw.browser.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.protocol.CanonicalJson;

/** OAuth 专用 Browser Worker 状态机；完整 callback 只存在于 Worker 线程调用栈。 */
final class BrowserOAuthSessions implements AutoCloseable {
    private static final Duration EXIT_GRACE = Duration.ofSeconds(1);
    private static final int MAXIMUM_ACTIVE = 4;

    private final BrowserWorkerClient.WorkerLauncher launcher;
    private final Path controlRoot;
    private final Duration startupTimeout;
    private final CanonicalJson json;
    private final AtomicLong commandIds;
    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    BrowserOAuthSessions(
            BrowserWorkerClient.WorkerLauncher launcher,
            Path controlRoot,
            Duration startupTimeout,
            CanonicalJson json,
            AtomicLong commandIds) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.controlRoot = prepareRoot(controlRoot);
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        this.json = Objects.requireNonNull(json, "json");
        this.commandIds = Objects.requireNonNull(commandIds, "commandIds");
    }

    synchronized McpOAuthBrowserSession begin(
            McpOAuthBrowserTask task,
            BrowserNetworkExchange network,
            McpOAuthCallbackHandler callback,
            CancellationToken cancellation) {
        requireOpen();
        McpOAuthBrowserTask checked = Objects.requireNonNull(task, "task");
        Entry existing = sessions.get(checked.sessionId());
        if (existing != null) {
            existing.requireSameTask(checked);
            return existing.status();
        }
        long active =
                sessions.values().stream().filter(entry -> !entry.terminal()).count();
        if (active >= MAXIMUM_ACTIVE) {
            throw new IllegalStateException("OAuth Browser active session limit reached");
        }
        Entry candidate = new Entry(checked, controlFile(checked.sessionId()));
        Entry prior = sessions.putIfAbsent(checked.sessionId(), candidate);
        if (prior != null) {
            prior.requireSameTask(checked);
            return prior.status();
        }
        candidate.start(Objects.requireNonNull(network, "network"), Objects.requireNonNull(callback, "callback"));
        awaitReady(candidate, Objects.requireNonNull(cancellation, "cancellation"));
        return candidate.status();
    }

    McpOAuthBrowserSession status(String sessionId) {
        Entry entry = require(sessionId);
        entry.expireIfNeeded();
        return entry.status();
    }

    McpOAuthBrowserSession cancel(String sessionId) {
        Entry entry = require(sessionId);
        entry.cancel(false);
        return entry.status();
    }

    void invalidate(String endpointId, long currentRevision) {
        sessions.values().stream()
                .filter(entry ->
                        entry.task.endpointId().equals(endpointId) && entry.task.endpointRevision() != currentRevision)
                .forEach(entry -> entry.fail("OAUTH_ENDPOINT_REVOKED"));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        sessions.values().forEach(entry -> entry.cancel(false));
    }

    private void awaitReady(Entry entry, CancellationToken cancellation) {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        while (!entry.ready.isDone()) {
            cancellation.throwIfCancelled();
            if (System.nanoTime() >= deadline) {
                entry.fail("OAUTH_BROWSER_START_TIMEOUT");
                throw new BrowserWorkerException("OAuth Browser did not become ready");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                entry.fail("OAUTH_BROWSER_INTERRUPTED");
                throw new BrowserWorkerException("OAuth Browser startup was interrupted", interrupted);
            }
        }
        entry.ready.join();
    }

    private Entry require(String sessionId) {
        Entry entry = sessions.get(uuid(sessionId));
        if (entry == null) {
            throw new IllegalArgumentException("OAuth Browser session does not exist");
        }
        return entry;
    }

    private Path controlFile(String sessionId) {
        Path file = controlRoot.resolve("oauth-" + uuid(sessionId) + ".control").normalize();
        if (!file.getParent().equals(controlRoot)) {
            throw new SecurityException("OAuth Browser control file escapes its root");
        }
        return file;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("OAuth Browser sessions are closed");
        }
    }

    /** 单个会话的进程、网络取消和脱敏状态只由同步方法变更。 */
    private final class Entry {
        private final McpOAuthBrowserTask task;
        private final Path controlFile;
        private final Instant startedAt = Instant.now();
        private final Instant expiresAt;
        private final CancellationSource networkCancellation = new CancellationSource();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private McpOAuthBrowserState state = McpOAuthBrowserState.STARTING;
        private Optional<String> failureCode = Optional.empty();
        private Process process;

        private Entry(McpOAuthBrowserTask task, Path controlFile) {
            this.task = task;
            this.controlFile = controlFile;
            expiresAt = startedAt.plus(task.timeout());
        }

        private void start(BrowserNetworkExchange network, McpOAuthCallbackHandler callback) {
            Thread.ofVirtual().name("javaclaw-mcp-oauth-browser").start(() -> run(network, callback));
            Thread.ofVirtual().name("javaclaw-mcp-oauth-expiry").start(this::expireAtDeadline);
        }

        private void run(BrowserNetworkExchange network, McpOAuthCallbackHandler callback) {
            long commandId = commandIds.incrementAndGet();
            try {
                Files.deleteIfExists(controlFile);
                process = launcher.start();
                sendCommand(commandId);
                readMessages(commandId, network, callback);
            } catch (Exception failure) {
                fail("OAUTH_BROWSER_FAILED");
                ready.completeExceptionally(failure);
            } finally {
                networkCancellation.cancel("OAuth Browser Worker stopped");
                closeInput(process);
                awaitExit(process);
                destroy(process);
                deleteControlFile();
            }
        }

        private void sendCommand(long commandId) throws IOException {
            BrowserWorkerProtocol.OAuthTask workerTask = new BrowserWorkerProtocol.OAuthTask(
                    task.sessionId(),
                    task.authorizationId(),
                    task.endpointId(),
                    task.endpointRevision(),
                    task.authorizationUri(),
                    task.allowedOrigins(),
                    task.redirectUri(),
                    task.timeout());
            BrowserWorkerProtocol.Command command = new BrowserWorkerProtocol.Command(
                    BrowserWorkerProtocol.VERSION, commandId, BrowserWorkerProtocol.OAUTH, json.encode(workerTask), 0);
            BrowserFrameIo.writeJson(process.getOutputStream(), json, command);
            BrowserFrameIo.writeBinary(process.getOutputStream(), new byte[0], 0);
        }

        private void readMessages(long commandId, BrowserNetworkExchange network, McpOAuthCallbackHandler callback)
                throws Exception {
            long expectedNetworkSequence = 1;
            while (true) {
                BrowserWorkerProtocol.WorkerMessage message = BrowserFrameIo.readJson(
                        process.getInputStream(), json, BrowserWorkerProtocol.WorkerMessage.class);
                if (message.commandId() != commandId) {
                    throw new BrowserWorkerException("OAuth Browser command ID mismatch");
                }
                switch (message.kind()) {
                    case NETWORK_REQUEST -> {
                        if (message.sequence() != expectedNetworkSequence++) {
                            throw new BrowserWorkerException("OAuth Browser network sequence mismatch");
                        }
                        exchangeNetwork(message, network);
                    }
                    case SESSION_READY -> markReady(message);
                    case RESULT -> {
                        finish(message, callback);
                        return;
                    }
                }
            }
        }

        private synchronized void markReady(BrowserWorkerProtocol.WorkerMessage message) {
            BrowserWorkerProtocol.OAuthReady event =
                    json.decode(message.payload().orElseThrow(), BrowserWorkerProtocol.OAuthReady.class);
            if (!task.sessionId().equals(event.sessionId()) || state != McpOAuthBrowserState.STARTING) {
                throw new BrowserWorkerException("OAuth Browser ready event is invalid");
            }
            state = McpOAuthBrowserState.PENDING;
            ready.complete(null);
        }

        private void finish(BrowserWorkerProtocol.WorkerMessage message, McpOAuthCallbackHandler callback)
                throws Exception {
            if (message.binaryBytes() != 0) {
                throw new BrowserWorkerException("OAuth Browser result contains unexpected sensitive bytes");
            }
            if (message.error().isPresent()) {
                finishFailure(message.error().orElseThrow());
                return;
            }
            BrowserWorkerProtocol.OAuthCallback result =
                    json.decode(message.payload().orElseThrow(), BrowserWorkerProtocol.OAuthCallback.class);
            if (!task.sessionId().equals(result.sessionId())) {
                throw new BrowserWorkerException("OAuth Browser callback session mismatch");
            }
            synchronized (this) {
                if (state != McpOAuthBrowserState.PENDING) {
                    throw new SecurityException("OAuth Browser callback arrived after revocation");
                }
            }
            callback.handle(result.callbackUri());
            synchronized (this) {
                if (state != McpOAuthBrowserState.PENDING) {
                    throw new SecurityException("OAuth Browser callback completed after revocation");
                }
                state = McpOAuthBrowserState.COMPLETED;
                networkCancellation.cancel("OAuth Browser callback completed");
            }
        }

        private void exchangeNetwork(BrowserWorkerProtocol.WorkerMessage message, BrowserNetworkExchange network)
                throws IOException {
            byte[] body = BrowserFrameIo.readBinary(
                    process.getInputStream(), message.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            try {
                synchronized (this) {
                    if (state != McpOAuthBrowserState.STARTING && state != McpOAuthBrowserState.PENDING) {
                        throw new SecurityException("OAuth Browser no longer permits network requests");
                    }
                }
                BrowserWorkerProtocol.NetworkRequest request =
                        json.decode(message.payload().orElseThrow(), BrowserWorkerProtocol.NetworkRequest.class);
                networkCancellation.throwIfCancelled();
                BrowserNetworkResult result = network.exchange(request, body, networkCancellation);
                sendNetworkResult(message, result);
            } catch (Exception denied) {
                BrowserFrameIo.writeJson(
                        process.getOutputStream(),
                        json,
                        BrowserWorkerProtocol.HostMessage.failure(
                                message.commandId(), message.sequence(), "NETWORK_REQUEST_DENIED"));
            } finally {
                Arrays.fill(body, (byte) 0);
            }
        }

        private void sendNetworkResult(BrowserWorkerProtocol.WorkerMessage message, BrowserNetworkResult result)
                throws IOException {
            byte[] body = result.body();
            try {
                BrowserWorkerProtocol.HostMessage response = BrowserWorkerProtocol.HostMessage.success(
                        message.commandId(), message.sequence(), json.encode(result.response()), body.length);
                BrowserFrameIo.writeJson(process.getOutputStream(), json, response);
                BrowserFrameIo.writeBinary(
                        process.getOutputStream(), body, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            } finally {
                Arrays.fill(body, (byte) 0);
            }
        }

        private synchronized void finishFailure(String code) {
            if (terminal()) {
                return;
            }
            state = switch (code) {
                case "OAUTH_CANCELLED" -> McpOAuthBrowserState.CANCELLED;
                case "OAUTH_EXPIRED" -> McpOAuthBrowserState.EXPIRED;
                default -> McpOAuthBrowserState.FAILED;
            };
            failureCode = state == McpOAuthBrowserState.FAILED ? Optional.of(code) : Optional.empty();
            networkCancellation.cancel("OAuth Browser ended");
            BrowserWorkerException ended = new BrowserWorkerException("OAuth Browser ended with a stable error");
            ready.completeExceptionally(ended);
        }

        private synchronized void cancel(boolean expired) {
            if (terminal()) {
                return;
            }
            state = expired ? McpOAuthBrowserState.EXPIRED : McpOAuthBrowserState.CANCELLED;
            failureCode = Optional.empty();
            networkCancellation.cancel("OAuth Browser cancelled");
            signalCancel();
            ready.completeExceptionally(new BrowserWorkerException("OAuth Browser was cancelled"));
        }

        private synchronized void fail(String code) {
            if (terminal()) {
                return;
            }
            state = McpOAuthBrowserState.FAILED;
            failureCode = Optional.of(code);
            networkCancellation.cancel("OAuth Browser failed");
            signalCancel();
            ready.completeExceptionally(new BrowserWorkerException("OAuth Browser failed"));
        }

        private synchronized void expireIfNeeded() {
            if (!terminal() && !Instant.now().isBefore(expiresAt)) {
                cancel(true);
            }
        }

        private void expireAtDeadline() {
            try {
                Duration remaining = Duration.between(Instant.now(), expiresAt);
                if (!remaining.isNegative() && !remaining.isZero()) {
                    Thread.sleep(remaining);
                }
                expireIfNeeded();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void signalCancel() {
            try {
                Files.deleteIfExists(controlFile);
                Files.writeString(
                        controlFile,
                        "CANCEL",
                        StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            } catch (IOException failure) {
                destroy(process);
            }
        }

        private synchronized McpOAuthBrowserSession status() {
            return new McpOAuthBrowserSession(
                    task.sessionId(),
                    task.authorizationId(),
                    task.endpointId(),
                    task.endpointRevision(),
                    state,
                    startedAt,
                    expiresAt,
                    failureCode);
        }

        private synchronized boolean terminal() {
            return switch (state) {
                case COMPLETED, CANCELLED, EXPIRED, FAILED -> true;
                case STARTING, PENDING -> false;
            };
        }

        private void requireSameTask(McpOAuthBrowserTask candidate) {
            if (!task.authorizationId().equals(candidate.authorizationId())
                    || !task.endpointId().equals(candidate.endpointId())
                    || task.endpointRevision() != candidate.endpointRevision()) {
                throw new IllegalArgumentException("OAuth Browser idempotency key is bound to another task");
            }
        }

        private void deleteControlFile() {
            try {
                Files.deleteIfExists(controlFile);
            } catch (IOException ignored) {
                // 临时根会由 App Server 生命周期继续清理。
            }
        }
    }

    private static Path prepareRoot(Path value) {
        try {
            Path root = Objects.requireNonNull(value, "controlRoot")
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(root);
            Path real = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("control root is not a directory");
            }
            return real;
        } catch (IOException failure) {
            throw new IllegalStateException("OAuth Browser control root is unavailable", failure);
        }
    }

    private static String uuid(String value) {
        try {
            return java.util
                    .UUID
                    .fromString(Objects.requireNonNull(value, "sessionId"))
                    .toString();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("sessionId must be a UUID", failure);
        }
    }

    private static void awaitExit(Process process) {
        if (process == null) {
            return;
        }
        try {
            process.waitFor(EXIT_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeInput(Process process) {
        if (process == null) {
            return;
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Worker 退出时可能已经关闭同一管道。
        }
    }

    private static void destroy(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
