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
import java.util.function.Supplier;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelPreviewOperation;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.model.CredentialMaterial;
import com.javaclaw.model.ProviderModelDiscoveryAdapter;
import com.javaclaw.model.ProviderModelPreviewException;

/**
 * 管理连接草稿的有界、只读模型目录预览，并与已保存目录发现共享额度。
 *
 * <p>操作与结果均不持久化。连接关闭会取消并删除该连接拥有的所有操作；终态只短暂保留，既允许本地 RPC 读取结果，也防止无人读取的结果无限占用内存。
 */
public final class ProviderModelPreviewService implements AutoCloseable {
    private static final int MAXIMUM_CANCEL_MEMOS_PER_OPERATION = 8;
    private static final Duration TERMINAL_RETENTION = Duration.ofSeconds(30);
    private static final Duration READ_TERMINAL_RETENTION = Duration.ofSeconds(1);

    private final Object lock = new Object();
    private final ProviderModelOperationCapacity capacity;
    private final ProviderModelPreviewCredentials credentials;
    private final PreviewPort discovery;
    private final Clock clock;
    private final ExecutorService workers;
    private final ScheduledExecutorService cleanup;
    private final Map<String, OperationEntry> operations = new HashMap<>();
    private final Map<SessionCommandKey, StartMemo> starts = new HashMap<>();
    private final Map<SessionCommandKey, CancelMemo> cancels = new HashMap<>();
    private boolean closed;

    /**
     * 创建不持久化草稿目录服务。
     *
     * @param providers Provider 精确版本服务
     * @param credentialReader 只读凭据快照边界
     * @param discovery 厂商模型目录 Adapter
     * @param savedDiscovery 当前服务实例的已保存目录发现，提供共同容量边界
     */
    public ProviderModelPreviewService(
            ProviderService providers,
            ProviderModelPreviewCredentialPort credentialReader,
            ProviderModelDiscoveryAdapter discovery,
            ProviderModelDiscoveryService savedDiscovery) {
        this(
                providers,
                credentialReader,
                (request, material, token) -> discovery.preview(
                        request.draftId(), request.generation(), request.connection(), material, token),
                savedDiscovery,
                Clock.systemUTC());
    }

