package com.javaclaw.server.extension;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.security.vault.SecretOperation;

/** 会话账号绑定与私有登录态保存；账号安全版本固定，状态版本可在同一写租约内前进。 */
final class BrowserSessionAccounts {
    private final SiteBrowserHostContext host;
    private final H2ManagedExtensionStore store;

    BrowserSessionAccounts(SiteBrowserHostContext host, H2ManagedExtensionStore store) {
        this.host = host;
        this.store = store;
    }

    BrowserSessionState create(
            IsolatedServiceInvocation invocation, BrowserCommands.Open request, BrowserSessionState.Access access)
            throws Exception {
        if (request.keepLogin()) {
            BrowserSessionAuthority.requireUser(invocation);
        }
        Optional<SiteAccountContracts.AccountScope> scope = request.account()
                .map(selection -> new SiteAccountContracts.AccountScope(
                        invocation.workspaceId(), selection.siteId(), selection.accountId()));
        Optional<BrowserContracts.AccountBinding> binding = scope.map(value -> {
            var account = account(value);
            if (!account.enabled()) {
                throw new SecurityException("该网站账号已禁用");
            }
            return new BrowserContracts.AccountBinding(account.accountId(), account.securityRevision());
        });
        String id = UUID.randomUUID().toString();
        var owner = new BrowserContracts.Owner(
                invocation.workspaceId(), invocation.scope().threadId().orElseThrow(), binding);
        var session = new BrowserSessionState(
                id, owner, scope, access, request.keepLogin(), host.clock().instant());
        if (scope.isPresent()) {
            SiteContracts.Site site = site(scope.orElseThrow());
            if (!site.origin().equals(SiteContracts.originOf(request.uri()))) {
                throw new SecurityException("账号只能从绑定的网站 Origin 打开");
            }
            session.stateLease = host.accounts().acquireStateLease(scope.orElseThrow(), id);
        }
        return session;
    }

    void requireSameAccount(BrowserSessionState session, Optional<SiteAccountContracts.Selection> selection) {
        Optional<SiteAccountContracts.Selection> existing =
                session.account.map(scope -> new SiteAccountContracts.Selection(scope.siteId(), scope.accountId()));
        if (!existing.equals(selection)) {
            throw new IllegalStateException("切换账号前请关闭当前浏览器，随后使用隔离上下文打开新账号");
        }
    }

    <T> T withState(BrowserSessionState session, SecretOperation<T> action) throws Exception {
        if (session.account.isEmpty()) {
            return action.use(new byte[0]);
        }
        return host.accounts().useState(session.account.orElseThrow(), security(session), action);
    }

    void saveIfSelected(BrowserSessionState session, BrowserSessionState.Access access, BrowserWorkerPort worker) {
        if (!session.keepLogin || session.closed || session.account.isEmpty()) {
            return;
        }
        try {
            requireCurrent(session);
        } catch (Exception failure) {
            throw new SecurityException("登录态保存前账号安全状态已失效", failure);
        }
        var lease = session.stateLease;
        worker.saveInteractiveState(session.id, bytes -> {
            if (session.closed || session.stateLease != lease || session.access != access) {
                throw new SecurityException("浏览器登录态保存租约已失效");
            }
            var identity = identity(
                    "state",
                    session.id + ':' + lease.stateRevision(),
                    lease.stateRevision(),
                    host.json()
                            .encode(Map.of("lease", lease, "digest", digest(bytes)))
                            .sha256());
            host.accounts().saveState(lease, identity, bytes);
            session.stateLease = host.accounts().acquireStateLease(lease.scope(), session.id);
            return true;
        });
    }

    void release(BrowserSessionState session) {
        if (session.stateLease != null) {
            host.accounts().releaseStateLease(session.stateLease);
        }
    }

    CanonicalPayload manage(
            IsolatedServiceInvocation invocation,
            BrowserCommands.Invocation command,
            BrowserSessionState session,
            BrowserWorkerPort worker)
            throws Exception {
        var access = userAccess(invocation, session);
        return switch (command.operation()) {
            case "browser.login.forms" ->
                host.json()
                        .encode(new BrowserCommands.LoginForms(worker.prepareInteractiveCredentials(
                                session.id, access.lease(), loginOrigin(session))));
            case "browser.capture" -> capture(invocation, command.payload(), session, access, worker);
            case "browser.save" -> {
                session.keepLogin = true;
                saveIfSelected(session, access, worker);
                yield host.json().encode(account(session.account.orElseThrow()));
            }
            default -> throw new IllegalArgumentException("未知浏览器管理操作");
        };
    }

