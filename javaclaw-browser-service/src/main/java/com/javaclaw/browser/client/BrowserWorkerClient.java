package com.javaclaw.browser.client;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerCommand;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerLauncher;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 为每个 Site 调用启动一次性原生 Sandbox Worker，并处理其反向 Network Broker 请求。
 *
 * <p><strong>安全不变量：</strong>生产构造器只接受 {@link SandboxedWorkerCommand}，backend 不可用即失败；Browser storage state
 * 使用原始私有帧且无返回路径；每个网络请求在宿主回调中重新授权；Site authority 变化会强制终止仍绑定旧 revision 的活动进程。
 */
public final class BrowserWorkerClient implements BrowserWorkerPort {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration EXIT_GRACE = Duration.ofSeconds(1);

    private final WorkerLauncher launcher;
    private final Duration timeout;
    private final CanonicalJson json = new CanonicalJson();
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<AuthorityKey, Set<Process>> active = new ConcurrentHashMap<>();
    private final BrowserLoginSessions logins;
    private final BrowserOAuthSessions oauth;
    private final BrowserWorkerCapabilities capabilities;
    private volatile boolean closed;

    /**
     * 创建只通过平台原生 Sandbox 启动的 Browser Worker 客户端。
     *
     * @param command 无宿主 HOME、无原始网络的 Worker 描述
     * @param timeout 单次宿主调用上限，1 秒至 10 分钟
     */
    public BrowserWorkerClient(SandboxedWorkerCommand command, Duration timeout) {
        this(command, timeout, null, BrowserWorkerCapabilities.unavailable());
    }

    /**
     * 创建可选人工登录能力的原生 Sandbox Worker 客户端。
     *
     * <p>{@code capabilities} 只能由签名发行镜像的分项能力回执决定；普通 classpath/IDEA 启动必须失败关闭。
     *
     * @param command 原生 Sandbox Worker 描述
     * @param timeout 快照和登录窗口启动上限
     * @param controlRoot Worker 只读、App Server 独占写入的控制目录
     * @param capabilities 当前平台已分别验证的交互能力
     */
    public BrowserWorkerClient(
            SandboxedWorkerCommand command,
            Duration timeout,
            Path controlRoot,
            BrowserWorkerCapabilities capabilities) {
        SandboxedWorkerLauncher sandbox = new SandboxedWorkerLauncher();
        SandboxedWorkerCommand checked = Objects.requireNonNull(command, "command");
        launcher = new BrowserWorkerLauncher(checked, sandbox::start);
        this.timeout = timeout(timeout);
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        logins = capabilities.interactiveLogin()
                ? new BrowserLoginSessions(launcher, controlRoot, this.timeout, json, sequence)
                : null;
        oauth = capabilities.mcpOAuth()
                ? new BrowserOAuthSessions(launcher, controlRoot, this.timeout, json, sequence)
                : null;
    }

    BrowserWorkerClient(WorkerLauncher launcher, Duration timeout) {
        this(launcher, timeout, null, BrowserWorkerCapabilities.unavailable());
    }

