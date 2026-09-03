package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelDiscoveryOperation;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelDiscoveryRequest;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.model.ProviderModelDiscoveryAdapter;

/**
 * 校验 Provider 精确版本后，管理有界且绑定 RPC session 的模型目录发现操作。
 *
 * <p>操作与结果均不持久化。连接关闭会取消并删除该连接拥有的所有操作；终态只短暂保留，既允许本地 RPC 读取结果，也防止无人读取的结果无限占用内存。
 */
public final class ProviderModelDiscoveryService implements AutoCloseable {
    private static final int MAXIMUM_OPERATIONS = 64;
    private static final int MAXIMUM_OPERATIONS_PER_SESSION = 4;
    private static final int MAXIMUM_CANCEL_MEMOS_PER_OPERATION = 8;
    private static final Duration TERMINAL_RETENTION = Duration.ofSeconds(30);
    private static final Duration READ_TERMINAL_RETENTION = Duration.ofSeconds(1);

    private final Object lock = new Object();
    private final ProviderService providers;
    private final DiscoveryPort discovery;
    private final Clock clock;
    private final ExecutorService workers;
    private final ScheduledExecutorService cleanup;
    private final Map<String, OperationEntry> operations = new HashMap<>();
    private final Map<SessionCommandKey, StartMemo> starts = new HashMap<>();
    private final Map<SessionCommandKey, CancelMemo> cancels = new HashMap<>();
    private boolean closed;

    /**
     * 使用真实厂商 SDK Adapter 创建发现服务。
     *
     * @param providers Provider 精确版本服务
     * @param discovery 厂商模型目录 Adapter
     */
    public ProviderModelDiscoveryService(ProviderService providers, ProviderModelDiscoveryAdapter discovery) {
        this(providers, Objects.requireNonNull(discovery, "discovery")::discover, Clock.systemUTC());
    }

    ProviderModelDiscoveryService(ProviderService providers, DiscoveryPort discovery) {
        this(providers, discovery, Clock.systemUTC());
    }

