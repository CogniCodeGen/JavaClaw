package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ApprovalRequest;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;

/**
 * 持久化 Approval 状态机；等待使用虚拟线程短轮询，以同时响应决议、超时和 Turn 取消。
 *
 * <p><strong>并发不变量：</strong>同一审批的所有写事务先获取进程级审批锁。客户端命令先获取幂等键锁，再获取独立的 审批锁，固定顺序可避免 H2 行锁竞争和进程锁反转；数据库条件更新仍是最终一致性防线。
 *
 * <p><strong>中断不变量：</strong>可被调用方中断的等待线程不直接执行 JDBC。申请、轮询和终态写入由服务拥有的短生命周期虚拟线程完成，防止线程中断关闭 H2 MVStore 的共享文件通道。
 */
public final class ApprovalService implements AutoCloseable {
    private final H2Transactions transactions;
    private final ApprovalRepository approvals = new ApprovalRepository();
    private final TurnRepository turns = new TurnRepository();
    private final TurnExecutionCheckpointRepository checkpoints;
    private final ItemRepository items = new ItemRepository(turns);
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ApprovalRecord>> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService waitOperations = Executors.newVirtualThreadPerTaskExecutor();
    private final CanonicalJson json;
    private final Clock clock;
    private int activeWaiters;
    private volatile boolean closed;

