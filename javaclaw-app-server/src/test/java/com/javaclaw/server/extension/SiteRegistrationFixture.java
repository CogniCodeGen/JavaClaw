package com.javaclaw.server.extension;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.client.BrowserRegistrationHandler;
import com.javaclaw.browser.client.BrowserRegistrationPort;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.browser.client.InteractiveBrowserNetworkExchange;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceCallScope;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;
import com.javaclaw.server.site.account.SiteAccountService;
import com.javaclaw.server.site.account.SiteRegistrationStore;

/** 固定 Worker 与本地 H2 夹具；不启动真实浏览器，不执行外网或模型调用。 */
final class SiteRegistrationFixture implements AutoCloseable {
    static final WorkspaceId WORKSPACE = WorkspaceId.parse("2527756d-4881-4a7f-aa87-f01f38919061");
    static final URI ORIGIN = URI.create("https://example.com");
    static final byte[] STORAGE = "{\"cookies\":[],\"origins\":[]}".getBytes(StandardCharsets.UTF_8);
    final CanonicalJson json = new CanonicalJson();
    final AdjustableClock clock = new AdjustableClock();
    final H2Database database;
    final SecretVaultService vault;
    final SiteAccountService accounts;
    final SiteRegistrationStore store;
    final Worker worker = new Worker();
    final SiteBrowserTestBroker broker = new SiteBrowserTestBroker();
    final PrivateNetworkGrantService grants;
    final SiteRegistrationService service;
    boolean available = true;

    SiteRegistrationFixture(Path path) throws Exception {
        database = new H2Database(path);
        database.initialize();
        vault = new SecretVaultService(database, new MemoryProtector(), json, clock, new SecureRandom());
        accounts = new SiteAccountService(database, vault, json, clock);
        store = accounts.registrations();
        grants = new PrivateNetworkGrantService(database, json, clock);
        BrowserWorkerPort port = (BrowserWorkerPort) Proxy.newProxyInstance(
                BrowserWorkerPort.class.getClassLoader(),
                new Class<?>[] {BrowserWorkerPort.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "interactiveAvailable" -> available;
                    case "registrations" -> worker;
                    default -> throw new UnsupportedOperationException();
                });
        service = new SiteRegistrationService(
                store, Optional.of(port), new SiteRegistrationNetwork(grants, broker, clock), json, clock);
    }

    SiteRegistrationContracts.Session begin() {
        return call(
                "registration.begin",
                new SiteRegistrationContracts.BeginRequest(ORIGIN.resolve("/login?token=secret")),
                "begin");
    }

    SiteRegistrationContracts.Session status(String id) {
        return call("registration.status", new SiteRegistrationContracts.SessionRequest(id), null);
    }

    SiteRegistrationContracts.Session call(String operation, Object input, String key) {
        return json.decode(
                service.invoke(invocation(WORKSPACE, operation, input, key)), SiteRegistrationContracts.Session.class);
    }

    IsolatedServiceInvocation invocation(WorkspaceId workspace, String operation, Object input, String key) {
        return new IsolatedServiceInvocation(
                new ExtensionId(BuiltinExtensionIds.SITE),
                workspace,
                TurnContractFixtures.TOOL_CATALOG.permissionCeiling(),
                SiteRegistrationContracts.SERVICE,
                json.encode(new SiteRegistrationContracts.ServiceRequest(
                        operation, json.encode(input), Optional.ofNullable(key))),
                new CancellationSource(),
                new IsolatedServiceCallScope(Optional.empty(), Optional.empty(), Optional.ofNullable(key), 0));
    }

    SiteRegistrationContracts.CompleteRequest confirmation(
            SiteRegistrationContracts.Session session, boolean password) {
        return new SiteRegistrationContracts.CompleteRequest(
                session.sessionId(),
                session.access().generation(),
                session.page().pageRevision(),
                password ? Optional.of("candidate") : Optional.empty(),
                "示例网站");
    }