    ProviderModelDiscoveryService(ProviderService providers, DiscoveryPort discovery, Clock clock) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.clock = Objects.requireNonNull(clock, "clock");
        workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("provider-model-discovery-", 0).factory());
        cleanup = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "provider-model-discovery-cleanup");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 创建一个立即返回的发现操作。
     *
     * @param owner 当前 RPC session 的不透明标识
     * @param identity start 写命令身份；expected revision 必须等于 Provider 版本
     * @param request 精确 Provider 请求
     * @return RUNNING 操作；同 session、同幂等键和同 payload 返回原操作
     */
    public ProviderModelDiscoveryOperation start(
            String owner, CommandIdentity identity, ProviderModelDiscoveryRequest request) {
        String checkedOwner = text(owner, "owner");
        CommandIdentity checkedIdentity = Objects.requireNonNull(identity, "identity");
        ProviderModelDiscoveryRequest checkedRequest = Objects.requireNonNull(request, "request");
        if (checkedIdentity.expectedRevision() != checkedRequest.endpointRevision()) {
            throw PersistenceException.revisionConflict("Provider model discovery revision does not match request");
        }
        SessionCommandKey commandKey = new SessionCommandKey(checkedOwner, checkedIdentity.idempotencyKey());
        synchronized (lock) {
            requireOpen();
            StartMemo prior = starts.get(commandKey);
            if (prior != null) {
                if (!prior.requestDigest().equals(checkedIdentity.requestDigest())) {
                    throw PersistenceException.idempotencyConflict(
                            "Provider model discovery idempotency key belongs to another request");
                }
                return prior.entry().snapshot();
            }
            requireCapacity(checkedOwner);
            ProviderEndpoint endpoint =
                    providers.requireDiscoverable(checkedRequest.endpointId(), checkedRequest.endpointRevision());
            Instant now = clock.instant();
            OperationEntry entry =
                    new OperationEntry(UUID.randomUUID().toString(), checkedOwner, commandKey, endpoint, now);
            operations.put(entry.id(), entry);
            starts.put(commandKey, new StartMemo(checkedIdentity.requestDigest(), entry));
            Future<?> future = workers.submit(() -> execute(entry));
            entry.attach(future);
            return entry.snapshot();
        }
    }

    /**
     * 读取当前 session 拥有的操作。
     *
     * @param owner 当前 RPC session 标识
     * @param operationId 服务端操作标识
     * @return 当前快照
     */
    public ProviderModelDiscoveryOperation read(String owner, String operationId) {
        OperationEntry entry = owned(owner, operationId);
        ProviderModelDiscoveryOperation snapshot = entry.snapshot();
        if (snapshot.terminal()) {
            scheduleRemoval(entry, READ_TERMINAL_RETENTION);
        }
        return snapshot;
    }

    /**
     * 幂等取消当前 session 拥有的操作。
     *
     * @param owner 当前 RPC session 标识
     * @param identity cancel 写命令身份
     * @param operationId 服务端操作标识
     * @param reason 脱敏取消原因
     * @return 取消后的快照；已是终态时原样返回
     */
    public ProviderModelDiscoveryOperation cancel(
            String owner, CommandIdentity identity, String operationId, String reason) {
        String checkedOwner = text(owner, "owner");
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        String checkedOperationId = text(operationId, "operationId");
        String checkedReason = text(reason, "reason");
        SessionCommandKey commandKey = new SessionCommandKey(checkedOwner, checked.idempotencyKey());
        OperationEntry entry;
        ProviderModelDiscoveryOperation result;
        synchronized (lock) {
            requireOpen();
            CancelMemo prior = cancels.get(commandKey);
            if (prior != null) {
                if (!prior.requestDigest().equals(checked.requestDigest())) {
                    throw PersistenceException.idempotencyConflict(
                            "Provider model discovery cancel key belongs to another request");
                }
                return prior.entry().snapshot();
            }
            entry = ownedLocked(checkedOwner, checkedOperationId);
            long memoCount = cancels.values().stream()
                    .filter(memo -> memo.entry() == entry)
                    .count();
            if (memoCount >= MAXIMUM_CANCEL_MEMOS_PER_OPERATION) {
                throw PersistenceException.invalidRequest("Provider model discovery cancel capacity is exhausted");
            }
            ProviderModelDiscoveryOperation before = entry.snapshot();
            if (!before.terminal() && checked.expectedRevision() != before.revision()) {
                throw PersistenceException.revisionConflict("Provider model discovery operation revision changed");
            }
            entry.cancel(checkedReason, clock.instant());
            cancels.put(commandKey, new CancelMemo(checked.requestDigest(), entry));
            result = entry.snapshot();
        }
        scheduleRemoval(entry, READ_TERMINAL_RETENTION);
        return result;
    }

    /**
     * 取消并清理一个已关闭 session 的全部操作。
     *
     * @param owner 已关闭的 session 标识
     */
    public void cancelOwner(String owner) {
        String checkedOwner = text(owner, "owner");
        java.util.List<OperationEntry> owned;
        synchronized (lock) {
            owned = operations.values().stream()
                    .filter(entry -> entry.owner().equals(checkedOwner))
                    .toList();
        }
        Instant now = clock.instant();
        owned.forEach(entry -> entry.cancel("RPC session closed", now));
        owned.forEach(this::remove);
    }

    @Override
    public void close() {
        java.util.List<OperationEntry> active;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            active = java.util.List.copyOf(operations.values());
        }
        Instant now = clock.instant();
        active.forEach(entry -> entry.cancel("App Server shutting down", now));
        active.forEach(this::remove);
        workers.close();
        cleanup.shutdownNow();
    }

    private void execute(OperationEntry entry) {
        try {
            ProviderModelDiscoveryResult result = discovery.discover(entry.endpoint(), entry.cancellation());
            requireMatchingResult(entry.endpoint(), result);
            entry.succeed(result, clock.instant());
        } catch (RuntimeException failure) {
            if (entry.cancellation().isCancelled()) {
                entry.cancel(entry.cancellation().reason().orElse("cancelled"), clock.instant());
            } else {
                entry.fail("DISCOVERY_FAILED", clock.instant());
            }
        } finally {
            scheduleRemoval(entry, TERMINAL_RETENTION);
        }
    }

    private OperationEntry owned(String owner, String operationId) {
        String checkedOwner = text(owner, "owner");
        String checkedId = text(operationId, "operationId");
        synchronized (lock) {
            requireOpen();
            OperationEntry entry = operations.get(checkedId);
            return requireOwned(entry, checkedOwner);
        }
    }

    private OperationEntry ownedLocked(String owner, String operationId) {
        return requireOwned(operations.get(operationId), owner);
    }

    private static OperationEntry requireOwned(OperationEntry entry, String owner) {
        if (entry == null || !entry.owner().equals(owner)) {
            throw PersistenceException.invalidRequest("Provider model discovery operation is unavailable");
        }
        return entry;
    }

    private void requireCapacity(String owner) {
        if (operations.size() >= MAXIMUM_OPERATIONS) {
            throw PersistenceException.invalidRequest("Provider model discovery capacity is exhausted");
        }
        long sessionCount = operations.values().stream()
                .filter(entry -> entry.owner().equals(owner))
                .count();
        if (sessionCount >= MAXIMUM_OPERATIONS_PER_SESSION) {
            throw PersistenceException.invalidRequest("Provider model discovery session capacity is exhausted");
        }
    }

    private void scheduleRemoval(OperationEntry entry, Duration delay) {
        if (!entry.snapshot().terminal() || cleanup.isShutdown()) {
            return;
        }
        entry.scheduleCleanup(cleanup, () -> remove(entry), delay);
    }

    private void remove(OperationEntry entry) {
        synchronized (lock) {
            operations.remove(entry.id(), entry);
            StartMemo memo = starts.get(entry.commandKey());
            if (memo != null && memo.entry() == entry) {
                starts.remove(entry.commandKey());
            }
            cancels.entrySet().removeIf(cancel -> cancel.getValue().entry() == entry);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Provider model discovery service is closed");
        }
    }

    private static void requireMatchingResult(ProviderEndpoint endpoint, ProviderModelDiscoveryResult result) {
        if (!endpoint.id().equals(result.endpointId()) || endpoint.revision() != result.endpointRevision()) {
            throw new IllegalStateException("Provider model discovery returned a mismatched endpoint");
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty() || normalized.length() > 240) {
            throw new IllegalArgumentException(name + " length must be between 1 and 240");
        }
        return normalized;
    }

    /** 厂商模型目录的最小可替换边界。 */
    @FunctionalInterface
    interface DiscoveryPort {
        /**
         * 读取已校验 Provider 的目录。
         *
         * @param endpoint Provider 精确版本
         * @param cancellation 取消信号
         * @return 发现结果
         */
        ProviderModelDiscoveryResult discover(ProviderEndpoint endpoint, CancellationToken cancellation);
    }

    private record SessionCommandKey(String owner, String idempotencyKey) {}

    private record StartMemo(String requestDigest, OperationEntry entry) {}

    private record CancelMemo(String requestDigest, OperationEntry entry) {}

    private static final class OperationEntry {
        private final String id;
        private final String owner;
        private final SessionCommandKey commandKey;
        private final ProviderEndpoint endpoint;
        private final CancellationSource cancellation = new CancellationSource();
        private ProviderModelDiscoveryOperation snapshot;
        private Future<?> future;
        private ScheduledFuture<?> cleanupFuture;
        private Duration cleanupDelay;

        private OperationEntry(
                String id, String owner, SessionCommandKey commandKey, ProviderEndpoint endpoint, Instant createdAt) {
            this.id = id;
            this.owner = owner;
            this.commandKey = commandKey;
            this.endpoint = endpoint;
            snapshot = new ProviderModelDiscoveryOperation(
                    id,
                    1,
                    endpoint.id(),
                    endpoint.revision(),
                    ProviderModelDiscoveryOperationState.RUNNING,
                    Optional.empty(),
                    Optional.empty(),
                    createdAt,
                    createdAt);
        }

        synchronized void attach(Future<?> value) {
            future = Objects.requireNonNull(value, "future");
            if (cancellation.isCancelled()) {
                future.cancel(true);
            }
        }

        synchronized void succeed(ProviderModelDiscoveryResult result, Instant now) {
            if (snapshot.terminal()) {
                return;
            }
            snapshot = new ProviderModelDiscoveryOperation(
                    id,
                    snapshot.revision() + 1,
                    endpoint.id(),
                    endpoint.revision(),
                    ProviderModelDiscoveryOperationState.SUCCEEDED,
                    Optional.of(result),
                    Optional.empty(),
                    snapshot.createdAt(),
                    now);
        }

        synchronized void fail(String code, Instant now) {
            if (snapshot.terminal()) {
                return;
            }
            snapshot = new ProviderModelDiscoveryOperation(
                    id,
                    snapshot.revision() + 1,
                    endpoint.id(),
                    endpoint.revision(),
                    ProviderModelDiscoveryOperationState.FAILED,
                    Optional.empty(),
                    Optional.of(code),
                    snapshot.createdAt(),
                    now);
        }

        synchronized void cancel(String reason, Instant now) {
            cancellation.cancel(reason);
            if (!snapshot.terminal()) {
                snapshot = new ProviderModelDiscoveryOperation(
                        id,
                        snapshot.revision() + 1,
                        endpoint.id(),
                        endpoint.revision(),
                        ProviderModelDiscoveryOperationState.CANCELLED,
                        Optional.empty(),
                        Optional.empty(),
                        snapshot.createdAt(),
                        now);
            }
            if (future != null) {
                future.cancel(true);
            }
        }

        synchronized ProviderModelDiscoveryOperation snapshot() {
            return snapshot;
        }

        String id() {
            return id;
        }

        String owner() {
            return owner;
        }

        SessionCommandKey commandKey() {
            return commandKey;
        }

        ProviderEndpoint endpoint() {
            return endpoint;
        }

        CancellationSource cancellation() {
            return cancellation;
        }

        synchronized void scheduleCleanup(ScheduledExecutorService scheduler, Runnable removal, Duration delay) {
            if (cleanupDelay != null && cleanupDelay.compareTo(delay) <= 0) {
                return;
            }
            if (cleanupFuture != null) {
                cleanupFuture.cancel(false);
            }
            try {
                cleanupFuture = scheduler.schedule(removal, delay.toMillis(), TimeUnit.MILLISECONDS);
                cleanupDelay = delay;
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // App Server 正在关闭；close 会同步删除全部操作。
            }
        }
    }
}
