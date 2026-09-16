package com.javaclaw.server.extension;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.client.BrowserActionResult;
import com.javaclaw.browser.client.BrowserStorageHandler;
import com.javaclaw.browser.client.BrowserWorkerException;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.browser.client.InteractiveBrowserNetworkExchange;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceCallScope;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.InputRequestService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.grant.BrowserGrantService;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;
import com.javaclaw.server.site.account.SiteAccountService;

/** 使用真实 H2、来源授权与宿主 owner 校验，Worker 只提供私有观察回执，不启动网络。 */
final class InteractiveBrowserHostFixture implements AutoCloseable {
    static final URI ORIGIN = URI.create("https://docs.example.com");
    final CanonicalJson json = new CanonicalJson();
    final TestClock clock = new TestClock();
    final SecretVaultService vault;
    final SiteBrowserHostContext host;
    final BrowserGrantService grants;
    final PrivateNetworkGrantService privateGrants;
    final WorkspaceId workspace;
    final ThreadId thread;
    final SiteInteractiveBrowserService service;
    volatile BrowserContracts.SessionView view;
    volatile int opens;
    volatile int closes;
    volatile int actions;
    volatile int saves;
    boolean failAction;
    boolean blockAction;
    boolean denyAction;
    boolean denyOpen;
    volatile boolean blockClose;
    volatile boolean blockLease;
    volatile boolean failLease;
    volatile boolean wrongLeaseOwner;
    volatile boolean wrongLeaseValue;
    volatile boolean failClose;
    volatile int fills;
    volatile byte[] filledInput;
    Boolean originDecision;
    volatile TurnId continuationTurn;
    volatile URI continuationOrigin;
    InteractiveBrowserNetworkExchange network;
    final CountDownLatch leaseReleased = new CountDownLatch(1);
    final CountDownLatch sessionClosed = new CountDownLatch(1);
    final CountDownLatch actionStarted = new CountDownLatch(1);
    final CountDownLatch actionGate = new CountDownLatch(1);
    final CompletableFuture<Void> closeGate = new CompletableFuture<>();
    final CountDownLatch leaseStarted = new CountDownLatch(1);
    final CompletableFuture<Void> leaseGate = new CompletableFuture<>();

    InteractiveBrowserHostFixture(Path directory) {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        var core = new CoreCommandService(database, json, clock);
        workspace = core.createWorkspace(identity("workspace/create", 0), "Browser", directory.resolve("workspace"))
                .id();
        thread = core.createThread(
                        identity("thread/create", 0),
                        workspace,
                        Optional.empty(),
                        ThreadExecutionIntent.WORKSPACE,
                        "Browser")
                .id();
        vault = new SecretVaultService(database, new Keys(), json, clock, new SecureRandom());
        host = new SiteBrowserHostContext(
                database,
                core,
                new SiteAccountService(database, vault, json, clock),
                new AttachmentService(database, json, clock),
                new PermissionProfileService(database, json, clock),
                new ProviderService(database, ignored -> false, json, clock),
                new InputRequestService(database, json, clock),
                json,
                clock);
        host.permissions().instantiatePreset(identity("permissionProfile/preset/instantiate", 0), permission());
        grants = new BrowserGrantService(database, json, clock);
        privateGrants = new PrivateNetworkGrantService(database, json, clock);
        grants.confirm(identity("browser/confirm", 0), grants.preview(workspace, thread, ORIGIN));
        BrowserWorkerPort port = (BrowserWorkerPort) Proxy.newProxyInstance(
                BrowserWorkerPort.class.getClassLoader(),
                new Class<?>[] {BrowserWorkerPort.class},
                (proxy, method, args) -> worker(method.getName(), args));
        service = new SiteInteractiveBrowserService(host, Optional.of(port), grants, privateGrants, (turn, origin) -> {
            continuationTurn = turn;
            continuationOrigin = origin;
        });
        host.inputs().onChanged(record -> {
            if (originDecision != null && record.pending()) {
                host.inputs()
                        .resolve(
                                identity("turn/input/resolve", record.revision()),
                                new InputJobRpcContracts.InputResolvePayload(
                                        record.request().id(), json.encode(Map.of("allow", originDecision))));
            }
        });
    }