    int siteCount() throws Exception {
        return new H2ManagedExtensionStore(database, clock)
                .inTransaction(
                        new ExtensionId(BuiltinExtensionIds.SITE),
                        tx -> tx.list("documents." + WORKSPACE, "", 500).size());
    }

    static CommandIdentity identity(String key) {
        return new CommandIdentity("registration-test/" + key, key, 0, "a".repeat(64));
    }

    @Override
    public void close() {
        try {
            service.close();
        } finally {
            vault.close();
        }
    }

    final class Worker implements BrowserRegistrationPort {
        SiteRegistrationContracts.WorkerStatus current;
        InteractiveBrowserNetworkExchange network;
        int starts;
        int saves;
        int cancels;
        boolean beginFailure;
        boolean saveReplyLost;
        boolean wrongLease;
        RuntimeException cancelFailure;
        SiteRegistrationContracts.State cancelState = SiteRegistrationContracts.State.CANCELLED;
        Runnable beforeSave = () -> {};
        byte[] exportedState;
        byte[] exportedCredentials;

        @Override
        public SiteRegistrationContracts.WorkerStatus begin(
                SiteRegistrationContracts.WorkerTask task,
                InteractiveBrowserNetworkExchange exchange,
                CancellationToken cancellation) {
            starts++;
            network = exchange;
            var lease = task.lease();
            current = new SiteRegistrationContracts.WorkerStatus(
                    task.sessionId(),
                    SiteRegistrationContracts.State.ACTIVE,
                    new SiteRegistrationContracts.Access(
                            lease.generation(), lease.allowedOrigins(), Set.of(), lease.expiresAt()),
                    new SiteRegistrationContracts.Page(
                            1,
                            Optional.of(ORIGIN.resolve("/account")),
                            "示例网站",
                            List.of(new SiteRegistrationContracts.CredentialCandidate("candidate", ORIGIN, "登录表单"))));
            if (beginFailure) {
                throw new IllegalStateException("启动失败");
            }
            return current;
        }

        @Override
        public SiteRegistrationContracts.WorkerStatus status(String id) {
            if (wrongLease) {
                var access = current.access();
                return new SiteRegistrationContracts.WorkerStatus(
                        id,
                        current.state(),
                        new SiteRegistrationContracts.Access(
                                access.generation() + 1, access.allowedOrigins(), Set.of(), access.expiresAt()),
                        current.page());
            }
            return current;
        }

        @Override
        public SiteRegistrationContracts.WorkerStatus updateLease(
                String id, BrowserContracts.AccessLease lease, CancellationToken cancellation) {
            current = new SiteRegistrationContracts.WorkerStatus(
                    id,
                    current.state(),
                    new SiteRegistrationContracts.Access(
                            lease.generation(), lease.allowedOrigins(), Set.of(), lease.expiresAt()),
                    current.page());
            return current;
        }

        @Override
        public <T> T complete(
                String id, SiteRegistrationContracts.CompleteRequest request, BrowserRegistrationHandler<T> handler) {
            saves++;
            beforeSave.run();
            exportedState = STORAGE.clone();
            exportedCredentials = request.credentialId().isPresent()
                    ? "alice\0password".getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            try {
                T saved = handler.handle(current, exportedState, exportedCredentials);
                if (saveReplyLost) {
                    throw new IllegalStateException("完成回执丢失");
                }
                return saved;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            } finally {
                Arrays.fill(exportedState, (byte) 0);
                Arrays.fill(exportedCredentials, (byte) 0);
            }
        }

        @Override
        public SiteRegistrationContracts.WorkerStatus cancel(String id) {
            cancels++;
            if (cancelFailure != null) {
                throw cancelFailure;
            }
            current = new SiteRegistrationContracts.WorkerStatus(id, cancelState, current.access(), current.page());
            return current;
        }
    }

    static final class AdjustableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-20T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String key) {
            return Optional.ofNullable(keys.get(key)).map(byte[]::clone);
        }

        @Override
        public void store(String key, byte[] value) {
            keys.put(key, value.clone());
        }

        @Override
        public void delete(String key) {
            keys.remove(key);
        }
    }
}
