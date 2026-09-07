package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.extension.spi.InputRequestPort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.InputJobRpcContracts;

/**
 * 持久化 InputRequest 状态机与 Turn/Item 事务协调。
 *
 * <p><strong>事务不变量：</strong>请求与 WAITING Turn、决议与 RUNNING Turn、对应 Core Item 均在同一个 SERIALIZABLE
 * 事务中提交。响应通知只在提交后发出，不得据通知替代权威读取。
 */
public final class InputRequestService implements InputRequestPort {
    private static final java.util.Set<String> FORBIDDEN_SECRET_FIELDS = java.util.Set.of(
            "password", "passphrase", "secret", "token", "apiKey", "cookie", "storageState", "privateKey");

    private final H2Transactions transactions;
    private final InputRequestRepository requests = new InputRequestRepository();
    private final TurnRepository turns = new TurnRepository();
    private final ItemRepository items = new ItemRepository(turns);
    private final IdempotentCommandStore commands = new IdempotentCommandStore();
    private final CopyOnWriteArrayList<Consumer<InputRequestRecord>> listeners = new CopyOnWriteArrayList<>();
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建输入请求服务。
     *
     * @param database data-v6 数据库
     * @param json 共享规范 JSON codec
     * @param clock 平台时钟
     */
    public InputRequestService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 注册提交后状态通知；创建与终态变化都会发布。
     *
     * <p>监听器在事务提交后同步运行。失败不会回滚权威状态，但会向调用方报告，使其可以使用同一幂等身份重试并重建生命周期投影。
     *
     * @param listener 已提交状态监听器
     */
    public void onChanged(Consumer<InputRequestRecord> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** {@inheritDoc} */
    @Override
    public InputRequestRecord open(InputRequest request) {
        InputRequest checked = Objects.requireNonNull(request, "request");
        if (!clock.instant().isBefore(checked.expiresAt())) {
            throw PersistenceException.invalidRequest("输入请求已经过期");
        }
        requireNoSecretFields(checked.responseSchema());
        InputRequestRecord opened = execute(connection -> open(connection, checked));
        notifyChanged(opened);
        return opened;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<InputRequestRecord> find(String requestId) {
        String id = identifier(requestId);
        return execute(connection -> requests.find(connection, id));
    }

    /** {@inheritDoc} */
    @Override
    public InputRequestRecord completeResolved(String requestId, String producerId) {
        String id = identifier(requestId);
        String producer = identifier(producerId);
        return execute(connection -> {
            InputRequestRecord current =
                    requests.lock(connection, id).orElseThrow(() -> PersistenceException.invalidRequest("输入请求不存在"));
            if (!current.request().producerId().equals(producer) || current.state() != InputRequestState.RESOLVED) {
                throw PersistenceException.invalidRequest("只有所属 Workflow 可以关闭已决议输入");
            }
            completeLinkedTurn(connection, current);
            return current;
        });
    }

    /**
     * 列出输入请求。
     *
     * @param turnId 可选 Turn 过滤
     * @param includeResolved 是否包含终态
     * @return 按创建时间排序的不可变列表
     */
    public List<InputRequestRecord> list(Optional<TurnId> turnId, boolean includeResolved) {
        Objects.requireNonNull(turnId, "turnId");
        return execute(connection -> requests.list(connection, turnId, includeResolved));
    }

    /**
     * 打开请求并以短等待周期等待客户端决议。
     *
     * <p>等待只轮询权威 H2 状态，不依赖易丢失的进程内通知。取消会写入 CANCELLED 并把 Turn 从 WAITING 恢复为 RUNNING，使 Harness 可以安全收口。
     *
     * @param request 输入请求
     * @param cancellation 父 Turn 取消信号
     * @return 已决议响应；拒绝、取消或过期为空
     * @throws InterruptedException 当前虚拟线程被中断
     */
    public Optional<CanonicalPayload> await(InputRequest request, CancellationToken cancellation)
            throws InterruptedException {
        InputRequestRecord current = open(request);
        while (current.pending()) {
            if (cancellation.isCancelled()) {
                current = cancel(request.id(), request.producerId(), "所属 Turn 已取消");
                break;
            }
            if (!clock.instant().isBefore(request.expiresAt())) {
                expire(request.id());
            }
            current = find(request.id()).orElseThrow();
            if (current.pending()) {
                Thread.sleep(50);
            }
        }
        return current.response();
    }

    /**
     * 由请求所属平台取消仍在等待的输入。
     *
     * @param requestId 请求标识
     * @param producerId 原始生产者
     * @param reason 脱敏原因
     * @return 当前或新 CANCELLED 状态
     */
    public InputRequestRecord cancel(String requestId, String producerId, String reason) {
        String id = identifier(requestId);
        String producer = identifier(producerId);
        String detail = Objects.requireNonNull(reason, "reason").strip();
        if (detail.isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        InputRequestRecord result = execute(connection -> {
            InputRequestRecord current =
                    requests.lock(connection, id).orElseThrow(() -> PersistenceException.invalidRequest("输入请求不存在"));
            if (!current.request().producerId().equals(producer)) {
                throw PersistenceException.invalidRequest("只有输入请求生产者可以取消");
            }
            return current.pending()
                    ? finish(connection, current, InputRequestState.CANCELLED, Optional.empty(), Optional.of(detail))
                    : current;
        });
        notifyChanged(result);
        return result;
    }

    /**
     * 幂等提交用户输入；expected revision 必须匹配等待请求。
     *
     * @param identity turn/input/resolve 命令身份
     * @param payload 请求 ID 与规范响应
     * @return 已决议或因到期而关闭的请求
     */
    public InputRequestRecord resolve(CommandIdentity identity, InputJobRpcContracts.InputResolvePayload payload) {
        CommandIdentity checkedIdentity = Objects.requireNonNull(identity, "identity");
        InputJobRpcContracts.InputResolvePayload checkedPayload = Objects.requireNonNull(payload, "payload");
        if (checkedIdentity.expectedRevision() < 1) {
            throw PersistenceException.invalidRequest("输入决议必须提供正数 expected revision");
        }
        requireNoSecretFields(checkedPayload.response());
        InputRequestRecord resolved;
        synchronized (CommandLocks.forKey(checkedIdentity.idempotencyKey())) {
            resolved = execute(connection -> resolve(connection, checkedIdentity, checkedPayload));
        }
        notifyChanged(resolved);
        return resolved;
    }

    /**
     * 将已经超过绝对有效期的等待请求收口。
     *
     * @return 本次关闭数量
     */
    public int expireDue() {
        List<InputRequestRecord> pending = list(Optional.empty(), false);
        int expired = 0;
        for (InputRequestRecord record : pending) {
            if (!clock.instant().isBefore(record.request().expiresAt())
                    && expire(record.request().id())) {
                expired++;
            }
        }
        return expired;
    }

    private InputRequestRecord open(Connection connection, InputRequest request) throws Exception {
        Optional<InputRequestRecord> existing = requests.lock(connection, request.id());
        if (existing.isPresent()) {
            return requireSameRequest(existing.orElseThrow(), request);
        }
        AgentTurn turn = turns.find(connection, request.turnId())
                .orElseThrow(() -> PersistenceException.invalidRequest("输入请求所属 Turn 不存在"));
        if (turn.status() != TurnStatus.RUNNING) {
            throw PersistenceException.invalidRequest("只有运行中的 Turn 可以请求输入");
        }
        requests.insert(connection, request);
        InputRequestRecord created = requests.find(connection, request.id()).orElseThrow();
        append(connection, created, ItemStatus.IN_PROGRESS);
        turns.transition(
                connection,
                request.turnId(),
                TurnStatus.RUNNING,
                TurnStatus.WAITING,
                Optional.empty(),
                clock.instant());
        return created;
    }

    private InputRequestRecord resolve(
            Connection connection, CommandIdentity identity, InputJobRpcContracts.InputResolvePayload payload)
            throws Exception {
        Optional<CanonicalPayload> stored = commands.recover(connection, identity);
        if (stored.isPresent()) {
            return json.decode(stored.orElseThrow(), InputRequestRecord.class);
        }
        InputRequestRecord current = requests.lock(connection, payload.requestId())
                .orElseThrow(() -> PersistenceException.invalidRequest("输入请求不存在"));
        requirePendingRevision(current, identity.expectedRevision());
        boolean expired = !clock.instant().isBefore(current.request().expiresAt());
        InputRequestRecord result = finish(
                connection,
                current,
                expired ? InputRequestState.EXPIRED : InputRequestState.RESOLVED,
                expired ? Optional.empty() : Optional.of(payload.response()),
                expired ? Optional.of("输入请求已超过有效期") : Optional.empty());
        commands.record(connection, identity, json.encode(result), clock.instant());
        return result;
    }

    private boolean expire(String requestId) {
        Optional<InputRequestRecord> result = execute(connection -> expireDue(connection, requestId, clock.instant()));
        result.ifPresent(this::notifyChanged);
        return result.isPresent();
    }

    Optional<InputRequestRecord> expireLinked(
            Connection connection, String requestId, TurnId expectedTurnId, Instant now) throws Exception {
        InputRequestRecord current = requests.lock(connection, requestId)
                .orElseThrow(() -> PersistenceException.invalidRequest("关联的 InputRequest 不存在"));
        if (!current.request().turnId().equals(expectedTurnId)) {
            throw PersistenceException.invalidRequest("关联的 InputRequest Turn 已改变");
        }
        if (!current.pending() || now.isBefore(current.request().expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(
                finish(connection, current, InputRequestState.EXPIRED, Optional.empty(), Optional.of("输入请求已超过有效期")));
    }

    Optional<InputRequestRecord> cancelLinked(
            Connection connection, String requestId, TurnId expectedTurnId, String reason) throws Exception {
        InputRequestRecord current = requests.lock(connection, requestId)
                .orElseThrow(() -> PersistenceException.invalidRequest("关联的 InputRequest 不存在"));
        if (!current.request().turnId().equals(expectedTurnId)) {
            throw PersistenceException.invalidRequest("关联的 InputRequest Turn 已改变");
        }
        if (!current.pending()) {
            return Optional.empty();
        }
        return Optional.of(finish(
                connection,
                current,
                InputRequestState.CANCELLED,
                Optional.empty(),
                Optional.of(Objects.requireNonNull(reason, "reason"))));
    }

    void publishChanged(InputRequestRecord record) {
        notifyChanged(record);
    }

    void completeLinked(Connection connection, String requestId, TurnId expectedTurnId) throws Exception {
        InputRequestRecord current = requests.lock(connection, requestId)
                .orElseThrow(() -> PersistenceException.invalidRequest("关联的 InputRequest 不存在"));
        if (!current.request().turnId().equals(expectedTurnId) || current.pending()) {
            throw PersistenceException.invalidRequest("关联的 InputRequest 尚未结束或 Turn 已改变");
        }
        completeLinkedTurn(connection, current);
    }

    private Optional<InputRequestRecord> expireDue(Connection connection, String requestId, Instant now)
            throws Exception {
        InputRequestRecord current = requests.lock(connection, requestId).orElseThrow();
        if (!current.pending() || now.isBefore(current.request().expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(
                finish(connection, current, InputRequestState.EXPIRED, Optional.empty(), Optional.of("输入请求已超过有效期")));
    }

    private InputRequestRecord finish(
            Connection connection,
            InputRequestRecord current,
            InputRequestState state,
            Optional<CanonicalPayload> response,
            Optional<String> reason)
            throws Exception {
        InputRequestRecord result = requests.resolve(
                connection, current.request().id(), current.revision(), state, response, reason, clock.instant());
        append(connection, result, ItemStatus.COMPLETED);
        turns.transition(
                connection,
                current.request().turnId(),
                TurnStatus.WAITING,
                TurnStatus.RUNNING,
                Optional.empty(),
                clock.instant());
        return result;
    }

    private void completeLinkedTurn(Connection connection, InputRequestRecord current) throws Exception {
        AgentTurn turn = turns.find(connection, current.request().turnId()).orElseThrow();
        if (turn.status() == TurnStatus.COMPLETED) {
            return;
        }
        turns.transition(
                connection,
                current.request().turnId(),
                TurnStatus.RUNNING,
                TurnStatus.COMPLETED,
                Optional.empty(),
                clock.instant());
    }

    private void append(Connection connection, InputRequestRecord record, ItemStatus status) throws Exception {
        CorePayloads.Input payload =
                new CorePayloads.Input(record.request().id(), record.request().prompt(), false, !record.pending());
        items.append(
                connection,
                new ItemRepository.ItemWrite(
                        record.request().turnId(),
                        "input",
                        CoreSchemas.INPUT,
                        record.request().producerId(),
                        status,
                        json.encode(payload),
                        clock.instant()));
    }

    private void notifyChanged(InputRequestRecord record) {
        RuntimeException firstFailure = null;
        for (Consumer<InputRequestRecord> listener : listeners) {
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

    private static InputRequestRecord requireSameRequest(InputRequestRecord current, InputRequest request) {
        if (!current.request().equals(request)) {
            throw PersistenceException.idempotencyConflict("输入请求 ID 已绑定其他内容");
        }
        return current;
    }

    private static void requirePendingRevision(InputRequestRecord current, long expectedRevision) {
        if (!current.pending()) {
            throw PersistenceException.revisionConflict("输入请求已经结束");
        }
        if (current.revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("输入请求 revision 已改变");
        }
    }

    private static String identifier(String value) {
        String normalized = Objects.requireNonNull(value, "requestId").strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("requestId contains unsupported characters");
        }
        return normalized;
    }

    private void requireNoSecretFields(CanonicalPayload payload) {
        if (json.containsAnyField(payload, FORBIDDEN_SECRET_FIELDS)) {
            throw PersistenceException.invalidRequest("输入请求不能收集 Secret；请使用 Vault 平台动作");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("InputRequest 事务失败", failure);
        }
    }
}