    ProviderModelPreviewService(
            ProviderService providers,
            ProviderModelPreviewCredentialPort credentialReader,
            PreviewPort discovery,
            ProviderModelDiscoveryService savedDiscovery,
            Clock clock) {
        credentials = new ProviderModelPreviewCredentials(providers, credentialReader);
        capacity = Objects.requireNonNull(savedDiscovery, "savedDiscovery").capacity();
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.clock = Objects.requireNonNull(clock, "clock");
        workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("provider-model-preview-", 0).factory());
        cleanup = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "provider-model-preview-cleanup");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 登记草稿预览；仅首次有效请求会取得材料并执行网络读取。
     *
     * <p>幂等记录与容量校验先于解封，重复 start 不会再次消费 Secret envelope。材料在失败、完成、取消或关闭时清零。
     *
     * @param owner 当前 RPC session 标识
     * @param identity start 身份；expected revision 必须等于草稿代次
     * @param request 连接草稿
     * @param plaintext 延迟解封的临时 UTF-8 材料，仅 REPLACE 使用；返回数组由服务清零
     * @return 当前操作；同 session、幂等键和 payload 返回原操作
     */
    public ProviderModelPreviewOperation start(
            String owner, CommandIdentity identity, ProviderModelPreviewRequest request, Supplier<byte[]> plaintext) {
        String checkedOwner = text(owner, "owner");
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(plaintext, "plaintext");
        if (checked.expectedRevision() != request.generation()) {
            throw PersistenceException.revisionConflict("模型预览代次与命令版本不一致");
        }
        SessionCommandKey commandKey = new SessionCommandKey(checkedOwner, checked.idempotencyKey());
        synchronized (lock) {
            requireOpen();
            StartMemo prior = starts.get(commandKey);
            if (prior != null) {
                if (!prior.requestDigest().equals(checked.requestDigest())) {
                    throw PersistenceException.idempotencyConflict("模型预览幂等键属于其他请求");
                }
                return prior.entry().snapshot();
            }
            return startFirst(checkedOwner, commandKey, checked, request, plaintext);
        }
    }

    private ProviderModelPreviewOperation startFirst(
            String owner,
            SessionCommandKey key,
            CommandIdentity identity,
            ProviderModelPreviewRequest request,
            Supplier<byte[]> plaintext) {
        capacity.acquire(owner);
        Optional<CredentialMaterial> material;
        try {
            material = credentials.prepare(identity, request, plaintext);
        } catch (RuntimeException failure) {
            capacity.release(owner);
            throw failure;
        }
        OperationEntry entry =
                new OperationEntry(UUID.randomUUID().toString(), owner, key, request, material, clock.instant());
        operations.put(entry.id(), entry);
        starts.put(key, new StartMemo(identity.requestDigest(), entry));
        try {
            entry.attach(workers.submit(() -> execute(entry)));
        } catch (RuntimeException failure) {
            entry.releaseMaterial();
            remove(entry);
            throw failure;
        }
        return entry.snapshot();
    }

    /**
     * 读取当前 session 拥有的操作。
     *
     * @param owner 当前 RPC session 标识
     * @param operationId 服务端操作标识
     * @return 当前快照
     */
    public ProviderModelPreviewOperation read(String owner, String operationId) {
        OperationEntry entry = owned(owner, operationId);
        ProviderModelPreviewOperation snapshot = entry.snapshot();
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
    public ProviderModelPreviewOperation cancel(
            String owner, CommandIdentity identity, String operationId, String reason) {
        String checkedOwner = text(owner, "owner");
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        String checkedOperationId = text(operationId, "operationId");
        String checkedReason = text(reason, "reason");
        SessionCommandKey commandKey = new SessionCommandKey(checkedOwner, checked.idempotencyKey());
        OperationEntry entry;
        ProviderModelPreviewOperation result;
        synchronized (lock) {
            requireOpen();
            CancelMemo prior = cancels.get(commandKey);
            if (prior != null) {
                if (!prior.requestDigest().equals(checked.requestDigest())) {
                    throw PersistenceException.idempotencyConflict(
                            "Provider model preview cancel key belongs to another request");
                }
                return prior.entry().snapshot();
            }
            entry = ownedLocked(checkedOwner, checkedOperationId);
            long memoCount = cancels.values().stream()
                    .filter(memo -> memo.entry() == entry)
                    .count();
            if (memoCount >= MAXIMUM_CANCEL_MEMOS_PER_OPERATION) {
                throw PersistenceException.invalidRequest("Provider model preview cancel capacity is exhausted");
            }
            ProviderModelPreviewOperation before = entry.snapshot();
            if (!before.terminal() && checked.expectedRevision() != before.revision()) {
                throw PersistenceException.revisionConflict("Provider model preview operation revision changed");
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
            ProviderModelPreviewResult result =
                    discovery.preview(entry.request(), entry.material(), entry.cancellation());
            requireMatchingResult(entry.request(), result);
            entry.releaseMaterial();
            entry.succeed(result, clock.instant());
        } catch (RuntimeException failure) {
            entry.releaseMaterial();
            if (entry.cancellation().isCancelled()) {
                entry.cancel(entry.cancellation().reason().orElse("cancelled"), clock.instant());
            } else {
                String code = failure instanceof ProviderModelPreviewException classified
                        ? classified.code().name()
                        : "PREVIEW_FAILED";
                entry.fail(code, clock.instant());
            }
        } finally {
            entry.releaseMaterial();
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
            throw PersistenceException.invalidRequest("Provider model preview operation is unavailable");
        }
        return entry;
    }

    private void scheduleRemoval(OperationEntry entry, Duration delay) {
        if (!entry.snapshot().terminal() || cleanup.isShutdown()) {
            return;
        }
        entry.scheduleCleanup(cleanup, () -> remove(entry), delay);
    }

    private void remove(OperationEntry entry) {
        synchronized (lock) {
            if (operations.remove(entry.id(), entry)) {
                capacity.release(entry.owner());
            }
            StartMemo memo = starts.get(entry.commandKey());
            if (memo != null && memo.entry() == entry) {
                starts.remove(entry.commandKey());
            }
            cancels.entrySet().removeIf(cancel -> cancel.getValue().entry() == entry);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Provider model preview service is closed");
        }
    }

    private static void requireMatchingResult(ProviderModelPreviewRequest request, ProviderModelPreviewResult result) {
        if (!request.draftId().equals(result.draftId()) || request.generation() != result.generation()) {
            throw new IllegalStateException("Provider model preview returned a mismatched endpoint");
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
    interface PreviewPort {
        /**
         * 读取已校验连接草稿的目录。
         *
         * @param request 连接草稿
         * @param material 借用凭据材料，由服务关闭
         * @param cancellation 取消信号
         * @return 发现结果
         */
        ProviderModelPreviewResult preview(
                ProviderModelPreviewRequest request,
                Optional<CredentialMaterial> material,
                CancellationToken cancellation);
    }

    private record SessionCommandKey(String owner, String idempotencyKey) {}

    private record StartMemo(String requestDigest, OperationEntry entry) {}

    private record CancelMemo(String requestDigest, OperationEntry entry) {}

    private static final class OperationEntry {
        private final String id;
        private final String owner;
        private final SessionCommandKey commandKey;
        private final ProviderModelPreviewRequest request;
        private final Optional<CredentialMaterial> material;
        private final CancellationSource cancellation = new CancellationSource();
        private ProviderModelPreviewOperation snapshot;
        private Future<?> future;
        private ScheduledFuture<?> cleanupFuture;
        private Duration cleanupDelay;

        private OperationEntry(
                String id,
                String owner,
                SessionCommandKey commandKey,
                ProviderModelPreviewRequest request,
                Optional<CredentialMaterial> material,
                Instant createdAt) {
            this.id = id;
            this.owner = owner;
            this.commandKey = commandKey;
            this.request = request;
            this.material = material;
            snapshot = new ProviderModelPreviewOperation(
                    id,
                    1,
                    request.draftId(),
                    request.generation(),
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

        synchronized void succeed(ProviderModelPreviewResult result, Instant now) {
            if (snapshot.terminal()) {
                return;
            }
            snapshot = new ProviderModelPreviewOperation(
                    id,
                    snapshot.revision() + 1,
                    request.draftId(),
                    request.generation(),
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
            snapshot = new ProviderModelPreviewOperation(
                    id,
                    snapshot.revision() + 1,
                    request.draftId(),
                    request.generation(),
                    ProviderModelDiscoveryOperationState.FAILED,
                    Optional.empty(),
                    Optional.of(code),
                    snapshot.createdAt(),
                    now);
        }

        synchronized void cancel(String reason, Instant now) {
            cancellation.cancel(reason);
            releaseMaterial();
            if (!snapshot.terminal()) {
                snapshot = new ProviderModelPreviewOperation(
                        id,
                        snapshot.revision() + 1,
                        request.draftId(),
                        request.generation(),
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

        synchronized ProviderModelPreviewOperation snapshot() {
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

        ProviderModelPreviewRequest request() {
            return request;
        }

        Optional<CredentialMaterial> material() {
            return material;
        }

        void releaseMaterial() {
            material.ifPresent(CredentialMaterial::close);
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
