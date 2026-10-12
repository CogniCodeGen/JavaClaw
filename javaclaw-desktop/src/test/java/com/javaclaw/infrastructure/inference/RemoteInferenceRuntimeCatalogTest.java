package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceCatalogPort.RuntimeInstallation;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RemoteInferenceRuntimeCatalogTest {
    @TempDir Path temporary;

    @Test void projectsGenericMetadataAndRetainsMissingRuntimeAsUnavailable() {
        Map<String, RuntimeInstallation> installed = new LinkedHashMap<>();
        var summary = new PluginSummary("example-model", "模型", "3.1", "", "example", "a".repeat(64), true,
                true, StartupPolicy.MANUAL, State.STOPPED, 1, Set.of("MODEL"), "");
        var contribution = new ContributionInfo("provider", "MODEL", "2.0", "Provider", "", "{}",
                "{\"inference\":{\"engine\":\"example-engine\",\"engineVersion\":\"3.1\",\"adapterVersion\":\"2\","
                        + "\"protocol\":{\"major\":1,\"minor\":2},\"capabilities\":[\"chat\",\"model-type:generation:llama\"],"
                        + "\"parameterSchema\":{\"type\":\"object\"}}}", "{}");
        var details = new PluginDetails(summary, "{}", new ConfigurationSnapshot(1, "{}", "{}", Map.of(), Set.of()),
                Set.of(), List.of(contribution));
        boolean[] present = {true};
        PluginManagementService plugins = (PluginManagementService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManagementService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "list" -> present[0] ? List.of(summary) : List.of();
                    case "details" -> details;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        InferenceCatalogPort catalog = (InferenceCatalogPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{InferenceCatalogPort.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "runtime" -> Optional.ofNullable(installed.get(args[0]));
                    case "runtimes" -> List.copyOf(installed.values());
                    case "saveRuntime" -> { var value = (RuntimeInstallation) args[0]; installed.put(value.manifest().runtimeId(), value); yield null; }
                    case "setActiveRuntime" -> {
                        var before = installed.get(args[1]); installed.put((String) args[1], new RuntimeInstallation(before.manifest(),
                                before.installPath(), InferenceCatalogPort.RuntimeState.ACTIVE, true, before.installedAt())); yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var projection = new RemoteInferenceRuntimeCatalog(plugins, null, catalog, new ObjectMapper(), null);

        projection.refresh("workspace-a");

        var runtime = installed.get("example-model");
        assertNotNull(runtime);
        assertEquals("example-engine", runtime.manifest().engine());
        assertEquals("example-model", runtime.manifest().runtimeId());
        assertEquals(Set.of("llama"), runtime.manifest().supportedModelTypes(InferenceModelProfile.Kind.GENERATION));
        assertEquals("object", runtime.manifest().parameterSchema().get("type"));
        assertTrue(runtime.installPath().startsWith("plugin:example-model/"));
        present[0] = false;
        projection.refresh("workspace-a");
        assertEquals(InferenceCatalogPort.RuntimeState.FAILED, installed.get("example-model").state());
        assertFalse(installed.get("example-model").active());
    }

    @Test void statusCallbacksAreAsynchronousCoalescedAndResolveGlobalEventsToTheCurrentWorkspace() throws Exception {
        AtomicReference<Consumer<PluginStatusEvent>> observer = new AtomicReference<>();
        AtomicBoolean subscriptionClosed = new AtomicBoolean(), blockNext = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        List<String> refreshedWorkspaces = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), followedUp = new CountDownLatch(1);
        PluginManagementService plugins = (PluginManagementService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManagementService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "observeStatus" -> {
                        observer.set((Consumer<PluginStatusEvent>) args[0]);
                        yield (AutoCloseable) () -> subscriptionClosed.set(true);
                    }
                    case "list" -> {
                        int count = calls.incrementAndGet();
                        refreshedWorkspaces.add((String) args[0]);
                        if (blockNext.compareAndSet(true, false)) {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("等待目录刷新测试解锁超时");
                        }
                        if (count == 3) followedUp.countDown();
                        yield List.of();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        InferenceCatalogPort catalog = (InferenceCatalogPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{InferenceCatalogPort.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "runtimes" -> List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:runtime-catalog", "sa", "");
        WorkspaceManager workspaces = new WorkspaceManager(new DataRoot(temporary), new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource)) {
            @Override public String getCurrentWorkspaceId() { return "workspace-a"; }
        };
        try (var tasks = new ManagedTaskExecutor();
             var projection = new RemoteInferenceRuntimeCatalog(plugins, workspaces, catalog, new ObjectMapper(), tasks)) {
            projection.init();
            blockNext.set(true);
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> observer.get().accept(event("")));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    for (int index = 0; index < 50; index++) {
                        observer.get().accept(event(index % 2 == 0 ? "" : "workspace-a"));
                    }
                });
                assertEquals(2, calls.get(), "回调不能等待正在刷新的目录，也不能并发读取插件门面");
            } finally {
                release.countDown();
            }
            assertTrue(followedUp.await(5, TimeUnit.SECONDS), "刷新期间的状态变化必须在下一轮处理");
            projection.close();
            assertTrue(subscriptionClosed.get());
            assertEquals(List.of("workspace-a", "workspace-a", "workspace-a"), refreshedWorkspaces);
            observer.get().accept(event(""));
            assertEquals(3, calls.get(), "关闭后不能再安排刷新");
        }
    }

    private static PluginStatusEvent event(String workspace) {
        return new PluginStatusEvent(workspace, "example-model", State.STOPPED, 1, "状态变化", Instant.now());
    }
}
