package com.javaclaw.server.security.grant;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Grant;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.GrantRef;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Preview;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Snapshot;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.IdempotentCommandStore;
import com.javaclaw.server.persistence.PersistenceException;

/**
 * 浏览器专用来源授权；预览确认、撤销与审计在同一事务提交。
 *
 * <p>ASSISTANT 权限仅来自 Turn 创建事务中固定的授权；每次请求再次检查持久快照与最新安全版本。 HUMAN 检查仅证明来源可用，调用者仍须验证人工接管租约；本服务不扩大普通网络策略或 MCP 权限。
 */
public final class BrowserGrantService {
    private static final Duration PREVIEW_VALIDITY = Duration.ofMinutes(5);
    private static final Instant ALL_HISTORY = Instant.parse("9999-12-31T23:59:59Z");
    private final H2Transactions transactions;
    private final BrowserGrantRepository repository;
    private final IdempotentCommandStore commands = new IdempotentCommandStore();
    private final CanonicalJson json;
    private final Clock clock;
    private final CopyOnWriteArrayList<Consumer<Scope>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 创建来源授权服务；不启动网络或后台任务。
     *
     * @param database data-v6 数据库
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public BrowserGrantService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        repository = new BrowserGrantRepository(json);
    }

    /**
     * 生成并持久保存五分钟有效的用户确认预览；不会创建活动授权。
     *
     * @param workspaceId Workspace
     * @param threadId 唯一 Thread
     * @param origin 精确 HTTPS 来源
     * @return 由服务端签发的完整预览
     */
    public Preview preview(WorkspaceId workspaceId, ThreadId threadId, URI origin) {
        Scope scope = new Scope(workspaceId, threadId);
        URI checkedOrigin = BrowserGrantContracts.normalizeOrigin(origin);
        return execute(connection -> {
            repository.requireThread(connection, workspaceId, threadId);
            return uniquePreview(connection, scope, checkedOrigin);
        });
    }

    /**
     * 确认一个已签发预览；调用方只能由明确的用户确认操作触发此命令。
     *
     * @param identity 创建版本为零的幂等命令身份
     * @param preview 用户看到并确认的完整预览
     * @return 新授权或同一命令恢复的原结果
     */
    public Grant confirm(CommandIdentity identity, Preview preview) {
        Objects.requireNonNull(identity, "identity").requireCreate();
        Objects.requireNonNull(preview, "preview");
        Grant result;
        synchronized (GrantCommandLocks.forKey(identity.idempotencyKey())) {
            result = execute(connection -> {
                var recovered = commands.recover(connection, identity);
                if (recovered.isPresent()) {
                    return json.decode(recovered.orElseThrow(), Grant.class);
                }
                requireFresh(preview);
                repository.requireThread(connection, preview.workspaceId(), preview.threadId());
                requireOriginCapacity(connection, preview);
                repository.consumePreview(connection, preview);
                Grant grant = new Grant(
                        UUID.randomUUID().toString(),
                        1,
                        SecurityGrantState.ACTIVE,
                        preview.workspaceId(),
                        preview.threadId(),
                        preview.origin(),
                        repository.grantTime(connection, preview.threadId(), now()));
                repository.insertGrant(connection, grant);
                record(connection, scope(grant), decision("confirm", grant.origin(), Optional.of(grant), "用户确认"));
                commands.record(connection, identity, json.encode(grant), now());
                return grant;
            });
        }
        changed(scope(result));
        return result;
    }

    /**
     * 写入不可逆撤销版本；提交完成后通知本实例监听器，网络检查始终实时读取数据库。
     *
     * @param identity 期望版本必须等于当前安全版本
     * @param id 授权 UUID
     * @return 撤销版本或幂等恢复的原结果
     */
    public Grant revoke(CommandIdentity identity, String id) {
        Objects.requireNonNull(identity, "identity");
        String checkedId = UUID.fromString(id).toString();
        Grant result;
        synchronized (GrantCommandLocks.forKey(identity.idempotencyKey())) {
            result = execute(connection -> {
                var recovered = commands.recover(connection, identity);
                if (recovered.isPresent()) {
                    return json.decode(recovered.orElseThrow(), Grant.class);
                }
                Grant found = repository
                        .latest(connection, checkedId)
                        .orElseThrow(() -> PersistenceException.invalidRequest("浏览器授权不存在"));
                repository.requireThread(connection, found.workspaceId(), found.threadId());
                Grant current = repository.latest(connection, checkedId).orElseThrow();
                if (current.revision() != identity.expectedRevision()) {
                    throw PersistenceException.revisionConflict("浏览器授权安全版本已改变");
                }
                if (current.state() == SecurityGrantState.REVOKED) {
                    throw PersistenceException.invalidRequest("浏览器授权已经撤销");
                }
                Grant revoked = new Grant(
                        current.id(),
                        Math.addExact(current.revision(), 1),
                        SecurityGrantState.REVOKED,
                        current.workspaceId(),
                        current.threadId(),
                        current.origin(),
                        now());
                repository.insertGrant(connection, revoked);
                record(connection, scope(revoked), decision("revoke", revoked.origin(), Optional.of(revoked), "用户撤销"));
                commands.record(connection, identity, json.encode(revoked), now());
                return revoked;
            });
        }
        changed(scope(result));
        return result;
    }

    /**
     * 恢复 Turn 创建事务中固定的来源；仅历史未保存快照的 Turn 使用严格时间边界补建。
     *
     * @param workspaceId Workspace
     * @param threadId Thread
     * @param turnId 当前 Turn，必须属于上述作用域
     * @return 不会因后来新授权而扩大的快照
     */
    public Snapshot freeze(WorkspaceId workspaceId, ThreadId threadId, TurnId turnId) {
        Scope scope = new Scope(workspaceId, threadId);
        Objects.requireNonNull(turnId, "turnId");
        synchronized (GrantCommandLocks.forKey("browser-freeze:" + turnId)) {
            return execute(connection -> {
                Instant turnCreatedAt = repository.requireTurn(connection, workspaceId, threadId, turnId);
                Optional<Snapshot> existing = repository.snapshot(connection, turnId);
                if (existing.isPresent()) {
                    return requireScope(existing.orElseThrow(), scope);
                }
                Map<URI, GrantRef> refs = new LinkedHashMap<>();
                // 仅兼容旧数据：严格早于，避免同时间戳的新确认被装入已有 Turn。
                for (Grant grant : repository.latestAt(connection, threadId, turnCreatedAt)) {
                    if (grant.state() == SecurityGrantState.ACTIVE) {
                        refs.putIfAbsent(grant.origin(), new GrantRef(grant.id(), grant.revision()));
                    }
                }
                Snapshot snapshot =
                        new Snapshot(UUID.randomUUID().toString(), workspaceId, threadId, turnId, refs, now());
                repository.insertSnapshot(connection, snapshot);
                return snapshot;
            });
        }
    }

    /**
     * 检查 ASSISTANT 当前请求；拒绝也提交脱敏审计，再向调用方抛出安全异常。
     *
     * @param snapshot 服务端曾持久冻结的快照
     * @param origin 本次请求的精确 HTTPS 来源
     * @return 当前仍有效的精确授权版本
     */
    public Grant requireAuthorized(Snapshot snapshot, URI origin) {
        Objects.requireNonNull(snapshot, "snapshot");
        URI checked = BrowserGrantContracts.normalizeOrigin(origin);
        Scope scope = new Scope(snapshot.workspaceId(), snapshot.threadId());
        Authorization result = execute(connection -> {
            repository.requireTurn(connection, scope.workspaceId(), scope.threadId(), snapshot.turnId());
            boolean authentic = repository
                    .snapshot(connection, snapshot.turnId())
                    .filter(snapshot::equals)
                    .isPresent();
            GrantRef ref = snapshot.grants().get(checked);
            Optional<Grant> grant =
                    authentic && ref != null ? repository.latest(connection, ref.id()) : Optional.empty();
            Optional<Grant> allowed = grant.filter(value -> matches(value, scope, checked))
                    .filter(value -> ref != null && value.revision() == ref.revision());
            Decision decision = decision(
                    "assistant-connect", checked, allowed, allowed.isPresent() ? "冻结授权及实时版本有效" : "来源未冻结、快照不匹配或授权已撤销");
            record(connection, scope, decision);
            return new Authorization(allowed, decision.reason());
        });
        return result.require();
    }

    /**
     * 检查人工接管请求的当前来源授权；不授予 HUMAN 操作租约。
     *
     * @param workspaceId Workspace
     * @param threadId Thread
     * @param origin 精确 HTTPS 来源
     * @return 当前活动授权；没有授权时抛出安全异常
     */
    public Grant requireHumanAuthorized(WorkspaceId workspaceId, ThreadId threadId, URI origin) {
        Scope scope = new Scope(workspaceId, threadId);
        URI checked = BrowserGrantContracts.normalizeOrigin(origin);
        Authorization result = execute(connection -> {
            repository.requireThread(connection, workspaceId, threadId);
            Optional<Grant> allowed = repository.latestAt(connection, threadId, ALL_HISTORY).stream()
                    .filter(value -> matches(value, scope, checked))
                    .findFirst();
            Decision decision =
                    decision("human-connect", checked, allowed, allowed.isPresent() ? "当前来源授权有效" : "当前来源未授权或已撤销");
            record(connection, scope, decision);
            return new Authorization(allowed, decision.reason());
        });
        return result.require();
    }

    /**
     * 读取每个授权的最新版本，包含撤销记录。
     *
     * @param workspaceId Workspace
     * @param threadId Thread
     * @return 不可变授权列表
     */
    public List<Grant> listLatest(WorkspaceId workspaceId, ThreadId threadId) {
        new Scope(workspaceId, threadId);
        return execute(connection -> {
            repository.requireThread(connection, workspaceId, threadId);
            return repository.latestAt(connection, threadId, ALL_HISTORY);
        });
    }

    /**
     * 列出当前活动来源；网络操作仍需再次调用对应的实时核验方法。
     *
     * @param workspaceId Workspace
     * @param threadId Thread
     * @return 当前活动来源集合
     */
    public Set<URI> currentOrigins(WorkspaceId workspaceId, ThreadId threadId) {
        return listLatest(workspaceId, threadId).stream()
                .filter(grant -> grant.state() == SecurityGrantState.ACTIVE)
                .map(Grant::origin)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 订阅当前实例已提交的变化；其他实例仍由请求复核与宿主定时检查兜底。
     *
     * @param listener 只接收作用域，不接收凭据或页面数据
     * @return 关闭后取消订阅
     */
    public AutoCloseable onChanged(Consumer<Scope> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    /**
     * 读取当前 Thread 最近的脱敏审计；不包含页面 URL 路径、请求头或正文。
     *
     * @param workspaceId Workspace
     * @param threadId Thread
     * @param limit 返回数量，1 至 500
     * @return 新到旧排列的记录
     */
    public List<Decision> audit(WorkspaceId workspaceId, ThreadId threadId, int limit) {
        new Scope(workspaceId, threadId);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Browser audit limit must be between 1 and 500");
        }
        return execute(connection -> {
            repository.requireThread(connection, workspaceId, threadId);
            return repository.audit(connection, threadId, limit);
        });
    }

    private void requireFresh(Preview preview) {
        Duration remaining = Duration.between(now(), preview.expiresAt());
        PreviewContent content = new PreviewContent(
                new Scope(preview.workspaceId(), preview.threadId()), preview.origin(), preview.expiresAt());
        if (remaining.isNegative()
                || remaining.isZero()
                || remaining.compareTo(PREVIEW_VALIDITY) > 0
                || !preview.digest().equals(json.encode(content).sha256())) {
            throw PersistenceException.invalidRequest("浏览器授权预览已过期或内容摘要不匹配");
        }
    }

    private Preview uniquePreview(java.sql.Connection connection, Scope scope, URI origin)
            throws java.sql.SQLException {
        // 最多扣除一毫秒且检查持久唯一性；静止或回拨时钟不能复用已消费预览，也不延长五分钟上限。
        for (int attempt = 0; attempt < 8; attempt++) {
            long jitter = Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000L);
            Instant expiresAt = now().plus(PREVIEW_VALIDITY).minusNanos(jitter);
            PreviewContent content = new PreviewContent(scope, origin, expiresAt);
            Preview preview = new Preview(
                    scope.workspaceId(),
                    scope.threadId(),
                    origin,
                    expiresAt,
                    json.encode(content).sha256());
            if (repository.insertPreview(connection, preview)) {
                return preview;
            }
        }
        throw PersistenceException.invalidRequest("浏览器授权预览无法分配唯一身份，请重试");
    }

    private void requireOriginCapacity(java.sql.Connection connection, Preview preview) throws java.sql.SQLException {
        Set<URI> origins = repository.latestAt(connection, preview.threadId(), ALL_HISTORY).stream()
                .filter(grant -> grant.state() == SecurityGrantState.ACTIVE)
                .map(Grant::origin)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!origins.contains(preview.origin()) && origins.size() >= 128) {
            throw PersistenceException.invalidRequest("每个对话最多同时授权 128 个浏览器来源，请先撤销不再使用的来源");
        }
    }

    private static Snapshot requireScope(Snapshot snapshot, Scope scope) {
        if (!snapshot.workspaceId().equals(scope.workspaceId())
                || !snapshot.threadId().equals(scope.threadId())) {
            throw new SecurityException("浏览器快照作用域不匹配");
        }
        return snapshot;
    }

    private static boolean matches(Grant grant, Scope scope, URI origin) {
        return grant.state() == SecurityGrantState.ACTIVE
                && grant.workspaceId().equals(scope.workspaceId())
                && grant.threadId().equals(scope.threadId())
                && grant.origin().equals(origin);
    }

    private Decision decision(String operation, URI origin, Optional<Grant> grant, String reason) {
        return new Decision(
                UUID.randomUUID().toString(),
                operation,
                origin,
                grant.map(value -> new GrantRef(value.id(), value.revision())),
                grant.isPresent(),
                reason,
                now());
    }

    private void record(java.sql.Connection connection, Scope scope, Decision decision) throws java.sql.SQLException {
        repository.audit(connection, scope, decision);
    }

    private void changed(Scope scope) {
        for (Consumer<Scope> listener : listeners) {
            try {
                listener.accept(scope);
            } catch (RuntimeException ignored) {
                // 撤权已提交，监听器故障不能把权限重新打开；每请求实时复核是最终边界。
            }
        }
    }

    private static Scope scope(Grant grant) {
        return new Scope(grant.workspaceId(), grant.threadId());
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("浏览器授权事务失败", failure);
        }
    }

    /**
     * 授权变化的唯一作用域。
     *
     * @param workspaceId Workspace，不可空
     * @param threadId Thread，不可空
     */
    public record Scope(WorkspaceId workspaceId, ThreadId threadId) {
        /** 校验作用域。 */
        public Scope {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(threadId, "threadId");
        }
    }

    /**
     * 只包含精确来源与版本的脱敏决策记录。
     *
     * @param id 审计 UUID
     * @param operation confirm、revoke、assistant-connect 或 human-connect
     * @param origin 精确 HTTPS 来源，无路径
     * @param grant 匹配授权版本；拒绝时可为空
     * @param allowed 本次决策是否允许；撤销操作本身成功也为真
     * @param reason 不含敏感数据的结果原因
     * @param decidedAt 决策时刻
     */
    public record Decision(
            String id,
            String operation,
            URI origin,
            Optional<GrantRef> grant,
            boolean allowed,
            String reason,
            Instant decidedAt) {}

    private record PreviewContent(Scope scope, URI origin, Instant expiresAt) {}

    private record Authorization(Optional<Grant> grant, String reason) {
        Grant require() {
            return grant.orElseThrow(() -> new SecurityException("BROWSER_ORIGIN_NOT_AUTHORIZED: " + reason));
        }
    }
}
