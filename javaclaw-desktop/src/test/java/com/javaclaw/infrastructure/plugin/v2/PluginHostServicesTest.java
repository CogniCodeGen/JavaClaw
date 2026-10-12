package com.javaclaw.infrastructure.plugin.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.infrastructure.agent.PluginHostServices;
import com.javaclaw.platform.spring.WorkspaceContextHandle;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 SQL 与回调注册表验证插件存储、调度归属和撤权边界。 */
class PluginHostServicesTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void storageUsesVerifiedWorkspaceAndPluginAndHidesReservedKeys() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.invoke("plugin-a", "storage", "put", """
                    {"key":"setting","value":"a","workspaceId":"workspace-b","pluginId":"plugin-b"}
                    """);
            fixture.invoke("plugin-b", "storage", "put", "{\"key\":\"setting\",\"value\":\"b\"}");
            fixture.store("workspace-b", "plugin-a", "setting", "other-workspace");
            fixture.store("workspace-a", "plugin-a", "__host:schedule:task-a", "task-a");
            assertEquals("a", fixture.invoke("plugin-a", "storage", "get", "{\"key\":\"setting\"}").path("value").asText());
            assertEquals("b", fixture.invoke("plugin-b", "storage", "get", "{\"key\":\"setting\"}").path("value").asText());
            assertEquals(json.readTree("[\"setting\"]"), fixture.invoke("plugin-a", "storage", "keys", "{}"));
            for (String operation : List.of("get", "put", "remove"))
                assertThrows(IllegalArgumentException.class, () -> fixture.invoke("plugin-a", "storage", operation,
                        "{\"key\":\"__host:schedule:task-a\",\"value\":\"overwrite\"}"));
            fixture.invoke("plugin-b", "storage", "remove", "{\"key\":\"setting\"}");
            assertEquals("a", fixture.invoke("plugin-a", "storage", "get", "{\"key\":\"setting\"}").path("value").asText());
            assertEquals("task-a", fixture.value("workspace-a", "plugin-a", "__host:schedule:task-a"));
        }
    }

    @Test void oldWorkspaceAndMissingPermissionsAreRejectedBeforeAccess() throws Exception {
        try (var fixture = new Fixture()) {
            var denied = assertThrows(PluginException.class, () -> fixture.registry.invoke("storage",
                    fixture.request("workspace-a", "plugin-a", "put", "{\"key\":\"denied\"}", Set.of()),
                    () -> false, ignored -> { }));
            assertEquals("PERMISSION_DENIED", denied.code());
            fixture.services.bind(fixture.runtime("workspace-b"));
            assertThrows(SecurityException.class, () -> fixture.invoke("plugin-a", "storage", "keys", "{}"));
            assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM plugin_storage", Integer.class));
            fixture.services.unbind("workspace-b");
            assertThrows(SecurityException.class, () -> fixture.registry.invoke("storage",
                    fixture.request("workspace-b", "plugin-a", "keys", "{}", Set.of("host.storage")),
                    () -> false, ignored -> { }));
        }
    }

    @Test void pluginSchedulesNeverReceiveUserToolsAndReleaseDeniedOrCompletedLeases() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.store("workspace-a", "plugin-a", "__host:schedule:owned", "owned");
            var authorization = fixture.services.scheduleAuthorization();
            try (var grant = authorization.authorize("workspace-a", "owned")) {
                assertEquals(PermissionSet.NONE, grant.permissions());
                assertFalse(fixture.leases.getLast().closed);
            }
            assertTrue(fixture.leases.getLast().closed);
            fixture.permissions = Set.of();
            assertThrows(SecurityException.class, () -> authorization.authorize("workspace-a", "owned"));
            assertTrue(fixture.leases.getLast().closed);
            int acquired = fixture.leases.size();
            try (var grant = authorization.authorize("workspace-a", "user-task")) {
                assertEquals(PermissionSet.UNRESTRICTED, grant.permissions());
            }
            assertEquals(acquired, fixture.leases.size());
            fixture.store("workspace-a", "plugin-b", "__host:schedule:owned", "owned");
            assertThrows(SecurityException.class, () -> authorization.authorize("workspace-a", "owned"));
            assertEquals(acquired, fixture.leases.size());
        }
    }

    @Test void securityEventsDisableOnlyOwnedTasksAndCancelQueuedOrActiveExecution() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.schedules.add("enabled", true, ScheduleApplicationService.RuntimeState.ENABLED);
            fixture.schedules.add("running", false, ScheduleApplicationService.RuntimeState.RUNNING);
            fixture.schedules.add("other-plugin", true, ScheduleApplicationService.RuntimeState.ENABLED);
            fixture.schedules.add("user-task", true, ScheduleApplicationService.RuntimeState.ENABLED);
            fixture.store("workspace-a", "plugin-a", "__host:schedule:enabled", "enabled");
            fixture.store("workspace-a", "plugin-a", "__host:schedule:running", "running");
            fixture.store("workspace-a", "plugin-b", "__host:schedule:other-plugin", "other-plugin");
            fixture.event("workspace-a", "plugin-a", SecurityReason.PERMISSIONS_REDUCED, Set.of("host.storage"));
            fixture.event("workspace-b", "plugin-a", SecurityReason.REVOKE, Set.of());
            assertTrue(fixture.schedules.disabled.isEmpty());
            fixture.event("workspace-a", "plugin-a", SecurityReason.PERMISSIONS_REDUCED, Set.of("host.schedule"));
            assertEquals(Set.of("enabled", "running"), fixture.schedules.disabled.keySet());
            assertTrue(fixture.schedules.disabled.values().stream().allMatch(value -> value ==
                    ScheduleApplicationService.DisablePolicy.CANCEL_ACTIVE));
            assertTrue(fixture.schedules.tasks.get("other-plugin").enabled());
            assertTrue(fixture.schedules.tasks.get("user-task").enabled());
            assertEquals("enabled", fixture.value("workspace-a", "plugin-a", "__host:schedule:enabled"));
            fixture.services.unbind("workspace-a");
            assertTrue(fixture.schedules.disabled.containsKey("other-plugin"));
            assertFalse(fixture.schedules.disabled.containsKey("user-task"));
        }
    }

    @Test void scheduleCreationKeepsOwnershipAndCancellationCannotTargetOtherPlugins() throws Exception {
        try (var fixture = new Fixture()) {
            String id = fixture.invoke("plugin-a", "schedule", "create", """
                    {"name":"插件任务","prompt":"你好","intervalMinutes":2,"unattendedToolsAuthorized":true}
                    """).path("taskId").asText();
            assertFalse(id.isBlank());
            assertFalse(fixture.schedules.lastSave.unattendedToolsAuthorized());
            assertEquals(id, fixture.value("workspace-a", "plugin-a", "__host:schedule:" + id));
            assertThrows(SecurityException.class, () -> fixture.invoke("plugin-b", "schedule", "cancel",
                    json.createObjectNode().put("taskId", id).toString()));
            assertTrue(fixture.schedules.tasks.containsKey(id));
            fixture.invoke("plugin-a", "schedule", "cancel", json.createObjectNode().put("taskId", id).toString());
            assertFalse(fixture.schedules.tasks.containsKey(id));
            assertNull(fixture.value("workspace-a", "plugin-a", "__host:schedule:" + id));
        }
    }

    @Test void cancelledCallbacksCannotWriteStorageOrCreateScheduledTasks() throws Exception {
        try (var fixture = new Fixture()) {
            for (String service : List.of("storage", "schedule")) {
                String operation = service.equals("storage") ? "put" : "create";
                assertThrows(CancellationException.class, () -> fixture.registry.invoke(service,
                        fixture.request("workspace-a", "plugin-a", operation,
                                "{\"key\":\"denied\",\"value\":\"value\",\"prompt\":\"你好\"}",
                                Set.of("host.storage", "host.schedule")), () -> true, ignored -> { }));
            }
            assertTrue(fixture.schedules.tasks.isEmpty());
            assertNull(fixture.schedules.lastSave);
            assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM plugin_storage", Integer.class));
        }
    }

    @Test void uninstallRetainsDisabledTasksUnlessPrivateDataDeletionWasRequested() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.schedules.add("owned", true, ScheduleApplicationService.RuntimeState.RUNNING);
            fixture.schedules.add("other", true, ScheduleApplicationService.RuntimeState.ENABLED);
            fixture.store("workspace-a", "plugin-a", "__host:schedule:owned", "owned");
            fixture.store("workspace-a", "plugin-a", "setting", "keep-me");
            fixture.store("workspace-a", "plugin-b", "__host:schedule:other", "other");
            fixture.event("workspace-a", "plugin-a", SecurityReason.UNINSTALL, Set.of());
            assertFalse(fixture.schedules.tasks.get("owned").enabled());
            assertEquals("keep-me", fixture.value("workspace-a", "plugin-a", "setting"));
            assertEquals("owned", fixture.value("workspace-a", "plugin-a", "__host:schedule:owned"));
            fixture.event("workspace-a", "plugin-a", SecurityReason.UNINSTALL_DELETE_DATA, Set.of());
            assertFalse(fixture.schedules.tasks.containsKey("owned"));
            assertNull(fixture.value("workspace-a", "plugin-a", "setting"));
            assertNull(fixture.value("workspace-a", "plugin-a", "__host:schedule:owned"));
            assertTrue(fixture.schedules.tasks.get("other").enabled());
            assertEquals("other", fixture.value("workspace-a", "plugin-b", "__host:schedule:other"));
        }
    }

    @Test void inactiveWorkspaceTasksArePersistentlyDisabledOrDeletedByOwner() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.store("workspace-b", "plugin-a", "__host:schedule:inactive", "inactive");
            fixture.store("workspace-b", "plugin-a", "setting", "private");
            fixture.store("workspace-b", "plugin-b", "__host:schedule:other", "other");
            fixture.jdbc.update("INSERT INTO scheduled_tasks VALUES('workspace-b','inactive',TRUE,3,CURRENT_TIMESTAMP)");
            fixture.jdbc.update("INSERT INTO scheduled_tasks VALUES('workspace-b','other',TRUE,2,CURRENT_TIMESTAMP)");
            fixture.event("workspace-b", "plugin-a", SecurityReason.REVOKE, Set.of());
            assertFalse(fixture.jdbc.queryForObject("SELECT enabled FROM scheduled_tasks WHERE id='inactive'", Boolean.class));
            assertEquals(4, fixture.jdbc.queryForObject("SELECT version FROM scheduled_tasks WHERE id='inactive'", Integer.class));
            assertEquals("private", fixture.value("workspace-b", "plugin-a", "setting"));
            fixture.event("workspace-b", "plugin-a", SecurityReason.UNINSTALL_DELETE_DATA, Set.of());
            assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_tasks WHERE id='inactive'", Integer.class));
            assertNull(fixture.value("workspace-b", "plugin-a", "setting"));
            assertTrue(fixture.jdbc.queryForObject("SELECT enabled FROM scheduled_tasks WHERE id='other'", Boolean.class));
            assertEquals("other", fixture.value("workspace-b", "plugin-b", "__host:schedule:other"));
        }
    }

    private final class Fixture implements AutoCloseable {
        private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:plugin-host-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        private final PluginHostServiceRegistry registry = new PluginHostServiceRegistry();
        private final Schedules schedules = new Schedules();
        private final List<Lease> leases = new ArrayList<>();
        private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
        private Consumer<PluginSecurityEvent> security;
        private Set<String> permissions = Set.of("host.schedule");
        private final PluginHostServices services;

        private Fixture() throws Exception {
            jdbc.execute("CREATE TABLE plugin_storage(workspace_id VARCHAR(100),plugin_id VARCHAR(100),store_key VARCHAR(512),store_value CLOB,updated_at TIMESTAMP,PRIMARY KEY(workspace_id,plugin_id,store_key))");
            jdbc.execute("CREATE TABLE scheduled_tasks(workspace_id VARCHAR(100),id VARCHAR(100),enabled BOOLEAN,version BIGINT,updated_at TIMESTAMP,PRIMARY KEY(workspace_id,id))");
            var plugins = (PluginManagementService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PluginManagementService.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "observeSecurityEvents" -> {
                            @SuppressWarnings("unchecked") var observer = (Consumer<PluginSecurityEvent>) args[0];
                            security = observer; yield (AutoCloseable) () -> security = null;
                        }
                        case "acquireRun" -> {
                            Lease lease = new Lease((String) args[0], (String) args[1], permissions);
                            leases.add(lease); yield lease;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            var agents = (AgentClient) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AgentClient.class},
                    (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
            services = new PluginHostServices(registry, json, jdbc, agents, Runnable::run, plugins);
            services.bind(runtime("workspace-a"));
        }

        private JsonNode invoke(String plugin, String service, String operation, String payload) throws Exception {
            return json.readTree(registry.invoke(service, request("workspace-a", plugin, operation, payload,
                    Set.of("host.storage", "host.schedule")), () -> false, ignored -> { }));
        }
        private PluginHostServiceRegistry.HostRequest request(String workspace, String plugin, String operation,
                                                               String payload, Set<String> permissions) {
            return new PluginHostServiceRegistry.HostRequest(workspace, plugin, "instance", "run", UUID.randomUUID().toString(),
                    operation, payload, permissions, Instant.now().plusSeconds(30), true);
        }
        private void event(String workspace, String plugin, SecurityReason reason, Set<String> revoked) {
            security.accept(new PluginSecurityEvent(workspace, plugin, reason, revoked, Instant.now()));
        }
        private void store(String workspace, String plugin, String key, String value) {
            jdbc.update("INSERT INTO plugin_storage VALUES(?,?,?,?,CURRENT_TIMESTAMP)", workspace, plugin, key, value);
        }
        private String value(String workspace, String plugin, String key) {
            var values = jdbc.query("SELECT store_value FROM plugin_storage WHERE workspace_id=? AND plugin_id=? AND store_key=?",
                    (row, i) -> row.getString(1), workspace, plugin, key);
            return values.isEmpty() ? null : values.getFirst();
        }
        private WorkspaceRuntime runtime(String id) throws Exception {
            Path base = temp.resolve(id);
            var workspace = new WorkspaceContext(id, base, base, base, base, base);
            // 门面只使用路径和调度服务；其余工作区 Bean 在此测试中不参与构造或启动。
            var context = new AnnotationConfigApplicationContext() {
                @Override public <T> T getBean(Class<T> type) {
                    if (type == WorkspaceContext.class) return type.cast(workspace);
                    if (type == ScheduleApplicationService.class) return type.cast(schedules);
                    return null;
                }
            };
            contexts.add(context);
            var handleConstructor = WorkspaceContextHandle.class.getDeclaredConstructor(AnnotationConfigApplicationContext.class);
            handleConstructor.setAccessible(true);
            var runtimeConstructor = WorkspaceRuntime.class.getDeclaredConstructor(WorkspaceContextHandle.class);
            runtimeConstructor.setAccessible(true);
            return runtimeConstructor.newInstance(handleConstructor.newInstance(context));
        }
        @Override public void close() { services.close(); contexts.forEach(AnnotationConfigApplicationContext::close); }
    }

    private static final class Lease implements RunLease {
        private final String workspaceId, pluginId;
        private final Set<String> permissions;
        private boolean closed;
        private Lease(String workspace, String plugin, Set<String> permissions) {
            this.workspaceId = workspace; this.pluginId = plugin; this.permissions = Set.copyOf(permissions);
        }
        public String workspaceId() { return workspaceId; }
        public String pluginId() { return pluginId; }
        public String runId() { return "run"; }
        public String artifactHash() { return "a".repeat(64); }
        public long configurationRevision() { return 1; }
        public String instanceId() { return "instance"; }
        public Set<String> grantedPermissions() { return permissions; }
        public List<ContributionInfo> contributions() { return List.of(); }
        public String manifestJson() { return "{}"; }
        public void close() { closed = true; }
    }

    private static final class Schedules implements ScheduleApplicationService {
        private final Map<String, Task> tasks = new LinkedHashMap<>();
        private final Map<String, DisablePolicy> disabled = new LinkedHashMap<>();
        private SaveCommand lastSave;
        private void add(String id, boolean enabled, RuntimeState state) {
            tasks.put(id, new Task(id, id, "", "interval", 1, 1, "minute", "", "", "", "你好", enabled,
                    1, "", "", "", 0, 0, false, "none", false, false, "", "", state, null, true, List.of()));
        }
        public Snapshot snapshot() { return new Snapshot(new ArrayList<>(tasks.values())); }
        public Task createDraft(String name) {
            String id = UUID.randomUUID().toString(); add(id, false, RuntimeState.PAUSED); return tasks.get(id);
        }
        public OperationResult save(SaveCommand command) {
            lastSave = command; add(command.id(), command.enabled(), command.enabled() ? RuntimeState.ENABLED : RuntimeState.PAUSED);
            return new OperationResult(snapshot(), null, "");
        }
        public OperationResult setEnabled(SaveCommand command, boolean enabled, DisablePolicy policy) {
            disabled.put(command.id(), policy); add(command.id(), enabled, enabled ? RuntimeState.ENABLED : RuntimeState.PAUSED);
            return new OperationResult(snapshot(), null, "");
        }
        public OperationResult delete(String id) { tasks.remove(id); return new OperationResult(snapshot(), null, ""); }
        public OperationResult runNow(String id, boolean allowDisabled) { throw new UnsupportedOperationException(); }
        public AutoCloseable observe(EventListener listener) { return () -> { }; }
    }
}