    /**
     * 创建审批服务。
     *
     * @param database data-v6 数据库
     * @param json 共享规范 JSON codec
     * @param clock 平台时钟
     */
    public ApprovalService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        checkpoints = new TurnExecutionCheckpointRepository(json);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 注册事务提交后的审批状态通知。
     *
     * @param listener 生命周期或调度投影监听器
     */
    public void onChanged(Consumer<ApprovalRecord> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 持久化申请并等待终态。
     *
     * <p>实现说明：申请 Item 与 RUNNING→WAITING 同事务提交；决议 Item 与 WAITING→RUNNING 同事务提交。等待不持有数据库连接， 每 100 毫秒检查一次取消和绝对过期时间。
     *
     * @param request 不含原始参数的审批摘要
     * @param cancellation Turn 取消信号
     * @return 终态审批
     * @throws InterruptedException 等待线程被中断
     */
    public ApprovalRecord await(ApprovalRequest request, CancellationToken cancellation) throws InterruptedException {
        Objects.requireNonNull(cancellation, "cancellation");
        ApprovalRequest checkedRequest = Objects.requireNonNull(request, "request");
        CompletableFuture<Void> signal = registerWaiter(checkedRequest.id());
        try {
            ApprovalRecord current = runWaitOperation(() -> {
                ApprovalRecord created = begin(checkedRequest);
                notifyChanged(created);
                return created;
            });
            while (current.pending()) {
                requireNotInterrupted();
                if (cancellation.isCancelled()) {
                    ApprovalRecord expected = current;
                    runWaitOperation(() -> cancelPending(expected));
                    cancellation.throwIfCancelled();
                }
                if (expired(current, clock.instant())) {
                    ApprovalRecord expected = current;
                    current = runWaitOperation(() -> expirePending(expected));
                    continue;
                }
                if (closed) {
                    ApprovalRecord expected = current;
                    runWaitOperation(() -> cancelPending(expected));
                    throw new IllegalStateException("Approval service is closed");
                }
                awaitSignal(signal);
                requireNotInterrupted();
                if (closed) {
                    ApprovalRecord expected = current;
                    runWaitOperation(() -> cancelPending(expected));
                    throw new IllegalStateException("Approval service is closed");
                }
                current = runWaitOperation(() -> findRequired(checkedRequest.id()));
            }
            return current;
        } finally {
            waiters.remove(checkedRequest.id(), signal);
            waiterFinished();
        }
    }

    /**
     * 查询审批。
     *
     * @param turnId 可选 Turn 过滤
     * @param includeResolved 是否包含终态
     * @return 按创建时间排序的不可变列表
     */
    public List<ApprovalRecord> list(Optional<TurnId> turnId, boolean includeResolved) {
        return execute(
                connection -> approvals.list(connection, Objects.requireNonNull(turnId, "turnId"), includeResolved));
    }

    /**
     * 原子收口所有已经达到绝对失效时间的等待审批。
     *
     * <p>实现说明：列表仅用于寻找候选项；每个候选项仍会在审批写锁和数据库行锁内重新检查状态与 {@code expiresAt}。因此本方法与客户端决议竞态时只有一方能完成
     * PENDING→终态转换，另一方读取已提交终态，不会重复推进 Turn checkpoint。
     *
     * @return 本次从 PENDING 转为 EXPIRED 的数量
     */
    public int expireDue() {
        Instant observedAt = clock.instant();
        int expired = 0;
        for (ApprovalRecord candidate : list(Optional.empty(), false)) {
            if (!observedAt.isBefore(candidate.request().expiresAt())
                    && expireIfDue(candidate.request().id(), observedAt).isPresent()) {
                expired++;
            }
        }
        return expired;
    }

    /**
     * 幂等提交客户端决议。
     *
     * @param identity approval/resolve 命令身份；expected revision 必须匹配审批
     * @param payload 决议内容
     * @return 终态审批
     */
    public ApprovalRecord resolve(CommandIdentity identity, CoreRpcContracts.ApprovalResolvePayload payload) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(payload, "payload");
        if (identity.expectedRevision() < 1) {
            throw PersistenceException.invalidRequest("审批命令必须提供正数 expected revision");
        }
        ApprovalRecord result;
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            synchronized (ApprovalMutationLocks.forId(payload.approvalId())) {
                result = execute(connection -> {
                    Optional<IdempotencyRepository.StoredCommand> stored =
                            idempotency.find(connection, identity.idempotencyKey());
                    if (stored.isPresent()) {
                        return recover(identity, stored.orElseThrow());
                    }
                    ApprovalRecord current = approvals
                            .lock(connection, payload.approvalId())
                            .orElseThrow(() -> new PersistenceException("审批不存在"));
                    ApprovalRecord resolved = resolveDecision(connection, current, identity, payload);
                    idempotency.insert(connection, identity, json.encode(resolved), clock.instant());
                    return resolved;
                });
            }
        }
        signal(result.request().id());
        notifyChanged(result);
        return result;
    }

    /**
     * 在客户端批准后记录实时撤权；工具不得继续执行。
     *
     * @param approvalId 审批 ID
     * @param reason 脱敏原因
     * @return 撤权终态
     */
    public ApprovalRecord revokeApproved(String approvalId, String reason) {
        ApprovalRecord revoked;
        synchronized (ApprovalMutationLocks.forId(approvalId)) {
            revoked = execute(connection -> {
                ApprovalRecord current =
                        approvals.lock(connection, approvalId).orElseThrow(() -> new PersistenceException("审批不存在"));
                if (current.state() == ApprovalState.REVOKED) {
                    return current;
                }
                ApprovalRecord result = approvals.revokeApproved(connection, approvalId, reason, clock.instant());
                append(connection, result, ItemStatus.COMPLETED);
                return result;
            });
        }
        signal(approvalId);
        notifyChanged(revoked);
        return revoked;
    }

    /**
     * 审批通过后、进入真实外部工具前提交不可重放边界。
     *
     * @param request 已批准的精确工具申请
     */
    public void markApprovedExternalCall(ApprovalRequest request) {
        ApprovalRequest checked = Objects.requireNonNull(request, "request");
        execute(connection -> {
            ApprovalRecord current =
                    approvals.find(connection, checked.id()).orElseThrow(() -> new PersistenceException("审批不存在"));
            requireSameRequest(current, checked);
            if (current.state() != ApprovalState.APPROVED) {
                throw new PersistenceException("只有已批准请求可以进入外部调用");
            }
            checkpoints.markApprovedExternalCall(connection, checked, clock.instant());
            return null;
        });
    }

    private ApprovalRecord begin(ApprovalRequest request) {
        synchronized (ApprovalMutationLocks.forId(request.id())) {
            return execute(connection -> {
                Optional<ApprovalRecord> existing = approvals.lock(connection, request.id());
                if (existing.isPresent()) {
                    return requireSameRequest(existing.orElseThrow(), request);
                }
                AgentTurn turn = turns.find(connection, request.turnId())
                        .orElseThrow(() -> new PersistenceException("审批所属 Turn 不存在"));
                if (turn.status() != TurnStatus.RUNNING) {
                    throw new PersistenceException("只有运行中的 Turn 可以申请审批");
                }
                approvals.insert(connection, request);
                ApprovalRecord created =
                        approvals.find(connection, request.id()).orElseThrow();
                append(connection, created, ItemStatus.IN_PROGRESS);
                checkpoints.markApprovalWaiting(connection, request, clock.instant());
                turns.transition(
                        connection,
                        request.turnId(),
                        TurnStatus.RUNNING,
                        TurnStatus.WAITING,
                        Optional.empty(),
                        clock.instant());
                return created;
            });
        }
    }

    private ApprovalRecord resolveDecision(
            java.sql.Connection connection,
            ApprovalRecord current,
            CommandIdentity identity,
            CoreRpcContracts.ApprovalResolvePayload payload)
            throws Exception {
        if (current.state() == ApprovalState.EXPIRED) {
            if (current.revision() - 1 != identity.expectedRevision()) {
                throw PersistenceException.revisionConflict("审批 revision 已改变");
            }
            return current;
        }
        Instant resolvedAt = clock.instant();
        ApprovalState state = expired(current, resolvedAt) ? ApprovalState.EXPIRED : state(payload.decision());
        String reason = state == ApprovalState.EXPIRED ? "审批已超过有效期" : payload.reason();
        return finishPending(connection, current, identity.expectedRevision(), state, reason, resolvedAt);
    }

    private ApprovalRecord finishPending(
            java.sql.Connection connection,
            ApprovalRecord current,
            long expectedRevision,
            ApprovalState state,
            String reason,
            Instant resolvedAt)
            throws Exception {
        if (current.revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("审批 revision 已改变");
        }
        ApprovalRecord result =
                approvals.resolve(connection, current.request().id(), expectedRevision, state, reason, resolvedAt);
        append(connection, result, ItemStatus.COMPLETED);
        AgentTurn turn = turns.find(connection, current.request().turnId()).orElseThrow();
        if (turn.status() == TurnStatus.WAITING) {
            checkpoints.markApprovalResolved(connection, current.request(), resolvedAt);
            turns.transition(
                    connection, turn.id(), TurnStatus.WAITING, TurnStatus.RUNNING, Optional.empty(), resolvedAt);
        }
        return result;
    }

    private ApprovalRecord expirePending(ApprovalRecord current) {
        return terminalize(current, ApprovalState.EXPIRED, "审批已超过有效期");
    }

    private ApprovalRecord cancelPending(ApprovalRecord current) {
        return terminalize(current, ApprovalState.CANCELLED, "所属 Turn 已取消");
    }

    private ApprovalRecord terminalize(ApprovalRecord expected, ApprovalState state, String reason) {
        ApprovalRecord result;
        synchronized (ApprovalMutationLocks.forId(expected.request().id())) {
            result = execute(connection -> {
                ApprovalRecord current =
                        approvals.lock(connection, expected.request().id()).orElseThrow();
                if (!current.pending()) {
                    return current;
                }
                return finishPending(connection, current, current.revision(), state, reason, clock.instant());
            });
        }
        signal(result.request().id());
        notifyChanged(result);
        return result;
    }

    private Optional<ApprovalRecord> expireIfDue(String approvalId, Instant observedAt) {
        Optional<ApprovalRecord> expired;
        synchronized (ApprovalMutationLocks.forId(approvalId)) {
            expired = execute(connection -> {
                ApprovalRecord current =
                        approvals.lock(connection, approvalId).orElseThrow(() -> new PersistenceException("审批不存在"));
                if (!current.pending() || !expired(current, observedAt)) {
                    return Optional.empty();
                }
                return Optional.of(finishPending(
                        connection, current, current.revision(), ApprovalState.EXPIRED, "审批已超过有效期", observedAt));
            });
        }
        expired.ifPresent(record -> {
            signal(record.request().id());
            notifyChanged(record);
        });
        return expired;
    }

    private ApprovalRecord findRequired(String id) {
        return execute(connection -> approvals.find(connection, id).orElseThrow());
    }

    private synchronized CompletableFuture<Void> registerWaiter(String id) {
        if (closed) {
            throw new IllegalStateException("Approval service is closed");
        }
        activeWaiters++;
        return waiters.computeIfAbsent(id, ignored -> new CompletableFuture<>());
    }

    private <T> T runWaitOperation(Supplier<T> operation) throws InterruptedException {
        CompletableFuture<T> task;
        synchronized (this) {
            task = CompletableFuture.supplyAsync(operation, waitOperations);
        }
        try {
            return task.get();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Approval wait operation failed", cause);
        }
    }

    private synchronized void waiterFinished() {
        activeWaiters--;
        if (closed && activeWaiters == 0) {
            waitOperations.shutdown();
        }
    }

    private void append(java.sql.Connection connection, ApprovalRecord approval, ItemStatus itemStatus)
            throws Exception {
        String reason = approval.resolutionReason().orElse(approval.request().explanation());
        CorePayloads.Approval payload = new CorePayloads.Approval(
                approval.request().id(),
                approval.request().tool().name(),
                approval.request().risk(),
                approval.state(),
                reason);
        items.append(
                connection,
                new ItemRepository.ItemWrite(
                        approval.request().turnId(),
                        "approval",
                        CoreSchemas.APPROVAL,
                        "core",
                        itemStatus,
                        json.encode(payload),
                        clock.instant()));
    }

    private ApprovalRecord recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), ApprovalRecord.class);
    }

    private static ApprovalRecord requireSameRequest(ApprovalRecord existing, ApprovalRequest request) {
        ApprovalRequest saved = existing.request();
        boolean same = saved.id().equals(request.id())
                && saved.turnId().equals(request.turnId())
                && saved.callId().equals(request.callId())
                && saved.tool().equals(request.tool())
                && saved.risk() == request.risk()
                && saved.requestDigest().equals(request.requestDigest());
        if (!same) {
            throw new PersistenceException("审批 ID 已绑定其他工具调用");
        }
        return existing;
    }

    private static ApprovalState state(ApprovalDecision decision) {
        return decision == ApprovalDecision.APPROVED ? ApprovalState.APPROVED : ApprovalState.DENIED;
    }

    private static boolean expired(ApprovalRecord approval, Instant observedAt) {
        return !observedAt.isBefore(approval.request().expiresAt());
    }

    private static void awaitSignal(CompletableFuture<Void> signal) throws InterruptedException {
        try {
            signal.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            // 短等待使取消和绝对过期时间不依赖外部唤醒。
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Approval signal failed", failure.getCause());
        }
    }

    private static void requireNotInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Approval wait was interrupted");
        }
    }

    private void signal(String id) {
        CompletableFuture<Void> signal = waiters.remove(id);
        if (signal != null) {
            signal.complete(null);
        }
    }

    private void notifyChanged(ApprovalRecord record) {
        RuntimeException firstFailure = null;
        for (Consumer<ApprovalRecord> listener : listeners) {
            try {
                listener.accept(record);
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Approval 事务失败", failure);
        }
    }

    /** 终止新等待并唤醒现有虚拟线程；持久状态由等待方收口。 */
    @Override
    public void close() {
        List<CompletableFuture<Void>> pendingSignals;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pendingSignals = List.copyOf(waiters.values());
            if (activeWaiters == 0) {
                waitOperations.shutdown();
            }
        }
        pendingSignals.forEach(signal -> signal.complete(null));
    }
}