    IsolatedServiceInvocation invocation(String operation, Object payload, long revision) {
        var request = new BrowserCommands.Invocation(operation, json.encode(payload));
        return new IsolatedServiceInvocation(
                new ExtensionId(BuiltinExtensionIds.SITE),
                workspace,
                permission(),
                BrowserCommands.SERVICE,
                json.encode(request),
                new CancellationSource(),
                new IsolatedServiceCallScope(
                        Optional.of(thread),
                        Optional.empty(),
                        Optional.of(UUID.randomUUID().toString()),
                        revision));
    }

    BrowserSessionState session() {
        var access =
                new BrowserSessionState.Access(view.lease(), permission(), Optional.empty(), new CancellationSource());
        BrowserSessionState result = new BrowserSessionState(
                view.sessionId(), view.owner(), Optional.empty(), access, false, clock.instant());
        result.view = view;
        return result;
    }

    BrowserActionResult observation(BrowserContracts.SessionView owner) {
        var page = new BrowserContracts.PageSnapshot(
                "page", ORIGIN.resolve("/page"), "Page", "safe", List.of(), List.of());
        return new BrowserActionResult(
                new BrowserContracts.Observation(owner, page, Optional.empty(), Optional.empty()), new byte[0]);
    }

    private Object worker(String name, Object[] args) throws Exception {
        return switch (name) {
            case "interactiveAvailable" -> true;
            case "openInteractive" ->
                workerOpen((BrowserContracts.OpenTask) args[0], (InteractiveBrowserNetworkExchange) args[2]);
            case "updateInteractiveLease" -> workerLease((BrowserContracts.AccessLease) args[1]);
            case "actInteractive" -> workerAction();
            case "saveInteractiveState" -> workerSave((BrowserStorageHandler<?>) args[1]);
            case "fillInteractiveCredentials" -> workerFill(args);
            case "interactiveStatus" -> view;
            case "closeInteractive" -> workerClose();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(name);
        };
    }

    private BrowserActionResult workerOpen(BrowserContracts.OpenTask task, InteractiveBrowserNetworkExchange callback) {
        opens++;
        network = callback;
        view = new BrowserContracts.SessionView(
                task.sessionId(),
                task.owner(),
                BrowserContracts.SessionState.OPEN,
                task.lease(),
                List.of(new BrowserContracts.Tab("page", task.uri(), "Page", true)));
        if (denyOpen) {
            deniedOrigin();
        }
        return observation(view);
    }

    private BrowserContracts.SessionView workerLease(BrowserContracts.AccessLease lease) {
        var before = view;
        view = new BrowserContracts.SessionView(view.sessionId(), view.owner(), view.state(), lease, view.tabs());
        if (lease.mode() == BrowserContracts.ControlMode.NONE) {
            leaseReleased.countDown();
        }
        var result = view;
        if (blockLease) {
            blockLease = false;
            leaseStarted.countDown();
            leaseGate.join();
        }
        if (failLease) {
            throw new BrowserWorkerException("BROWSER_CONTROL_FAILED");
        }
        if (wrongLeaseValue) {
            return before;
        }
        return wrongLeaseOwner
                ? new BrowserContracts.SessionView(
                        result.sessionId(),
                        new BrowserContracts.Owner(WorkspaceId.random(), ThreadId.random(), Optional.empty()),
                        result.state(),
                        result.lease(),
                        result.tabs())
                : result;
    }

    private BrowserActionResult workerAction() throws InterruptedException {
        actions++;
        var initial = view;
        actionStarted.countDown();
        if (blockAction && !actionGate.await(4, TimeUnit.SECONDS)) {
            throw new IllegalStateException("fixture action timed out");
        }
        if (denyAction) {
            deniedOrigin();
        }
        if (failAction) {
            throw new BrowserWorkerException("BROWSER_ACTION_UNCONFIRMED");
        }
        return observation(initial);
    }