    CanonicalPayload fill(
            IsolatedServiceInvocation invocation,
            CanonicalPayload payload,
            BrowserSessionState session,
            BrowserSessionState.Access access,
            BrowserWorkerPort worker)
            throws Exception {
        var scope = session.account.orElseThrow(() -> new IllegalStateException("当前浏览器未绑定账号"));
        var target = host.json().decode(payload, BrowserContracts.CredentialsTarget.class);
        URI origin = loginOrigin(session);
        return host.accounts().useCredential(scope, security(session), bytes -> {
            try (var result = worker.fillInteractiveCredentials(
                    session.id, access.lease(), origin, target, bytes, invocation.cancellation())) {
                if (session.access != access || session.closed) {
                    throw new SecurityException("填充期间浏览器授权已改变");
                }
                return host.json()
                        .encode(new BrowserThreadAttachments(host)
                                .store(
                                        session,
                                        access,
                                        invocation.scope().idempotencyKey().orElseThrow(),
                                        result));
            }
        });
    }

    private CanonicalPayload capture(
            IsolatedServiceInvocation invocation,
            CanonicalPayload payload,
            BrowserSessionState session,
            BrowserSessionState.Access access,
            BrowserWorkerPort worker)
            throws Exception {
        var scope = session.account.orElseThrow(() -> new IllegalStateException("请先选择要保存的账号"));
        var target = host.json().decode(payload, BrowserContracts.CredentialsTarget.class);
        var current = account(scope);
        URI origin = loginOrigin(session);
        String key = invocation.scope().idempotencyKey().orElseThrow();
        var lease = session.stateLease;
        // 登录态先经私有帧取得短生命周期副本，再与同一用户确认的凭据在一个账号事务提交。
        byte[] state = worker.saveInteractiveState(session.id, byte[]::clone);
        try {
            return worker.captureInteractiveCredentials(session.id, access.lease(), origin, target, bytes -> {
                if (session.access != access || session.closed || session.stateLease != lease) {
                    throw new SecurityException("用户确认的登录表单或状态版本已失效");
                }
                var identity = identity("capture", key, current.revision(), payload.sha256());
                return host.json().encode(host.accounts().setLogin(lease, identity, bytes, state));
            });
        } finally {
            Arrays.fill(state, (byte) 0);
        }
    }

    void requireCurrent(BrowserSessionState session) throws Exception {
        if (session.account.isPresent()) {
            var account = account(session.account.orElseThrow());
            if (!account.enabled() || account.securityRevision() != security(session)) {
                throw new SecurityException("账号安全状态已改变");
            }
            loginOrigin(session);
        }
    }

    private URI loginOrigin(BrowserSessionState session) throws Exception {
        var scope = session.account.orElseThrow(() -> new IllegalStateException("当前浏览器未绑定账号"));
        var site = site(scope);
        if (site.authorityRevision() != session.stateLease.siteAuthorityRevision()
                || account(scope).securityRevision() != security(session)) {
            throw new SecurityException("网站或账号安全状态已改变");
        }
        return site.origin();
    }

    private SiteContracts.Site site(SiteAccountContracts.AccountScope scope) throws Exception {
        return store.inTransaction(new ExtensionId(BuiltinExtensionIds.SITE), tx -> {
            var document = tx.get("documents." + scope.workspaceId(), scope.siteId())
                    .orElseThrow(() -> new SecurityException("网站不存在"));
            var site = host.json().decode(document.payload(), SiteContracts.Site.class);
            if (!site.enabled()) {
                throw new SecurityException("网站已禁用");
            }
            return site;
        });
    }

    private SiteAccountContracts.AccountProjection account(SiteAccountContracts.AccountScope scope) {
        return host.accounts().list(scope.workspaceId(), scope.siteId()).accounts().stream()
                .filter(value -> value.accountId().equals(scope.accountId()))
                .findFirst()
                .orElseThrow(() -> new SecurityException("网站账号不存在"));
    }

    private BrowserSessionState.Access userAccess(IsolatedServiceInvocation invocation, BrowserSessionState session) {
        BrowserSessionAuthority.requireUser(invocation);
        synchronized (session) {
            var access = session.access;
            if (session.closed
                    || session.closing
                    || !access.lease().active(host.clock().instant())
                    || invocation.scope().expectedRevision() != access.lease().generation()
                    || access.lease().mode() != BrowserContracts.ControlMode.HUMAN) {
                throw new SecurityException("用户确认的浏览器操作租约已改变，请刷新后重新确认");
            }
            return access;
        }
    }

    private static long security(BrowserSessionState session) {
        return session.owner.account().orElseThrow().securityRevision();
    }

    private static String digest(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private static CommandIdentity identity(String operation, String key, long revision, String digest) {
        return new CommandIdentity("site/browser/" + operation, key, revision, digest);
    }
}
