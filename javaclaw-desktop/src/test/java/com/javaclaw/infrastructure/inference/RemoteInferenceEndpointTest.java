package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceApiServerControlPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RemoteInferenceEndpointTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<InferenceCatalogPort.GatewayConfiguration> configuration = new AtomicReference<>(config("provider-a"));
    private final CountDownLatch configureEntered = new CountDownLatch(1);
    private volatile Consumer<PluginStatusEvent> observer;
    private volatile long revision = 1;
    private volatile State providerState = State.READY;
    private volatile boolean blockFirst, subscriptionClosed, failProviderB;
    private volatile CompletableFuture<InvocationResult> blocked;
    private RemoteInferenceGateway gateway;
    private ManagedTaskExecutor tasks;

    @BeforeEach void initialize() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:endpoint-" + UUID.randomUUID(), "sa", "");
        var workspaces = new WorkspaceManager(new DataRoot(temporary), new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource)) {
            @Override public String getCurrentWorkspaceId() { return "workspace-a"; }
        };
        InferenceCatalogPort catalog = (InferenceCatalogPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{InferenceCatalogPort.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "gatewayConfiguration" -> configuration.get();
                    case "apiKeys", "publishedModels" -> List.of();
                    case "runtime" -> java.util.Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PluginManagementService plugins = (PluginManagementService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManagementService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "list" -> List.of(summary("provider-a"), summary("provider-b"));
                    case "details" -> new PluginDetails(summary((String) args[1]), "{}", new ConfigurationSnapshot(revision, "{}", "{}", Map.of(), Set.of()), Set.of(), List.of());
                    case "diagnostics" -> new Diagnostics(providerState, "instance-" + revision, 42, "", List.of(), Map.of());
                    case "observeStatus" -> { observer = (Consumer<PluginStatusEvent>) args[0]; yield (AutoCloseable) () -> subscriptionClosed = true; }
                    case "acquireRun" -> new Lease((String) args[1], revision);
                    case "invoke" -> invoke(args);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        gateway = new RemoteInferenceGateway(plugins, workspaces, catalog, (asset, cancelled) -> { }, json);
        tasks = new ManagedTaskExecutor();
    }
    @AfterEach void close() { gateway.close(); tasks.close(); }

    @Test void explicitOwnerAndExactInstanceCloseTheOldPortBeforeStartingTheNext() throws Exception {
        gateway.reconfigure(); Lease first = calls.getFirst().lease(); assertFalse(first.closed);
        configuration.set(config("provider-b")); gateway.reconfigure();
        assertEquals(List.of("provider-a:1:true", "provider-a:1:false", "provider-b:1:true"), endpointCalls());
        assertSame(first, calls.get(1).lease()); assertTrue(first.closed);
        Lease second = calls.get(2).lease(); revision = 2; gateway.reconfigure();
        assertEquals(List.of("provider-a:1:true", "provider-a:1:false", "provider-b:1:true", "provider-b:1:false", "provider-b:2:true"), endpointCalls());
        assertTrue(second.closed); assertFalse(calls.getLast().lease().closed);
        gateway.close(); assertTrue(calls.getLast().lease().closed);
    }

    @Test void multipleProvidersWithoutAnOwnerDoNotBreakTheModelPageSnapshot() {
        configuration.set(config(""));
        var snapshot = gateway.serviceSnapshot();
        assertEquals(InferenceApiServerControlPort.State.CONFIGURED_STOPPED, snapshot.api().state());
        assertEquals("STOPPED", snapshot.processState());
    }

    @Test void readyDuringAnInFlightApplyIsNotLostAndDrainReleasesTheEndpointLease() throws Exception {
        blockFirst = true; gateway.init(tasks);
        assertTrue(configureEntered.await(5, TimeUnit.SECONDS));
        revision = 2; observer.accept(new PluginStatusEvent("workspace-a", "provider-a", State.READY, 2, "升级就绪", Instant.now()));
        blocked.complete(new InvocationResult("configured", "{}"));
        await(() -> endpointCalls().contains("provider-a:2:true"));
        Call newest = calls.stream().filter(call -> call.operation().equals("gateway.configure") && call.enabled() && call.lease().version == 2).reduce((first, second) -> second).orElseThrow();
        assertFalse(newest.lease().closed);
        providerState = State.DRAINING; observer.accept(new PluginStatusEvent("workspace-a", "provider-a", State.DRAINING, 2, "排空", Instant.now()));
        await(() -> newest.lease().closed);
        assertTrue(endpointCalls().contains("provider-a:2:false"));
        gateway.close(); assertTrue(subscriptionClosed);
    }

    @Test void failedTakeoverClosesTheCandidateAndCanRestoreTheSavedOwner() throws Exception {
        gateway.reconfigure(); Lease original = calls.getFirst().lease();
        failProviderB = true; configuration.set(config("provider-b"));
        assertThrows(Exception.class, gateway::reconfigure);
        assertTrue(original.closed); assertTrue(calls.getLast().lease().closed);
        configuration.set(config("provider-a")); gateway.reconfigure();
        assertEquals(InferenceApiServerControlPort.State.AVAILABLE, gateway.status().state());
        assertEquals("provider-a:1:true", endpointCalls().getLast());
    }

    private PluginInvocation invoke(Object[] arguments) throws Exception {
        Lease lease = (Lease) arguments[0]; String operation = (String) arguments[3];
        boolean enabled = json.readTree((String) arguments[4]).path("enabled").asBoolean();
        calls.add(new Call(lease, operation, enabled));
        var result = new CompletableFuture<InvocationResult>();
        if (operation.equals("gateway.configure") && enabled && blockFirst) {
            blockFirst = false; blocked = result; configureEntered.countDown();
        } else if (operation.equals("gateway.configure") && enabled && failProviderB && lease.pluginId().equals("provider-b")) {
            result.completeExceptionally(new PluginException("BIND_FAILED", "端口启动失败"));
        } else result.complete(new InvocationResult("configured", "{}"));
        return new PluginInvocation() {
            public String requestId() { return UUID.randomUUID().toString(); }
            public CompletableFuture<InvocationResult> completion() { return result; }
            public boolean cancel() { return result.cancel(true); }
        };
    }
    private List<String> endpointCalls() {
        return calls.stream().filter(value -> value.operation().equals("gateway.configure"))
                .map(value -> value.lease().pluginId() + ":" + value.lease().version + ":" + value.enabled()).toList();
    }
    private PluginSummary summary(String id) {
        return new PluginSummary(id, id, "1", "", "fixture", "a".repeat(64), true, true, StartupPolicy.MANUAL,
                providerState, revision, Set.of("MODEL"), "");
    }
    private static InferenceCatalogPort.GatewayConfiguration config(String provider) {
        return new InferenceCatalogPort.GatewayConfiguration(true, "127.0.0.1", 18080, false, false, "", "", 4096, 30, false, Instant.now(), provider);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("等待异步监听设置超时"); Thread.sleep(10); }
    }
    private record Call(Lease lease, String operation, boolean enabled) { }
    private static final class Lease implements RunLease {
        private final String plugin; private final long version; private volatile boolean closed;
        Lease(String plugin, long version) { this.plugin = plugin; this.version = version; }
        public String runId() { return "endpoint"; }
        public String workspaceId() { return "workspace-a"; }
        public String pluginId() { return plugin; }
        public String artifactHash() { return "a".repeat(64); }
        public long configurationRevision() { return version; }
        public String instanceId() { return plugin + "-" + version; }
        public Set<String> grantedPermissions() { return Set.of(); }
        public List<ContributionInfo> contributions() { return List.of(new ContributionInfo("model", "MODEL", "2", "fixture", "", "{}")); }
        public String manifestJson() { return "{}"; }
        public void close() { closed = true; }
    }
}
