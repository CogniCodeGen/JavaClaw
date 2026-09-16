package com.javaclaw.server.extension;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.browser.client.BrowserStorageHandler;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;

/** 真实 H2/Vault 的账号边界夹具；仅替换 Worker 私有帧端点，不修改共享宿主夹具。 */
final class BrowserAccountBoundaryFixture implements AutoCloseable {
    static final BrowserContracts.CredentialsTarget TARGET =
            new BrowserContracts.CredentialsTarget("page", "user", "password");
    static final byte[] STATE = "{\"cookies\":[],\"origins\":[]}".getBytes(StandardCharsets.UTF_8);
    final InteractiveBrowserHostFixture base;
    final BrowserSessionAccounts accounts;
    final SiteAccountContracts.AccountProjection account;
    final BrowserSessionState session;
    final BrowserWorkerPort worker;
    final List<byte[]> privateBuffers = new ArrayList<>();
    Runnable beforeSave = () -> {};
    Runnable beforeCapture = () -> {};
    int saves;
    int captures;

    BrowserAccountBoundaryFixture(Path directory, boolean bound) throws Exception {
        base = new InteractiveBrowserHostFixture(directory);
        accounts = new BrowserSessionAccounts(
                base.host, new H2ManagedExtensionStore(base.host.database(), base.host.clock()));
        site(1, true, InteractiveBrowserHostFixture.ORIGIN);
        account = create("工作");
        var invocation = base.invocation("browser.open", Map.of(), 1);
        var access = new BrowserSessionState.Access(
                lease(BrowserContracts.ControlMode.HUMAN, 1, Duration.ofMinutes(1)),
                invocation.effectivePermissions(),
                Optional.empty(),
                new CancellationSource());
        session = accounts.create(
                invocation,
                new BrowserCommands.Open(
                        InteractiveBrowserHostFixture.ORIGIN,
                        bound ? Optional.of(selection()) : Optional.empty(),
                        false),
                access);
        session.view = new BrowserContracts.SessionView(
                session.id, session.owner, BrowserContracts.SessionState.OPEN, access.lease(), List.of());
        worker = (BrowserWorkerPort) Proxy.newProxyInstance(
                BrowserWorkerPort.class.getClassLoader(),
                new Class<?>[] {BrowserWorkerPort.class},
                (proxy, method, arguments) -> dispatch(method.getName(), arguments));
    }

    SiteAccountContracts.AccountProjection create(String name) {
        return base.host
                .accounts()
                .command(
                        base.workspace,
                        "account/create",
                        base.json.encode(new SiteAccountContracts.CreateRequest("account-site", name)),
                        identity("create", 0));
    }

    SiteAccountContracts.Selection selection() {
        return new SiteAccountContracts.Selection(account.siteId(), account.accountId());
    }

    SiteAccountContracts.AccountScope scope() {
        return new SiteAccountContracts.AccountScope(base.workspace, account.siteId(), account.accountId());
    }

    SiteAccountContracts.AccountProjection current() {
        return base.host.accounts().list(base.workspace, account.siteId()).accounts().stream()
                .filter(value -> value.accountId().equals(account.accountId()))
                .findFirst()
                .orElseThrow();
    }

    CanonicalPayload manage(String operation) throws Exception {
        Object payload = operation.equals("browser.capture") ? TARGET : Map.of();
        return accounts.manage(
                base.invocation(operation, payload, session.access.lease().generation()),
                new BrowserCommands.Invocation(operation, base.json.encode(payload)),
                session,
                worker);
    }

    void changeAccess(BrowserContracts.ControlMode mode, long generation, Duration remaining) {
        session.access = new BrowserSessionState.Access(
                lease(mode, generation, remaining),
                session.access.permission(),
                Optional.empty(),
                new CancellationSource());
    }

    BrowserContracts.AccessLease lease(BrowserContracts.ControlMode mode, long generation, Duration remaining) {
        return new BrowserContracts.AccessLease(
                mode,
                "lease-" + generation,
                generation,
                base.host.clock().instant().plus(remaining),
                Set.of(InteractiveBrowserHostFixture.ORIGIN));
    }

    void site(long revision, boolean enabled, URI origin) throws Exception {
        var site = new SiteContracts.Site(
                "account-site",
                revision,
                revision,
                "账号网站",
                origin,
                Set.of(origin),
                SiteContracts.SiteCredential.none(),
                Optional.empty(),
                enabled,
                base.host.clock().instant());
        new H2ManagedExtensionStore(base.host.database(), base.host.clock())
                .inTransaction(new ExtensionId(BuiltinExtensionIds.SITE), tx -> {
                    tx.put("documents." + base.workspace, site.id(), revision - 1, base.json.encode(site));
                    return null;
                });
    }

    void deleteSite() throws Exception {
        new H2ManagedExtensionStore(base.host.database(), base.host.clock())
                .inTransaction(new ExtensionId(BuiltinExtensionIds.SITE), tx -> {
                    tx.delete("documents." + base.workspace, account.siteId(), 1);
                    return null;
                });
    }

    private Object dispatch(String operation, Object[] arguments) throws Exception {
        return switch (operation) {
            case "saveInteractiveState" -> {
                saves++;
                beforeSave.run();
                yield privateResult((BrowserStorageHandler<?>) arguments[1], STATE.clone());
            }
            case "captureInteractiveCredentials" -> {
                captures++;
                beforeCapture.run();
                yield privateResult(
                        (BrowserStorageHandler<?>) arguments[4],
                        "alice\0private-password".getBytes(StandardCharsets.UTF_8));
            }
            case "prepareInteractiveCredentials" -> List.of(new BrowserContracts.LoginForm("登录", "用户名", "密码", TARGET));
            default -> throw new UnsupportedOperationException(operation);
        };
    }

    private Object privateResult(BrowserStorageHandler<?> handler, byte[] bytes) throws Exception {
        privateBuffers.add(bytes);
        try {
            return handler.handle(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static CommandIdentity identity(String operation, long revision) {
        return new CommandIdentity(
                "account-boundary/" + operation, UUID.randomUUID().toString(), revision, "0".repeat(64));
    }

    @Override
    public void close() {
        accounts.release(session);
        base.close();
    }
}
