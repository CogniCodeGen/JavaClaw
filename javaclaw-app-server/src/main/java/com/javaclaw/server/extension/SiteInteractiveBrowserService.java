package com.javaclaw.server.extension;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.browser.client.BrowserActionResult;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.security.grant.BrowserGrantService;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;

/** 常驻浏览器的宿主组合服务；仅拥有 Thread 会话状态，不把业务状态放入 Thin Harness。 */
final class SiteInteractiveBrowserService implements AutoCloseable {
    private final SiteBrowserHostContext host;
    private final Optional<BrowserWorkerPort> worker;
    private final BrowserSessionAuthority authority;
    private final BrowserSessionNetwork network;
    private final BrowserOriginRequests origins;
    private final BrowserPendingOrigins pendingOrigins;
    private final BrowserOperationDispatch dispatch;
    private final BrowserOperationLedger ledger;
    private final BrowserThreadAttachments attachments;
    private final BrowserSessionAccounts accounts;
    private final BrowserGrantCommands grantCommands;
    private final H2ManagedExtensionStore store;
    private final Map<ThreadId, BrowserSessionState> sessions = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("browser-session-maintenance").factory());
    private final AutoCloseable grantChanges;
    private final AutoCloseable accountChanges;
    private volatile boolean closed;

    SiteInteractiveBrowserService(
            SiteBrowserHostContext host,
            Optional<BrowserWorkerPort> worker,
            BrowserGrantService grants,
            PrivateNetworkGrantService privateGrants,
            BiConsumer<TurnId, URI> continuation) {
        this.host = host;
        this.worker = worker;
        store = new H2ManagedExtensionStore(host.database(), host.clock());
        authority = new BrowserSessionAuthority(host, grants);
        network = new BrowserSessionNetwork(host, authority, privateGrants);
        origins = new BrowserOriginRequests(host, grants, continuation);
        pendingOrigins = new BrowserPendingOrigins(origins);
        dispatch = new BrowserOperationDispatch(pendingOrigins, authority, network);
        ledger = new BrowserOperationLedger(store, host.json());
        attachments = new BrowserThreadAttachments(host);
        accounts = new BrowserSessionAccounts(host, store);
        grantCommands = new BrowserGrantCommands(host, grants);
        grantChanges = grants.onChanged(scope -> invalidateOrigins(scope.threadId()));
        accountChanges = host.accounts()
                .onSecurityChanged(scope -> sessions.values().stream()
                        .filter(session -> session.account.filter(scope::equals).isPresent())
                        .forEach(this::revokeAccount));
        maintenance.scheduleWithFixedDelay(this::maintain, 1, 1, TimeUnit.SECONDS);
    }

    CanonicalPayload invoke(IsolatedServiceInvocation invocation) throws Exception {
        if (closed) {
            throw new IllegalStateException("浏览器宿主已关闭");
        }
        ThreadId thread = authority.owner(invocation);
        var command = host.json().decode(invocation.request(), BrowserCommands.Invocation.class);
        String operation = command.operation();
        if (operation.startsWith("browser.grant") || operation.equals("browser.grants")) {
            return grantCommands.invoke(invocation, command);
        }
        if (operation.equals("browser.status")) {
            return host.json().encode(status(thread));
        }
        requireWorker();
        return switch (operation) {
            case "browser_open", "browser.open" -> open(invocation, command.payload());
            case "browser_fill_account" -> fillAccount(invocation, command.payload());
            case "browser_act", "browser.act", "browser_screenshot", "browser_tabs" -> act(invocation, command);
            default -> manage(invocation, command);
        };
    }

    private BrowserCommands.Status status(ThreadId thread) {
        return BrowserSessionStatus.read(
                host,
                store,
                worker.filter(BrowserWorkerPort::interactiveAvailable).isPresent(),
                sessions.get(thread),
                thread,
                pendingOrigins);
    }

    private CanonicalPayload open(IsolatedServiceInvocation invocation, CanonicalPayload payload) throws Exception {
        var request = host.json().decode(payload, BrowserCommands.Open.class);
        Optional<CanonicalPayload> handoff = origins.require(invocation, SiteContracts.originOf(request.uri()));
        if (handoff.isPresent()) {
            return handoff.orElseThrow();
        }
        ThreadId thread = authority.owner(invocation);
        BrowserSessionState existing = sessions.get(thread);
        if (existing != null && !existing.closed && !existing.closing) {
            accounts.requireSameAccount(existing, request.account());
            var action = new BrowserContracts.Action(
                    BrowserContracts.Operation.NAVIGATE,
                    BrowserContracts.Target.current(),
                    BrowserContracts.ActionInput.text(request.uri().toString()));
            var previous = ledger.recover(
                    existing.owner.threadId(),
                    identity(invocation),
                    BrowserOperationIdentity.fingerprint(
                            host.json(), invocation, existing, payload, Optional.of(action)));
            if (previous.isPresent()) {
                return previous.orElseThrow();
            }
            return execute(invocation, existing, action, payload, Optional.empty());
        }
        BrowserSessionState created = accounts.create(
                invocation,
                request,
                authority.acquire(invocation, 1, invocation.scope().turnId().isEmpty()));
        synchronized (lifecycle) {
            if (closed || sessions.putIfAbsent(thread, created) != null) {
                accounts.release(created);
                throw new IllegalStateException("浏览器宿主已关闭或当前对话已有浏览器正在启动");
            }
        }
        return start(invocation, payload, request.uri(), created);
    }

    private CanonicalPayload start(
            IsolatedServiceInvocation invocation, CanonicalPayload payload, URI uri, BrowserSessionState session)
            throws Exception {
        String key = identity(invocation);
        try {
            CanonicalPayload fingerprint = BrowserOperationIdentity.fingerprint(
                    host.json(),
                    invocation,
                    session,
                    payload,
                    Optional.of(new BrowserContracts.Action(
                            BrowserContracts.Operation.NAVIGATE,
                            BrowserContracts.Target.current(),
                            BrowserContracts.ActionInput.text(uri.toString()))));
            Optional<CanonicalPayload> previous = ledger.begin(session.owner.threadId(), key, fingerprint);
            if (previous.isPresent()) {
                sessions.remove(session.owner.threadId(), session);
                accounts.release(session);
                return previous.orElseThrow();
            }
            var access = session.access;
            authority.requireCurrent(session, access);
            var task = new BrowserContracts.OpenTask(session.id, session.owner, uri, access.lease());
            var browser = requireWorker();
            try (BrowserActionResult result = accounts.withState(
                    session, bytes -> dispatch.open(invocation, session, access, browser, task, bytes))) {
                var output = host.json().encode(attachments.store(session, access, key, result));
                ledger.complete(session.owner.threadId(), key, fingerprint, output);
                changed(session);
                pendingOrigins.requestAfter(invocation, session, access);
                return output;
            }
        } catch (Exception failure) {
            BrowserOperationDispatch.preserveFailure(failure, () -> closeSession(session, false));
            throw failure;
        }
    }

    private CanonicalPayload act(IsolatedServiceInvocation invocation, BrowserCommands.Invocation command)
            throws Exception {
        BrowserSessionState session = requireSession(invocation);
        BrowserContracts.Action action =
                new BrowserActionParser(host.json()).parse(command.operation(), command.payload());
        if (action.operation() == BrowserContracts.Operation.FILL_SECRET) {
            throw new SecurityException("凭据只能由账号填充专用私有动作提供");
        }
        if (action.operation() == BrowserContracts.Operation.NAVIGATE
                || action.operation() == BrowserContracts.Operation.NEW_TAB) {
            var handoff = origins.require(
                    invocation, SiteContracts.originOf(URI.create(action.input().value())));
            if (handoff.isPresent()) {
                return handoff.orElseThrow();
            }
        }
        return execute(
                invocation,
                session,
                action,
                command.payload(),
                BrowserUploadInput.digest(host.json(), command.payload()));
    }

    private CanonicalPayload execute(
            IsolatedServiceInvocation invocation,
            BrowserSessionState session,
            BrowserContracts.Action action,
            CanonicalPayload payload,
            Optional<String> upload)
            throws Exception {
        if (!session.actions.tryLock()) {
            throw new IllegalStateException("浏览器已有操作正在执行");
        }
        try {
            assistantLease(invocation, session);
            var access = authority.requireAssistant(session, invocation);
            requireVisualModel(invocation, action);
            String key = identity(invocation);
            CanonicalPayload fingerprint = BrowserOperationIdentity.fingerprint(
                    host.json(), invocation, session, payload, Optional.of(action));
            Optional<CanonicalPayload> previous = ledger.begin(session.owner.threadId(), key, fingerprint);
            if (previous.isPresent()) {
                return previous.orElseThrow();
            }
            BrowserSessionActivity.touch(session, access, host.clock());
            var browser = requireWorker();
            BrowserUploadInput input = BrowserUploadInput.prepare(host, attachments, session, action, upload);
            try (input;
                    BrowserActionResult result = dispatch.act(invocation, session, access, browser, input)) {
                var output = host.json().encode(attachments.store(session, access, key, result));
                ledger.complete(session.owner.threadId(), key, fingerprint, output);
                accounts.saveIfSelected(session, access, requireWorker());
                changed(session);
                pendingOrigins.requestAfter(invocation, session, access);
                return output;
            }
        } finally {
            session.actions.unlock();
        }
    }

    private CanonicalPayload fillAccount(IsolatedServiceInvocation invocation, CanonicalPayload payload)
            throws Exception {
        BrowserSessionState session = requireSession(invocation);
        if (!session.actions.tryLock()) {
            throw new IllegalStateException("浏览器已有操作正在执行");
        }
        try {
            assistantLease(invocation, session);
            var access = authority.requireAssistant(session, invocation);
            String key = identity(invocation);
            CanonicalPayload fingerprint =
                    BrowserOperationIdentity.fingerprint(host.json(), invocation, session, payload, Optional.empty());
            Optional<CanonicalPayload> previous = ledger.begin(session.owner.threadId(), key, fingerprint);
            if (previous.isPresent()) {
                return previous.orElseThrow();
            }
            BrowserSessionActivity.touch(session, access, host.clock());
            var guarded = BrowserOperationIdentity.withCancellation(
                    invocation, authority.cancellation(session, access, invocation.cancellation()));
            CanonicalPayload result = accounts.fill(guarded, payload, session, access, requireWorker());
            authority.requireCurrent(session, access);
            ledger.complete(session.owner.threadId(), key, fingerprint, result);
            changed(session);
            pendingOrigins.requestAfter(invocation, session, access);
            return result;
        } finally {
            session.actions.unlock();
        }
    }

    private CanonicalPayload manage(IsolatedServiceInvocation invocation, BrowserCommands.Invocation command)
            throws Exception {
        BrowserSessionAuthority.requireUser(invocation);
        BrowserSessionState session = requireSession(invocation);
        if (command.operation().equals("browser.takeover")
                || command.operation().equals("browser.return")) {
            return control(invocation, session, command.operation().equals("browser.takeover"));
        }
        BrowserSessionState.Access expected;
        synchronized (session) {
            requireRevision(invocation, session);
            expected = session.access;
        }
        if (command.operation().equals("browser.close")) {
            closeSession(session, true, expected);
            return host.json().encode(status(session.owner.threadId()));
        }
        BrowserSessionActivity.touch(session, expected, host.clock());
        return accounts.manage(invocation, command, session, requireWorker());
    }

    private CanonicalPayload control(IsolatedServiceInvocation invocation, BrowserSessionState session, boolean human) {
        BrowserSessionState.Access access;
        synchronized (session) {
            requireRevision(invocation, session);
            access = authority.release(session);
            if (human) {
                access = authority.acquire(invocation, access.lease().generation() + 1, true);
                session.access = access;
            }
            session.touchedAt = host.clock().instant();
        }
        publishLease(session, access, new CancellationSource());
        return host.json().encode(status(session.owner.threadId()));
    }

    private void assistantLease(IsolatedServiceInvocation invocation, BrowserSessionState session) {
        BrowserSessionState.Access next = null;
        synchronized (session) {
            if (session.closed
                    || session.closing
                    || session.access.lease().mode() == BrowserContracts.ControlMode.HUMAN) {
                throw new SecurityException("用户正在操作浏览器或会话已关闭，助手观察与操作已暂停");
            }
            if (!session.access.turn().equals(invocation.scope().turnId())
                    || !session.access.lease().active(host.clock().instant())) {
                var released = authority.release(session);
                next = authority.acquire(invocation, released.lease().generation() + 1, false);
                session.access = next;
            }
        }
        if (next != null) {
            publishLease(session, next, invocation.cancellation());
        }
    }

    void finishTurn(TurnId turn) {
        for (BrowserSessionState session : sessions.values()) {
            BrowserSessionState.Access released;
            synchronized (session) {
                if (session.closed
                        || session.closing
                        || session.access.turn().filter(turn::equals).isEmpty()) {
                    continue;
                }
                released = authority.release(session);
            }
            publishLease(session, released, new CancellationSource());
        }
    }

    private void publishLease(
            BrowserSessionState session,
            BrowserSessionState.Access access,
            com.javaclaw.api.CancellationToken cancellation) {
        if (leaseChanged(session, access)) {
            return;
        }
        try {
            var view = requireWorker().updateInteractiveLease(session.id, access.lease(), cancellation);
            synchronized (session) {
                if (leaseChanged(session, access)) {
                    return;
                }
                if (!view.lease().equals(access.lease()) || !view.owner().equals(session.owner)) {
                    throw new SecurityException("浏览器控制回执身份不一致");
                }
                session.view = view;
            }
            changed(session);
        } catch (RuntimeException failure) {
            if (leaseChanged(session, access)) {
                return;
            }
            closeSession(session, false);
            throw failure;
        }
    }

    private static boolean leaseChanged(BrowserSessionState session, BrowserSessionState.Access access) {
        return session.closed || session.closing || session.access != access;
    }

    private static void requireRevision(IsolatedServiceInvocation invocation, BrowserSessionState session) {
        if (session.closed
                || session.closing
                || invocation.scope().expectedRevision()
                        != session.access.lease().generation()) {
            throw new IllegalStateException("浏览器控制代次已改变，请刷新后操作");
        }
    }

    private void requireVisualModel(IsolatedServiceInvocation invocation, BrowserContracts.Action action) {
        if (action.operation() != BrowserContracts.Operation.CLICK_AT
                && action.operation() != BrowserContracts.Operation.DRAG) {
            return;
        }
        var turn =
                host.core().findTurn(invocation.scope().turnId().orElseThrow()).orElseThrow();
        if (!host.providers().probe(turn.provider()).capabilities().images()) {
            throw new SecurityException("当前模型未声明图片输入能力，请使用页面元素引用");
        }
    }

    private BrowserSessionState requireSession(IsolatedServiceInvocation invocation) {
        BrowserSessionState session = sessions.get(authority.owner(invocation));
        if (session == null || session.closed || session.closing || session.view == null) {
            throw new IllegalStateException("当前对话没有活动浏览器");
        }
        return session;
    }

    private BrowserWorkerPort requireWorker() {
        return worker.filter(BrowserWorkerPort::interactiveAvailable)
                .orElseThrow(() -> new IllegalStateException("当前平台尚未通过可见浏览器隔离验证"));
    }

    private void revokeAccount(BrowserSessionState session) {
        synchronized (session) {
            session.access.cancelled().cancel("站点账号安全状态已改变");
        }
        maintenance.execute(() -> closeSession(session, false));
    }

    private void invalidateOrigins(ThreadId thread) {
        BrowserSessionState session = sessions.get(thread);
        if (session == null || session.closed || session.closing) {
            return;
        }
        var access = authority.release(session);
        maintenance.execute(() -> publishLease(session, access, new CancellationSource()));
    }

    private void maintain() {
        for (BrowserSessionState session : sessions.values()) {
            if (session.closed || session.closing) {
                continue;
            }
            try {
                accounts.requireCurrent(session);
                maintainLease(session);
                maintainIdle(session);
            } catch (Exception failure) {
                try {
                    closeSession(session, false);
                } catch (RuntimeException cleanup) {
                    session.access.cancelled().cancel("浏览器维护回收失败");
                }
            }
        }
    }

    private void maintainIdle(BrowserSessionState session) {
        Duration idle = Duration.between(session.touchedAt, host.clock().instant());
        if (idle.compareTo(Duration.ofMinutes(15)) >= 0) {
            closeSession(session, true);
            return;
        }
        if (idle.compareTo(Duration.ofMinutes(14)) < 0 || session.savedIdleAt == session.touchedAt) {
            return;
        }
        var access = session.access;
        if (session.actions.tryLock()) {
            try {
                accounts.saveIfSelected(session, access, requireWorker());
                session.savedIdleAt = session.touchedAt;
            } finally {
                session.actions.unlock();
            }
        }
    }

    private void maintainLease(BrowserSessionState session) {
        var access = session.access;
        if (access.lease().mode() == BrowserContracts.ControlMode.NONE) {
            return;
        }
        try {
            authority.requireCurrent(session, access);
            for (URI origin : access.lease().allowedOrigins()) {
                authority.authorize(session, access, origin);
            }
        } catch (RuntimeException expired) {
            BrowserSessionState.Access released;
            synchronized (session) {
                if (leaseChanged(session, access)) {
                    return;
                }
                released = authority.release(session);
            }
            publishLease(session, released, new CancellationSource());
        }
    }

    private void closeSession(BrowserSessionState session, boolean save) {
        closeSession(session, save, null);
    }

    private void closeSession(BrowserSessionState session, boolean save, BrowserSessionState.Access expected) {
        BrowserSessionState.Access access;
        synchronized (session) {
            if (session.closed || session.closing) {
                return;
            }
            if (expected != null && session.access != expected) {
                throw new SecurityException("关闭请求的浏览器控制代次已失效");
            }
            session.closing = true;
            access = session.access;
        }
        try {
            if (save) {
                accounts.saveIfSelected(session, access, requireWorker());
            }
        } finally {
            synchronized (session) {
                session.closed = true;
                access.cancelled().cancel("浏览器会话已关闭");
            }
            try {
                worker.ifPresent(value -> value.closeInteractive(session.id));
            } finally {
                accounts.release(session);
                sessions.remove(session.owner.threadId(), session);
                changed(session);
            }
        }
    }

    private void changed(BrowserSessionState session) {
        try {
            store.inTransaction(new ExtensionId(BuiltinExtensionIds.SITE), tx -> {
                tx.appendEvent(
                        "site.browser.changed",
                        host.json()
                                .encode(Map.of(
                                        "workspaceId",
                                        session.owner.workspaceId(),
                                        "threadId",
                                        session.owner.threadId(),
                                        "sessionId",
                                        session.id)));
                return null;
            });
        } catch (Exception failure) {
            throw new IllegalStateException("浏览器状态通知失败", failure);
        }
    }

    private static String identity(IsolatedServiceInvocation invocation) {
        return invocation.scope().idempotencyKey().orElseThrow(() -> new SecurityException("浏览器动作需要幂等身份"));
    }

    @Override
    public void close() {
        java.util.List<BrowserSessionState> owned;
        synchronized (lifecycle) {
            closed = true;
            owned = java.util.List.copyOf(sessions.values());
        }
        // 维护中的关闭仍需释放账号写租约并提交 changed 事件；等待其完成后才能让组合根关闭 Vault 或 H2。
        maintenance.close();
        RuntimeException failure = null;
        for (BrowserSessionState session : owned) {
            try {
                closeSession(session, true);
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        try {
            try {
                grantChanges.close();
            } finally {
                accountChanges.close();
            }
            if (failure != null) {
                throw failure;
            }
        } catch (Exception listenerFailure) {
            throw new IllegalStateException("浏览器授权监听关闭失败", listenerFailure);
        }
    }
}