    BrowserWorkerClient(
            WorkerLauncher launcher, Duration timeout, Path controlRoot, BrowserWorkerCapabilities capabilities) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.timeout = timeout(timeout);
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        logins = capabilities.interactiveLogin()
                ? new BrowserLoginSessions(this.launcher, controlRoot, this.timeout, json, sequence)
                : null;
        oauth = capabilities.mcpOAuth()
                ? new BrowserOAuthSessions(this.launcher, controlRoot, this.timeout, json, sequence)
                : null;
    }

    /**
     * 执行一个受 Site authority 约束的页面快照。
     *
     * @param task 含 CredentialRef 但不含 Secret 的宿主任务
     * @param storageState Vault 解密得到的短生命周期 Browser state；无状态时为空数组
     * @param network 每次请求都复查权限的宿主 Broker 回调
     * @param cancellation Turn 取消信号
     * @return 编码后的 {@link SiteContracts.PageSnapshot}
     */
    @Override
    public CanonicalPayload snapshot(
            SiteContracts.SnapshotTask task,
            byte[] storageState,
            BrowserNetworkExchange network,
            CancellationToken cancellation) {
        requireOpen();
        SiteContracts.SnapshotTask checkedTask = Objects.requireNonNull(task, "task");
        BrowserNetworkExchange checkedNetwork = Objects.requireNonNull(network, "network");
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        checkedCancellation.throwIfCancelled();
        byte[] secret = checkedState(checkedTask, storageState);
        long commandId = sequence.incrementAndGet();
        AuthorityKey authority =
                new AuthorityKey(checkedTask.site().id(), checkedTask.site().authorityRevision());
        AtomicReference<Process> pending = new AtomicReference<>();
        FutureTask<CanonicalPayload> exchange = new FutureTask<>(
                () -> run(commandId, authority, checkedTask, secret, checkedNetwork, checkedCancellation, pending));
        Thread worker = Thread.ofVirtual().name("javaclaw-browser-client").start(exchange);
        try {
            return await(exchange, pending, checkedCancellation);
        } finally {
            Arrays.fill(secret, (byte) 0);
            if (!exchange.isDone()) {
                exchange.cancel(true);
                destroy(pending.get());
                worker.interrupt();
            }
        }
    }

    /**
     * 终止一个 Site 中仍绑定旧 authority revision 的进程。
     *
     * @param siteId Site 标识
     * @param currentAuthorityRevision 当前 revision；删除 Site 时为 0
     */
    @Override
    public void invalidate(String siteId, long currentAuthorityRevision) {
        AuthorityKey.validate(siteId, currentAuthorityRevision);
        if (logins != null) {
            logins.invalidate(siteId, currentAuthorityRevision);
        }
        active.forEach((key, processes) -> {
            if (key.siteId().equals(siteId) && key.authorityRevision() != currentAuthorityRevision) {
                processes.forEach(BrowserWorkerClient::destroy);
            }
        });
    }

    @Override
    public SiteContracts.LoginSession beginLogin(
            SiteContracts.LoginBeginTask task,
            byte[] storageState,
            BrowserNetworkExchange network,
            CancellationToken cancellation) {
        requireInteractiveLogin();
        return logins.begin(task, storageState, network, cancellation);
    }

    @Override
    public SiteContracts.LoginSession loginStatus(String sessionId) {
        requireInteractiveLogin();
        return logins.status(sessionId);
    }

    @Override
    public <T> T saveLogin(String sessionId, BrowserStorageHandler<T> handler) {
        requireInteractiveLogin();
        return logins.save(sessionId, handler);
    }

    @Override
    public SiteContracts.LoginSession cancelLogin(String sessionId) {
        requireInteractiveLogin();
        return logins.cancel(sessionId);
    }

    @Override
    public boolean interactiveLoginAvailable() {
        return capabilities.interactiveLogin();
    }

    @Override
    public McpOAuthBrowserSession beginOAuth(
            McpOAuthBrowserTask task,
            BrowserNetworkExchange network,
            McpOAuthCallbackHandler callback,
            CancellationToken cancellation) {
        requireOAuth();
        return oauth.begin(task, network, callback, cancellation);
    }

    @Override
    public McpOAuthBrowserSession oauthStatus(String sessionId) {
        requireOAuth();
        return oauth.status(sessionId);
    }

    @Override
    public McpOAuthBrowserSession cancelOAuth(String sessionId) {
        requireOAuth();
        return oauth.cancel(sessionId);
    }

    @Override
    public void invalidateOAuth(String endpointId, long currentRevision) {
        if (oauth != null) {
            oauth.invalidate(endpointId, currentRevision);
        }
    }

    @Override
    public boolean oauthAvailable() {
        return capabilities.mcpOAuth() && oauth != null;
    }

    private void requireInteractiveLogin() {
        requireOpen();
        if (!capabilities.interactiveLogin() || logins == null) {
            throw new UnsupportedOperationException(
                    "Browser interactive login is not verified for this packaged native runtime");
        }
    }

    private void requireOAuth() {
        requireOpen();
        if (!oauthAvailable()) {
            throw new UnsupportedOperationException("OAuth Browser is not verified for this packaged native runtime");
        }
    }

    private CanonicalPayload run(
            long commandId,
            AuthorityKey authority,
            SiteContracts.SnapshotTask task,
            byte[] storageState,
            BrowserNetworkExchange network,
            CancellationToken cancellation,
            AtomicReference<Process> pending)
            throws Exception {
        Process process = launcher.start();
        pending.set(process);
        try {
            register(authority, process);
            sendCommand(process, commandId, task, storageState);
            return readMessages(process, commandId, network, cancellation);
        } finally {
            unregister(authority, process);
            closeInput(process);
            awaitExit(process);
            destroy(process);
            pending.compareAndSet(process, null);
        }
    }

    private void sendCommand(Process process, long commandId, SiteContracts.SnapshotTask task, byte[] storageState)
            throws IOException {
        BrowserWorkerProtocol.SnapshotTask workerTask = new BrowserWorkerProtocol.SnapshotTask(
                task.uri(), task.site().allowedOrigins(), task.maxCharacters(), task.timeout());
        BrowserWorkerProtocol.Command command = new BrowserWorkerProtocol.Command(
                BrowserWorkerProtocol.VERSION,
                commandId,
                BrowserWorkerProtocol.SNAPSHOT,
                json.encode(workerTask),
                storageState.length);
        BrowserFrameIo.writeJson(process.getOutputStream(), json, command);
        BrowserFrameIo.writeBinary(process.getOutputStream(), storageState, BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
    }

    private CanonicalPayload readMessages(
            Process process, long commandId, BrowserNetworkExchange network, CancellationToken cancellation)
            throws Exception {
        long expectedNetworkSequence = 1;
        while (true) {
            cancellation.throwIfCancelled();
            BrowserWorkerProtocol.WorkerMessage message =
                    BrowserFrameIo.readJson(process.getInputStream(), json, BrowserWorkerProtocol.WorkerMessage.class);
            if (message.commandId() != commandId) {
                throw new BrowserWorkerException("Browser Worker command ID mismatch");
            }
            if (message.kind() == BrowserWorkerProtocol.WorkerMessageKind.RESULT) {
                if (message.binaryBytes() != 0) {
                    throw new BrowserWorkerException("snapshot result unexpectedly contains sensitive data");
                }
                return message.payload()
                        .orElseThrow(() -> new BrowserWorkerException("Browser Worker rejected request: "
                                + message.error().orElseThrow()));
            }
            if (message.kind() != BrowserWorkerProtocol.WorkerMessageKind.NETWORK_REQUEST) {
                throw new BrowserWorkerException("snapshot Worker emitted an unexpected session event");
            }
            if (message.sequence() != expectedNetworkSequence++) {
                throw new BrowserWorkerException("Browser Worker network sequence mismatch");
            }
            exchangeNetwork(process, message, network, cancellation);
        }
    }

    private void exchangeNetwork(
            Process process,
            BrowserWorkerProtocol.WorkerMessage message,
            BrowserNetworkExchange network,
            CancellationToken cancellation)
            throws IOException {
        byte[] requestBody = BrowserFrameIo.readBinary(
                process.getInputStream(), message.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
        try {
            BrowserWorkerProtocol.NetworkRequest request =
                    json.decode(message.payload().orElseThrow(), BrowserWorkerProtocol.NetworkRequest.class);
            BrowserNetworkResult result = network.exchange(request, requestBody, cancellation);
            byte[] responseBody = result.body();
            try {
                BrowserWorkerProtocol.HostMessage response = BrowserWorkerProtocol.HostMessage.success(
                        message.commandId(), message.sequence(), json.encode(result.response()), responseBody.length);
                BrowserFrameIo.writeJson(process.getOutputStream(), json, response);
                BrowserFrameIo.writeBinary(
                        process.getOutputStream(), responseBody, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            } finally {
                Arrays.fill(responseBody, (byte) 0);
            }
        } catch (Exception denied) {
            BrowserFrameIo.writeJson(
                    process.getOutputStream(),
                    json,
                    BrowserWorkerProtocol.HostMessage.failure(
                            message.commandId(), message.sequence(), "NETWORK_REQUEST_DENIED"));
        } finally {
            Arrays.fill(requestBody, (byte) 0);
        }
    }

    private CanonicalPayload await(
            FutureTask<CanonicalPayload> exchange, AtomicReference<Process> pending, CancellationToken cancellation) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            cancellation.throwIfCancelled();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                destroy(pending.get());
                throw new BrowserWorkerException("Browser Worker call timed out");
            }
            try {
                return exchange.get(Math.min(remaining, POLL_INTERVAL.toNanos()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException pendingResult) {
                // 短轮询仅用于传播取消和宿主总时限。
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new BrowserWorkerException("Browser Worker call was interrupted", interrupted);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof BrowserWorkerException browserFailure) {
                    throw browserFailure;
                }
                throw new BrowserWorkerException("Browser Worker process failed", cause);
            }
        }
    }

    private static byte[] checkedState(SiteContracts.SnapshotTask task, byte[] source) {
        byte[] state = Objects.requireNonNull(source, "storageState").clone();
        if (state.length > BrowserWorkerProtocol.MAXIMUM_STATE_BYTES) {
            Arrays.fill(state, (byte) 0);
            throw new IllegalArgumentException("Browser storage state exceeds the frame limit");
        }
        boolean expected = task.site().credential().kind() == SiteContracts.CredentialKind.BROWSER_STORAGE;
        if (expected != (state.length > 0)) {
            Arrays.fill(state, (byte) 0);
            throw new IllegalArgumentException("Browser storage state does not match the Site credential kind");
        }
        return state;
    }

    private void register(AuthorityKey authority, Process process) {
        requireOpen();
        active.computeIfAbsent(authority, ignored -> ConcurrentHashMap.newKeySet())
                .add(process);
    }

    private void unregister(AuthorityKey authority, Process process) {
        active.computeIfPresent(authority, (ignored, processes) -> {
            processes.remove(process);
            return processes.isEmpty() ? null : processes;
        });
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Browser Worker client is closed");
        }
    }

    /** 终止全部活动 Worker；幂等。 */
    @Override
    public void close() {
        closed = true;
        if (logins != null) {
            logins.close();
        }
        if (oauth != null) {
            oauth.close();
        }
        active.values().forEach(processes -> processes.forEach(BrowserWorkerClient::destroy));
        active.clear();
    }

    private static void awaitExit(Process process) {
        try {
            process.waitFor(EXIT_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeInput(Process process) {
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

    private static Duration timeout(Duration value) {
        Duration checked = Objects.requireNonNull(value, "timeout");
        if (checked.compareTo(Duration.ofSeconds(1)) < 0 || checked.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 second and 10 minutes");
        }
        return checked;
    }

    @FunctionalInterface
    interface WorkerLauncher {
        Process start() throws IOException;
    }

    private record AuthorityKey(String siteId, long authorityRevision) {
        private AuthorityKey {
            validate(siteId, authorityRevision);
        }

        private static void validate(String siteId, long authorityRevision) {
            if (siteId == null || siteId.isBlank() || authorityRevision < 0) {
                throw new IllegalArgumentException("Site authority identity is invalid");
            }
        }
    }
}