    private Object workerSave(BrowserStorageHandler<?> handler) throws Exception {
        saves++;
        byte[] state = "{\"cookies\":[],\"origins\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            return handler.handle(state);
        } finally {
            java.util.Arrays.fill(state, (byte) 0);
        }
    }

    private BrowserContracts.SessionView workerClose() {
        closes++;
        sessionClosed.countDown();
        if (blockClose) {
            closeGate.join();
        }
        if (failClose) {
            throw new IllegalStateException("fixture close failed");
        }
        return view;
    }

    private BrowserActionResult workerFill(Object[] args) throws InterruptedException {
        ((com.javaclaw.api.CancellationToken) args[5]).throwIfCancelled();
        fills++;
        filledInput = (byte[]) args[4];
        var initial = view;
        if (!ORIGIN.equals(args[2]) || !view.lease().equals(args[1])) {
            throw new SecurityException("fixture credential origin or lease mismatch");
        }
        actionStarted.countDown();
        if (blockAction && !actionGate.await(4, TimeUnit.SECONDS)) {
            throw new IllegalStateException("fixture credential fill timed out");
        }
        return observation(initial);
    }

    void deniedOrigin() {
        network.deniedOrigin(
                URI.create("https://blocked.example.com"), view.lease().generation());
        throw new BrowserWorkerException("BROWSER_ACTION_FAILED");
    }

    void advance(Duration elapsed) {
        clock.now = clock.now.plus(elapsed);
    }

    TurnId turn() {
        clock.now = clock.now.plusSeconds(1);
        var budget = new TurnBudget(1000, 1000, 5, 0, Duration.ofMinutes(1));
        var selection = new TurnContractFixtures.Selection(
                budget,
                TurnContractFixtures.ROLE,
                TurnContractFixtures.PROVIDER,
                new PermissionProfileRef("browser-tests", 1));
        var catalog = new ToolCatalogSnapshot(TurnId.random(), 1, List.of(), permission(), clock.instant());
        var request = TurnContractFixtures.request(
                thread,
                selection,
                host.core().workspaceForThread(thread).root(),
                TurnContractFixtures.PROMPT_SNAPSHOT,
                catalog,
                new CorePayloads.Message(MessageRole.USER, "浏览", List.of(), Optional.empty()),
                Optional.empty());
        var turn = host.core().startTurn(identity("turn/start", 0), request);
        journal().transition(turn.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        return turn.id();
    }

    void finish(TurnId turn) {
        journal().transition(turn, TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
        service.finishTurn(turn);
    }

    IsolatedServiceInvocation modelInvocation(TurnId turn, String operation, Object payload) {
        var user = invocation(operation, payload, 0);
        return new IsolatedServiceInvocation(
                user.caller(),
                workspace,
                permission(),
                user.serviceId(),
                user.request(),
                user.cancellation(),
                new IsolatedServiceCallScope(
                        Optional.of(thread), Optional.of(turn), user.scope().idempotencyKey(), 0));
    }

    private H2TurnJournal journal() {
        return new H2TurnJournal(host.database(), CoreItemCodecs.createRegistry(json), json, clock);
    }

    private CommandIdentity identity(String method, long revision) {
        return new CommandIdentity(method, UUID.randomUUID().toString(), revision, "0".repeat(64));
    }

    private static PermissionProfile permission() {
        var base = TurnContractFixtures.TOOL_CATALOG.permissionCeiling();
        return new PermissionProfile(
                "browser-tests",
                1,
                base.files(),
                base.network(),
                base.processes(),
                new ToolPermission(BrowserCommands.TOOL_NAMES, ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.NONE),
                base.resources());
    }

    @Override
    public void close() {
        closeGate.complete(null);
        leaseGate.complete(null);
        try {
            service.close();
        } finally {
            vault.close();
        }
    }

    private static final class TestClock extends Clock {
        private volatile Instant now = Instant.now();

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

    private static final class Keys implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String id) {
            return Optional.ofNullable(keys.get(id)).map(byte[]::clone);
        }

        @Override
        public void store(String id, byte[] value) {
            keys.put(id, value.clone());
        }

        @Override
        public void delete(String id) {
            keys.remove(id);
        }
    }
}
