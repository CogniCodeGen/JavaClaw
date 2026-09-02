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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.protocol.CanonicalJson;

/** BrowserWorkerClient 的人工登录会话状态机；只保存脱敏元数据和受控进程句柄。 */
final class BrowserLoginSessions implements AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration EXIT_GRACE = Duration.ofSeconds(1);
    private static final Duration RETENTION = Duration.ofMinutes(5);
    private static final int MAXIMUM_ACTIVE_SESSIONS = 4;

    private final BrowserWorkerClient.WorkerLauncher launcher;
    private final Path controlRoot;
    private final Duration startupTimeout;
    private final CanonicalJson json;
    private final AtomicLong commandIds;
    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    BrowserLoginSessions(
            BrowserWorkerClient.WorkerLauncher launcher,
            Path controlRoot,
            Duration startupTimeout,
            CanonicalJson json,
            AtomicLong commandIds) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.controlRoot = prepareControlRoot(controlRoot);
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        this.json = Objects.requireNonNull(json, "json");
        this.commandIds = Objects.requireNonNull(commandIds, "commandIds");
    }

    synchronized SiteContracts.LoginSession begin(
            SiteContracts.LoginBeginTask task,
            byte[] storageState,
            BrowserNetworkExchange network,
            CancellationToken cancellation) {
        requireOpen();
        cleanup();
        SiteContracts.LoginBeginTask checkedTask = Objects.requireNonNull(task, "task");
        Entry existing = sessions.get(checkedTask.sessionId());
        if (existing != null) {
            existing.requireSameAuthority(checkedTask);
            return existing.status();
        }
        long activeCount =
                sessions.values().stream().filter(entry -> !entry.terminal()).count();
        if (activeCount >= MAXIMUM_ACTIVE_SESSIONS) {
            throw new IllegalStateException("Browser login active session limit reached");
        }
        byte[] state = checkedState(checkedTask, storageState);
        Entry candidate = new Entry(checkedTask, controlFile(checkedTask.sessionId()));
        Entry entry = sessions.putIfAbsent(checkedTask.sessionId(), candidate);
        if (entry != null) {
            Arrays.fill(state, (byte) 0);
            entry.requireSameAuthority(checkedTask);
            return entry.status();
        }
        candidate.start(state, Objects.requireNonNull(network, "network"));
        awaitReady(candidate, Objects.requireNonNull(cancellation, "cancellation"));
        return candidate.status();
    }

    SiteContracts.LoginSession status(String sessionId) {
        cleanup();
        Entry entry = require(sessionId);
        entry.expireIfNeeded();
        return entry.status();
    }

    <T> T save(String sessionId, BrowserStorageHandler<T> handler) {
        Entry entry = require(sessionId);
        byte[] state = entry.save();
        try {
            return Objects.requireNonNull(handler, "handler").handle(state);
        } catch (RuntimeException failure) {
            entry.fail("STORAGE_PERSIST_FAILED");
            throw failure;
        } catch (Exception failure) {
            entry.fail("STORAGE_PERSIST_FAILED");
            throw new BrowserWorkerException("Browser storage state could not be persisted", failure);
        } finally {
            Arrays.fill(state, (byte) 0);
        }
    }

    SiteContracts.LoginSession cancel(String sessionId) {
        Entry entry = require(sessionId);
        entry.cancel(false);
        return entry.status();
    }

    void invalidate(String siteId, long currentAuthorityRevision) {
        sessions.values().stream()
                .filter(entry -> entry.task.site().id().equals(siteId)
                        && entry.task.site().authorityRevision() != currentAuthorityRevision)
                .forEach(entry -> entry.cancel(false));
    }

    private void awaitReady(Entry entry, CancellationToken cancellation) {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        while (true) {
            cancellation.throwIfCancelled();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                entry.cancel(false);
                throw new BrowserWorkerException("Browser login window did not become ready");
            }
            try {
                entry.ready.get(Math.min(remaining, POLL_INTERVAL.toNanos()), TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException waiting) {
                // 短轮询只用于传播取消和启动时限。
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                entry.cancel(false);
                throw new BrowserWorkerException("Browser login startup was interrupted", interrupted);
            } catch (ExecutionException failure) {
                throw new BrowserWorkerException("Browser login startup failed", failure.getCause());
            }
        }
    }

    private Entry require(String sessionId) {
        String checked = uuid(sessionId);
        Entry entry = sessions.get(checked);
        if (entry == null) {
            throw new IllegalArgumentException("Browser login session does not exist");
        }
        return entry;
    }

    private Path controlFile(String sessionId) {
        Path file = controlRoot.resolve("login-" + uuid(sessionId) + ".control").normalize();
        if (!file.getParent().equals(controlRoot)) {
            throw new SecurityException("Browser login control file escapes its root");
        }
        return file;
    }

    private void cleanup() {
        Instant threshold = Instant.now().minus(RETENTION);
        sessions.entrySet()
                .removeIf(entry -> entry.getValue().terminal()
                        && entry.getValue()
                                .startedAt
                                .plus(entry.getValue().task.timeout())
                                .isBefore(threshold));
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Browser login client is closed");
        }
    }

    @Override
    public void close() {
        closed = true;
        sessions.values().forEach(entry -> entry.cancel(false));
        sessions.clear();
    }

    private final class Entry {
        private final SiteContracts.LoginBeginTask task;
        private final Path controlFile;
        private final Instant startedAt = Instant.now();
        private final Instant expiresAt;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final CompletableFuture<byte[]> saved = new CompletableFuture<>();
        private final CancellationSource networkCancellation = new CancellationSource();
        private volatile SiteContracts.LoginSessionState state = SiteContracts.LoginSessionState.STARTING;
        private volatile Optional<String> failureCode = Optional.empty();
        private volatile Process process;

        private Entry(SiteContracts.LoginBeginTask task, Path controlFile) {
            this.task = task;
            this.controlFile = controlFile;
            expiresAt = startedAt.plus(task.timeout());
        }

        private void start(byte[] storageState, BrowserNetworkExchange network) {
            byte[] ownedState = storageState.clone();
            Arrays.fill(storageState, (byte) 0);
            Thread.ofVirtual().name("javaclaw-browser-login").start(() -> run(ownedState, network));
            Thread.ofVirtual().name("javaclaw-browser-login-deadline").start(this::expireAtDeadline);
        }

        private void run(byte[] storageState, BrowserNetworkExchange network) {
            long commandId = commandIds.incrementAndGet();
            try {
                Files.deleteIfExists(controlFile);
                process = launcher.start();
                sendCommand(process, commandId, storageState);
                readMessages(process, commandId, network);
            } catch (Exception failure) {
                fail("BROWSER_LOGIN_FAILED");
                ready.completeExceptionally(failure);
                saved.completeExceptionally(failure);
            } finally {
                networkCancellation.cancel("browser login worker stopped");
                Arrays.fill(storageState, (byte) 0);
                closeInput(process);
                awaitExit(process);
                destroy(process);
                deleteControlFile();
            }
        }

        private void sendCommand(Process worker, long commandId, byte[] storageState) throws IOException {
            BrowserWorkerProtocol.LoginTask workerTask = new BrowserWorkerProtocol.LoginTask(
                    task.sessionId(), task.site().origin(), task.site().allowedOrigins(), task.timeout());
            BrowserWorkerProtocol.Command command = new BrowserWorkerProtocol.Command(
                    BrowserWorkerProtocol.VERSION,
                    commandId,
                    BrowserWorkerProtocol.LOGIN,
                    json.encode(workerTask),
                    storageState.length);
            BrowserFrameIo.writeJson(worker.getOutputStream(), json, command);
            BrowserFrameIo.writeBinary(
                    worker.getOutputStream(), storageState, BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
        }

        private void readMessages(Process worker, long commandId, BrowserNetworkExchange network) throws Exception {
            long expectedNetworkSequence = 1;
            while (true) {
                BrowserWorkerProtocol.WorkerMessage message = BrowserFrameIo.readJson(
                        worker.getInputStream(), json, BrowserWorkerProtocol.WorkerMessage.class);
                requireCommand(message, commandId);
                switch (message.kind()) {
                    case NETWORK_REQUEST -> {
                        if (message.sequence() != expectedNetworkSequence++) {
                            throw new BrowserWorkerException("Browser login network sequence mismatch");
                        }
                        exchangeNetwork(worker, message, network);
                    }
                    case SESSION_READY -> markReady(message);
                    case RESULT -> {
                        finish(worker, message);
                        return;
                    }
                }
            }
        }

        private synchronized void markReady(BrowserWorkerProtocol.WorkerMessage message) {
            BrowserWorkerProtocol.LoginReady event =
                    json.decode(message.payload().orElseThrow(), BrowserWorkerProtocol.LoginReady.class);
            if (!task.sessionId().equals(event.sessionId()) || state != SiteContracts.LoginSessionState.STARTING) {
                throw new BrowserWorkerException("Browser login ready event is invalid");
            }
            state = SiteContracts.LoginSessionState.READY;
            ready.complete(null);
        }

        private void finish(Process worker, BrowserWorkerProtocol.WorkerMessage message) throws IOException {
            if (message.error().isPresent()) {
                finishFailure(message.error().orElseThrow());
                return;
            }
            BrowserWorkerProtocol.LoginSaved result =
                    json.decode(message.payload().orElseThrow(), BrowserWorkerProtocol.LoginSaved.class);
            if (!task.sessionId().equals(result.sessionId())) {
                throw new BrowserWorkerException("Browser login save result is invalid");
            }
            byte[] sensitive = BrowserFrameIo.readBinary(
                    worker.getInputStream(), message.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
            if (sensitive.length == 0) {
                throw new BrowserWorkerException("Browser login returned an empty storage state");
            }
            acceptSavedState(sensitive);
        }

        private synchronized void finishFailure(String code) {
            if (terminal()) {
                return;
            }
            state = switch (code) {
                case "LOGIN_CANCELLED" -> SiteContracts.LoginSessionState.CANCELLED;
                case "LOGIN_EXPIRED" -> SiteContracts.LoginSessionState.EXPIRED;
                default -> SiteContracts.LoginSessionState.FAILED;
            };
            networkCancellation.cancel("browser login worker ended");
            failureCode = state == SiteContracts.LoginSessionState.FAILED ? Optional.of(code) : Optional.empty();
            BrowserWorkerException failure = new BrowserWorkerException("Browser login ended: " + code);
            ready.completeExceptionally(failure);
            saved.completeExceptionally(failure);
        }

        private void exchangeNetwork(
                Process worker, BrowserWorkerProtocol.WorkerMessage message, BrowserNetworkExchange network)
                throws IOException {
            byte[] requestBody = BrowserFrameIo.readBinary(
                    worker.getInputStream(), message.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            try {
                if (state != SiteContracts.LoginSessionState.STARTING
                        && state != SiteContracts.LoginSessionState.READY) {
                    throw new SecurityException("Browser login session no longer permits network requests");
                }
                BrowserWorkerProtocol.NetworkRequest request =
                        json.decode(message.payload().orElseThrow(), BrowserWorkerProtocol.NetworkRequest.class);
                networkCancellation.throwIfCancelled();
                BrowserNetworkResult result = network.exchange(request, requestBody, networkCancellation);
                sendNetworkResult(worker, message, result);
            } catch (Exception denied) {
                BrowserFrameIo.writeJson(
                        worker.getOutputStream(),
                        json,
                        BrowserWorkerProtocol.HostMessage.failure(
                                message.commandId(), message.sequence(), "NETWORK_REQUEST_DENIED"));
            } finally {
                Arrays.fill(requestBody, (byte) 0);
            }
        }

        private void sendNetworkResult(
                Process worker, BrowserWorkerProtocol.WorkerMessage message, BrowserNetworkResult result)
                throws IOException {
            byte[] body = result.body();
            try {
                BrowserWorkerProtocol.HostMessage response = BrowserWorkerProtocol.HostMessage.success(
                        message.commandId(), message.sequence(), json.encode(result.response()), body.length);
                BrowserFrameIo.writeJson(worker.getOutputStream(), json, response);
                BrowserFrameIo.writeBinary(worker.getOutputStream(), body, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            } finally {
                Arrays.fill(body, (byte) 0);
            }
        }

        private byte[] save() {
            synchronized (this) {
                expireIfNeeded();
                if (state != SiteContracts.LoginSessionState.READY) {
                    throw new IllegalStateException("Browser login session is not ready to save");
                }
                state = SiteContracts.LoginSessionState.SAVING;
                networkCancellation.cancel("browser login is saving");
                signal("SAVE");
            }
            try {
                long remaining =
                        Math.max(1, Duration.between(Instant.now(), expiresAt).toMillis());
                byte[] retained = saved.get(remaining, TimeUnit.MILLISECONDS);
                byte[] result = retained.clone();
                Arrays.fill(retained, (byte) 0);
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("BROWSER_LOGIN_INTERRUPTED");
                throw new BrowserWorkerException("Browser login save was interrupted", interrupted);
            } catch (ExecutionException | TimeoutException failure) {
                expireIfNeeded();
                throw new BrowserWorkerException("Browser login save failed", failure);
            }
        }

        private synchronized void cancel(boolean expired) {
            if (terminal()) {
                return;
            }
            state = expired ? SiteContracts.LoginSessionState.EXPIRED : SiteContracts.LoginSessionState.CANCELLED;
            networkCancellation.cancel(expired ? "browser login expired" : "browser login cancelled");
            try {
                signal("CANCEL");
            } catch (RuntimeException ignored) {
                destroy(process);
            }
            ready.completeExceptionally(new BrowserWorkerException("Browser login was cancelled"));
            saved.completeExceptionally(new BrowserWorkerException("Browser login was cancelled"));
        }

        private synchronized void expireIfNeeded() {
            if (!terminal() && !Instant.now().isBefore(expiresAt)) {
                cancel(true);
            }
        }

        private synchronized void fail(String code) {
            if (!terminal() || state == SiteContracts.LoginSessionState.SAVED) {
                state = SiteContracts.LoginSessionState.FAILED;
                failureCode = Optional.of(code);
                networkCancellation.cancel("browser login failed");
            }
        }

        private synchronized void acceptSavedState(byte[] sensitive) {
            if (state != SiteContracts.LoginSessionState.SAVING) {
                Arrays.fill(sensitive, (byte) 0);
                throw new BrowserWorkerException("Browser login save completed after the session was revoked");
            }
            state = SiteContracts.LoginSessionState.SAVED;
            if (!saved.complete(sensitive)) {
                state = SiteContracts.LoginSessionState.FAILED;
                failureCode = Optional.of("BROWSER_LOGIN_STATE_REJECTED");
                Arrays.fill(sensitive, (byte) 0);
                throw new BrowserWorkerException("Browser login private state was rejected");
            }
        }

        private void expireAtDeadline() {
            Duration remaining = Duration.between(Instant.now(), expiresAt);
            try {
                if (!remaining.isNegative() && !remaining.isZero()) {
                    Thread.sleep(remaining);
                }
                expireIfNeeded();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void signal(String value) {
            try {
                if (Files.exists(controlFile, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(controlFile, LinkOption.NOFOLLOW_LINKS)) {
                        throw new SecurityException("Browser login control path is not a regular file");
                    }
                    Files.delete(controlFile);
                }
                Files.writeString(
                        controlFile,
                        value,
                        StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            } catch (IOException failure) {
                throw new BrowserWorkerException("Browser login control signal failed", failure);
            }
        }

        private SiteContracts.LoginSession status() {
            return new SiteContracts.LoginSession(
                    task.sessionId(),
                    task.site().id(),
                    task.site().revision(),
                    task.site().authorityRevision(),
                    state,
                    startedAt,
                    expiresAt,
                    failureCode);
        }

        private boolean terminal() {
            return switch (state) {
                case SAVED, CANCELLED, EXPIRED, FAILED -> true;
                case STARTING, READY, SAVING -> false;
            };
        }

        private void requireSameAuthority(SiteContracts.LoginBeginTask candidate) {
            if (!task.site().id().equals(candidate.site().id())
                    || task.site().revision() != candidate.site().revision()
                    || task.site().authorityRevision() != candidate.site().authorityRevision()) {
                throw new IllegalArgumentException("login idempotency key is bound to another Site authority");
            }
        }

        private void deleteControlFile() {
            try {
                Files.deleteIfExists(controlFile);
            } catch (IOException ignored) {
                // 临时控制根会由 App Server 生命周期继续清理。
            }
        }
    }

    private static Path prepareControlRoot(Path value) {
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
            throw new IllegalStateException("Browser login control root is unavailable", failure);
        }
    }

    private static byte[] checkedState(SiteContracts.LoginBeginTask task, byte[] source) {
        byte[] state = Objects.requireNonNull(source, "storageState").clone();
        if (state.length > BrowserWorkerProtocol.MAXIMUM_STATE_BYTES) {
            Arrays.fill(state, (byte) 0);
            throw new IllegalArgumentException("Browser storage state exceeds the frame limit");
        }
        boolean browserCredential = task.site().credential().kind() == SiteContracts.CredentialKind.BROWSER_STORAGE;
        if (!browserCredential && state.length > 0) {
            Arrays.fill(state, (byte) 0);
            throw new IllegalArgumentException("login storage state does not match the Site credential kind");
        }
        return state;
    }

    private static void requireCommand(BrowserWorkerProtocol.WorkerMessage message, long commandId) {
        if (message.commandId() != commandId) {
            throw new BrowserWorkerException("Browser login command ID mismatch");
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
