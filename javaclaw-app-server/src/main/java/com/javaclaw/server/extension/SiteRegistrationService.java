package com.javaclaw.server.extension;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.client.BrowserRegistrationPort;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.Session;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.site.account.SiteRegistrationStore;

/**
 * Workspace 设置页的人工登记宿主；临时窗口没有 Thread/Turn，也不会提前创建网站。
 *
 * <p>命令与维护在实例内串行，Broker 仅访问会话的可见租约而不取得此锁，避免等待 Worker 时死锁。 启动先持久记录身份，完成只消费一次私有导出；失败后的查询恢复回执，不自动重新打开或重放保存。
 */
final class SiteRegistrationService implements AutoCloseable {
    private final SiteRegistrationStore store;
    private final Optional<BrowserWorkerPort> worker;
    private final SiteRegistrationNetwork network;
    private final CanonicalJson json;
    private final Clock clock;
    private final Map<WorkspaceId, SiteRegistrationSession> sessions = new HashMap<>();
    private final ScheduledExecutorService maintenance;
    private boolean closed;

    SiteRegistrationService(
            SiteRegistrationStore store,
            Optional<BrowserWorkerPort> worker,
            SiteRegistrationNetwork network,
            CanonicalJson json,
            Clock clock) {
        this.store = store;
        this.worker = worker;
        this.network = network;
        this.json = json;
        this.clock = clock;
        maintenance = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .daemon()
                .name("site-registration-maintenance")
                .factory());
        maintenance.scheduleWithFixedDelay(this::expireSessions, 1, 1, TimeUnit.SECONDS);
    }

    synchronized CanonicalPayload invoke(IsolatedServiceInvocation invocation) {
        requireSettings(invocation);
        var command = json.decode(invocation.request(), SiteRegistrationContracts.ServiceRequest.class);
        if (!command.idempotencyKey().equals(invocation.scope().idempotencyKey())) {
            throw new SecurityException("网站登记的命令身份与平台 Scope 不一致");
        }
        if (command.operation().equals("registration.status")) {
            if (command.idempotencyKey().isPresent()) {
                throw new IllegalArgumentException("登记查询不能携带写入身份");
            }
            return json.encode(status(invocation.workspaceId(), sessionId(command)));
        }
        CommandIdentity identity = identity(invocation, command);
        Optional<Session> replay = store.recover(identity);
        if (replay.isPresent()) {
            return json.encode(
                    status(invocation.workspaceId(), replay.orElseThrow().sessionId()));
        }
        Session result =
                switch (command.operation()) {
                    case "registration.begin" -> begin(invocation, command, identity);
                    case "registration.origin" -> allowOrigin(invocation, command, identity);
                    case "registration.complete" -> complete(invocation, command, identity);
                    case "registration.cancel" -> cancel(invocation.workspaceId(), sessionId(command), identity);
                    default -> throw new IllegalArgumentException("不支持的网站登记操作");
                };
        return json.encode(result);
    }

    private Session begin(
            IsolatedServiceInvocation invocation,
            SiteRegistrationContracts.ServiceRequest command,
            CommandIdentity identity) {
        BrowserRegistrationPort port = port();
        var request = json.decode(command.payload(), SiteRegistrationContracts.BeginRequest.class);
        SiteRegistrationSession previous = sessions.get(invocation.workspaceId());
        if (previous != null && status(previous.workspace, previous.id).state() == State.ACTIVE) {
            throw new IllegalStateException("当前 Workspace 已有网站登记窗口");
        }
        var lease = new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.HUMAN,
                UUID.randomUUID().toString(),
                1,
                clock.instant().plus(Duration.ofMinutes(15)),
                Set.of(SiteContracts.originOf(SiteRegistrationContracts.displayUri(request.uri()))));
        var session = new SiteRegistrationSession(
                invocation.workspaceId(), UUID.randomUUID().toString(), invocation.effectivePermissions(), lease);
        store.record(session.workspace, identity, session.snapshot(State.ACTIVE));
        sessions.put(session.workspace, session);
        try {
            var task = new SiteRegistrationContracts.WorkerTask(session.id, session.workspace, request.uri(), lease);
            var result = port.begin(task, network.callback(session), invocation.cancellation());
            invocation.cancellation().throwIfCancelled();
            return accept(session, result);
        } catch (RuntimeException failure) {
            fail(session, failure);
            throw failure;
        }
    }

    private Session allowOrigin(
            IsolatedServiceInvocation invocation,
            SiteRegistrationContracts.ServiceRequest command,
            CommandIdentity identity) {
        var request = json.decode(command.payload(), SiteRegistrationContracts.OriginRequest.class);
        SiteRegistrationSession session = active(invocation.workspaceId(), request.sessionId());
        var previous = session.lease;
        requireGeneration(previous, request.expectedGeneration());
        Set<URI> origins = new HashSet<>(previous.allowedOrigins());
        origins.add(request.origin());
        var next = new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.HUMAN,
                UUID.randomUUID().toString(),
                Math.addExact(previous.generation(), 1),
                previous.expiresAt(),
                origins);
        session.lease = next;
        session.pending.remove(request.origin());
        try {
            // 先持久绑定操作身份；更新 Worker 失败则终止窗口，同一命令不得再次追加或启动。
            store.record(session.workspace, identity, session.snapshot(State.ACTIVE));
            return accept(session, port().updateLease(session.id, next, invocation.cancellation()));
        } catch (RuntimeException failure) {
            fail(session, failure);
            throw failure;
        }
    }

    private Session complete(
            IsolatedServiceInvocation invocation,
            SiteRegistrationContracts.ServiceRequest command,
            CommandIdentity identity) {
        var request = json.decode(command.payload(), SiteRegistrationContracts.CompleteRequest.class);
        SiteRegistrationSession session = active(invocation.workspaceId(), request.sessionId());
        var lease = session.lease;
        requireGeneration(lease, request.expectedGeneration());
        try {
            Session completed = port().complete(session.id, request, (status, state, credentials) -> {
                try {
                    validate(session, status);
                    invocation.cancellation().throwIfCancelled();
                    return store.complete(session.workspace, request, status, identity, state, credentials, () -> {
                        invocation.cancellation().throwIfCancelled();
                        session.requireCurrent(lease, clock);
                    });
                } finally {
                    Arrays.fill(state, (byte) 0);
                    Arrays.fill(credentials, (byte) 0);
                }
            });
            release(session);
            return completed;
        } catch (RuntimeException failure) {
            // 若提交后通信丢失，只有持久回执可以宣布成功；查询失败时保留不确定性并关闭窗口。
            release(session);
            try {
                Optional<Session> committed = store.recover(identity);
                if (committed.isPresent()) {
                    return committed.orElseThrow();
                }
                terminal(session, State.FAILED);
            } catch (RuntimeException recovery) {
                failure.addSuppressed(recovery);
            }
            throw failure;
        }
    }

    private Session cancel(WorkspaceId workspace, String id, CommandIdentity identity) {
        Session current = status(workspace, id);
        if (current.state() != State.ACTIVE) {
            return store.record(workspace, identity, current);
        }
        SiteRegistrationSession session = active(workspace, id);
        Session cancelled = terminal(session, State.CANCELLED);
        return store.record(workspace, identity, cancelled);
    }

    private Session status(WorkspaceId workspace, String id) {
        Session saved = store.read(workspace, id).orElseThrow(() -> new SecurityException("网站登记不存在或不属于当前 Workspace"));
        if (saved.state() != State.ACTIVE) {
            return saved;
        }
        SiteRegistrationSession session = sessions.get(workspace);
        if (session == null || !session.id.equals(id)) {
            // 服务器重启不恢复带秘密的临时窗口；旧 ACTIVE 永远不能重新取得执行能力。
            return recordTerminal(
                    workspace, new Session(id, State.EXPIRED, saved.access(), saved.page(), Optional.empty()));
        }
        if (!session.lease.active(clock.instant())) {
            return terminal(session, State.EXPIRED);
        }
        try {
            return accept(session, port().status(id));
        } catch (RuntimeException failure) {
            fail(session, failure);
            throw failure;
        }
    }

    private Session accept(SiteRegistrationSession session, SiteRegistrationContracts.WorkerStatus status) {
        validate(session, status);
        session.page = status.page();
        synchronized (session.pending) {
            for (URI origin : status.access().pendingOrigins()) {
                if (session.pending.size() < 128) {
                    session.pending.add(origin);
                }
            }
        }
        session.pending.removeAll(session.lease.allowedOrigins());
        if (status.state() != State.ACTIVE) {
            return terminal(session, status.state());
        }
        session.requireCurrent(session.lease, clock);
        return session.snapshot(State.ACTIVE);
    }

    private static void validate(SiteRegistrationSession session, SiteRegistrationContracts.WorkerStatus status) {
        var lease = session.lease;
        var access = status.access();
        if (!session.id.equals(status.sessionId())
                || access.generation() != lease.generation()
                || !access.allowedOrigins().equals(lease.allowedOrigins())
                || !access.expiresAt().equals(lease.expiresAt())) {
            throw new SecurityException("登记 Worker 返回了不一致的会话或授权边界");
        }
        status.page().uri().ifPresent(uri -> {
            if (!lease.allowedOrigins().contains(SiteContracts.originOf(uri))) {
                throw new SecurityException("登记页面不在用户授权来源内");
            }
        });
    }

    private SiteRegistrationSession active(WorkspaceId workspace, String id) {
        SiteRegistrationSession session = sessions.get(workspace);
        if (session == null || !session.id.equals(id)) {
            throw new SecurityException("网站登记窗口不存在或归属不同");
        }
        session.requireCurrent(session.lease, clock);
        return session;
    }

    private Session terminal(SiteRegistrationSession session, State state) {
        release(session);
        try {
            var result = port().cancel(session.id);
            validate(session, result);
            if (result.state() == State.FAILED || result.state() == State.ACTIVE) {
                throw new IllegalStateException("网站登记窗口清理失败，无法确认临时输入已销毁");
            }
        } catch (RuntimeException cleanup) {
            try {
                recordTerminal(session.workspace, session.snapshot(State.FAILED));
            } catch (RuntimeException persistence) {
                cleanup.addSuppressed(persistence);
            }
            throw cleanup;
        }
        // 只有 Worker 确认回收后才能宣告取消或到期；已提交事实始终由持久记录优先决定。
        return recordTerminal(session.workspace, session.snapshot(state));
    }

    private Session recordTerminal(WorkspaceId workspace, Session terminal) {
        Session current = store.read(workspace, terminal.sessionId()).orElseThrow();
        if (current.state() != State.ACTIVE) {
            return current;
        }
        var identity = new CommandIdentity(
                "site/registration/terminal/" + workspace,
                terminal.sessionId(),
                0,
                json.encode(terminal).sha256());
        return store.record(workspace, identity, terminal);
    }

    private void fail(SiteRegistrationSession session, RuntimeException failure) {
        if (sessions.get(session.workspace) != session) {
            return;
        }
        try {
            terminal(session, State.FAILED);
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private void release(SiteRegistrationSession session) {
        session.cancelled.cancel("网站登记窗口已结束");
        sessions.remove(session.workspace, session);
    }

    private synchronized void expireSessions() {
        for (SiteRegistrationSession session : java.util.List.copyOf(sessions.values())) {
            if (!session.lease.active(clock.instant())) {
                try {
                    terminal(session, State.EXPIRED);
                } catch (RuntimeException ignored) {
                    // 网络租约已失效，数据库不可用时旧 ACTIVE 在下次查询被终结，绝不恢复窗口。
                }
            }
        }
    }

    private BrowserRegistrationPort port() {
        return worker.filter(BrowserWorkerPort::interactiveAvailable)
                .orElseThrow(() -> new IllegalStateException("当前发行环境不支持隔离浏览器人工登记"))
                .registrations();
    }

    private void requireSettings(IsolatedServiceInvocation invocation) {
        if (closed
                || !BuiltinExtensionIds.SITE.equals(invocation.caller().value())
                || !SiteRegistrationContracts.SERVICE.equals(invocation.serviceId())
                || invocation.scope().threadId().isPresent()
                || invocation.scope().turnId().isPresent()
                || invocation.scope().expectedRevision() != 0) {
            throw new SecurityException("网站登记只允许当前 Workspace 设置页人工调用");
        }
        invocation.cancellation().throwIfCancelled();
    }

    private CommandIdentity identity(
            IsolatedServiceInvocation invocation, SiteRegistrationContracts.ServiceRequest command) {
        String key = command.idempotencyKey().orElseThrow(() -> new SecurityException("登记写入缺少用户命令身份"));
        return new CommandIdentity(
                "site/" + invocation.workspaceId() + '/' + command.operation(),
                key,
                0,
                json.encode(Map.of("workspaceId", invocation.workspaceId(), "command", command))
                        .sha256());
    }

    private String sessionId(SiteRegistrationContracts.ServiceRequest command) {
        return json.decode(command.payload(), SiteRegistrationContracts.SessionRequest.class)
                .sessionId();
    }

    private static void requireGeneration(BrowserContracts.AccessLease lease, long expected) {
        if (lease.generation() != expected) {
            throw new SecurityException("网站登记授权已更新，请刷新后重试");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        maintenance.shutdownNow();
        RuntimeException failure = null;
        for (SiteRegistrationSession session : java.util.List.copyOf(sessions.values())) {
            try {
                terminal(session, State.CANCELLED);
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
