package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.agent.AgentConversationRunner;
import com.javaclaw.application.agent.RunRequestFactory;
import com.javaclaw.api.conversation.*;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.infrastructure.plugin.v2.PluginHostServiceRegistry;
import com.javaclaw.infrastructure.plugin.v2.PluginHostServiceRegistry.HostRequest;
import com.javaclaw.runtime.WorkspaceRuntime;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 回调只使用宿主核验的身份；旧工作区句柄解绑后不能继续访问新工作区。 */
public final class PluginHostServices implements AutoCloseable {
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final AgentConversationRunner conversations;
    private final List<AutoCloseable> registrations = new ArrayList<>();
    private volatile WorkspaceRuntime workspace;
    private final PluginScheduleAuthorization scheduleAuthorization;
    private final Object callbackMutations = new Object();

    public PluginHostServices(PluginHostServiceRegistry registry, ObjectMapper json, JdbcTemplate jdbc,
                              AgentClient agents, Executor executor,
                              com.javaclaw.application.plugin.v2.PluginManagementService plugins) {
        this.json = json; this.jdbc = jdbc; this.conversations = new AgentConversationRunner(agents, executor);
        this.scheduleAuthorization = new PluginScheduleAuthorization(plugins, jdbc, () -> workspace);
        registrations.add(registry.register("agent", Set.of("chat"), "host.agent.chat", true, this::chat));
        registrations.add(registry.register("memory", Set.of("list", "read"), "host.memory.read", true, this::memory));
        registrations.add(registry.register("storage", Set.of("get", "put", "remove", "keys"), "host.storage", true,
                (request, cancelled, events) -> mutate(cancelled, () -> storage(request, cancelled, events))));
        registrations.add(registry.register("schedule", Set.of("list", "create", "cancel"), "host.schedule", true,
                (request, cancelled, events) -> mutate(cancelled, () -> schedule(request, cancelled, events))));
        registrations.add(plugins.observeSecurityEvents(event -> {
            synchronized (callbackMutations) {
                if (event.reason() == com.javaclaw.application.plugin.v2.PluginManagementService.SecurityReason.UNINSTALL_DELETE_DATA)
                    scheduleAuthorization.deleteData(event.workspaceId(), event.pluginId());
                else if (event.reason() != com.javaclaw.application.plugin.v2.PluginManagementService.SecurityReason.PERMISSIONS_REDUCED
                        || event.revokedPermissions().contains("host.schedule"))
                    scheduleAuthorization.disable(event.workspaceId(), event.pluginId());
            }
        }));
    }

    public PluginScheduleAuthorization scheduleAuthorization() { return scheduleAuthorization; }
    private String mutate(java.util.function.BooleanSupplier cancelled, Callable<String> action) throws Exception {
        synchronized (callbackMutations) {
            if (cancelled.getAsBoolean()) throw new CancellationException("插件回调已取消或撤权");
            return action.call();
        }
    }

    public void bind(WorkspaceRuntime workspace) {
        synchronized (callbackMutations) { this.workspace = Objects.requireNonNull(workspace); }
    }
    public void unbind(String workspaceId) {
        synchronized (callbackMutations) {
            WorkspaceRuntime current = workspace;
            if (current != null && current.context().workspaceId().equals(workspaceId)) {
                for (String plugin : jdbc.query("SELECT DISTINCT plugin_id FROM plugin_storage WHERE workspace_id=? AND LEFT(store_key,16)='__host:schedule:'",
                        (row, i) -> row.getString(1), workspaceId)) scheduleAuthorization.disable(workspaceId, plugin);
                workspace = null;
            }
        }
    }
    private WorkspaceRuntime require(HostRequest request) {
        WorkspaceRuntime current = workspace;
        if (current == null || current.isClosed() || !current.context().workspaceId().equals(request.workspaceId()))
            throw new SecurityException("插件回调工作区已失效");
        if (Instant.now().isAfter(request.deadline())) throw new IllegalStateException("插件回调已超时");
        return current;
    }

    private String chat(HostRequest request, java.util.function.BooleanSupplier cancelled,
                        java.util.function.Consumer<String> events) throws Exception {
        WorkspaceRuntime current = require(request);
        JsonNode body = json.readTree(request.payloadJson());
        String prompt = body.path("prompt").asText();
        if (prompt.isBlank()) throw new IllegalArgumentException("对话内容不能为空");
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder reply = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        var commands = new RunRequestFactory(current.context());
        ConversationHandle handle = conversations.start(commands.text(prompt,
                "plugin:" + request.pluginId() + ":" + body.path("conversationId").asText("default"),
                "plugin", InvocationSource.plugin(request.pluginId()), PermissionSet.NONE, null),
                com.javaclaw.agent.ToolCallOrigin.UNKNOWN, new ConversationCallbacks() {
                    @Override public void onEvent(ConversationEvent event) {
                        if (event instanceof ConversationEvent.Reply chunk) {
                            synchronized (reply) { reply.append(chunk.chunk()); }
                            events.accept(json.createObjectNode().put("text", chunk.chunk()).toString());
                        }
                    }
                    @Override public void onTerminal(ConversationOutcome outcome) {
                        if (outcome instanceof ConversationOutcome.Failed failed) error.set(failed.error());
                        if (outcome instanceof ConversationOutcome.Cancelled) error.set(new CancellationException());
                        done.countDown();
                    }
                });
        try {
            while (!done.await(100, TimeUnit.MILLISECONDS)) {
                require(request);
                if (cancelled.getAsBoolean()) throw new CancellationException();
            }
            if (error.get() != null) throw new IllegalStateException("宿主对话未完成", error.get());
            synchronized (reply) { return json.createObjectNode().put("text", reply.toString()).toString(); }
        } finally {
            if (done.getCount() != 0) handle.cancel(CancellationReason.RUNTIME_REBUILD);
        }
    }

    private String memory(HostRequest request, java.util.function.BooleanSupplier cancelled,
                          java.util.function.Consumer<String> events) throws Exception {
        var service = require(request).memoryService();
        var body = json.readTree(request.payloadJson());
        var scopes = service.scopes().stream().filter(scope -> scope.workspaceId().equals(request.workspaceId())
                && scope.kind() == com.javaclaw.memory.MemoryGraphScope.Kind.THREAD).toList();
        if (request.operation().equals("list")) return json.writeValueAsString(scopes.stream()
                .map(scope -> Map.of("threadId", scope.threadId())).toList());
        var selected = scopes.stream().filter(scope -> scope.threadId().equals(body.path("threadId").asText()))
                .findFirst().orElseThrow(() -> new SecurityException("记忆范围不属于当前工作区"));
        var result = json.createArrayNode();
        for (var episode : service.inScope(selected).episodes()) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            if (episode.userInput != null) result.addObject().put("role", "user").put("text", episode.userInput);
            if (episode.assistantReply != null) result.addObject().put("role", "assistant").put("text", episode.assistantReply);
        }
        return result.toString();
    }

    private String storage(HostRequest request, java.util.function.BooleanSupplier cancelled,
                           java.util.function.Consumer<String> events) throws Exception {
        require(request);
        var body = json.readTree(request.payloadJson());
        String key = body.path("key").asText();
        if (!request.operation().equals("keys") && (key.isBlank() || key.length() > 512 || key.startsWith("__host:")))
            throw new IllegalArgumentException("存储键无效");
        return switch (request.operation()) {
            case "get" -> json.createObjectNode().put("value", get(request, key)).toString();
            case "put" -> { put(request, key, body.path("value").asText()); yield "{}"; }
            case "remove" -> { remove(request, key); yield "{}"; }
            case "keys" -> json.writeValueAsString(jdbc.query("SELECT store_key FROM plugin_storage WHERE workspace_id=? AND plugin_id=? AND LEFT(store_key,7)<>'__host:' ORDER BY store_key",
                    (row, i) -> row.getString(1), request.workspaceId(), request.pluginId()));
            default -> throw new SecurityException("不支持的存储操作");
        };
    }

    private String schedule(HostRequest request, java.util.function.BooleanSupplier cancelled,
                            java.util.function.Consumer<String> events) throws Exception {
        var schedules = require(request).schedules();
        var body = json.readTree(request.payloadJson());
        if (request.operation().equals("list")) return json.writeValueAsString(jdbc.query(
                "SELECT store_value FROM plugin_storage WHERE workspace_id=? AND plugin_id=? AND LEFT(store_key,16)='__host:schedule:'",
                (row, i) -> row.getString(1), request.workspaceId(), request.pluginId()));
        if (request.operation().equals("cancel")) {
            String id = body.path("taskId").asText();
            String key = "__host:schedule:" + id;
            if (!id.equals(get(request, key))) throw new SecurityException("任务不属于当前插件");
            schedules.delete(id); remove(request, key); return "{}";
        }
        String name = body.path("name").asText("插件任务");
        var draft = schedules.createDraft(name);
        var command = new com.javaclaw.application.schedule.ScheduleApplicationService.SaveCommand(
                draft.id(), name, "插件 " + request.pluginId(), body.path("triggerType").asText("interval"),
                Math.max(1, body.path("intervalMinutes").asInt(1)), "minute", body.path("dailyTime").asText(),
                body.path("cron").asText(), "", body.path("prompt").asText(), true, draft.version(),
                false, "none", false, true);
        put(request, "__host:schedule:" + draft.id(), draft.id());
        try { schedules.save(command); }
        catch (RuntimeException failure) { remove(request, "__host:schedule:" + draft.id()); throw failure; }
        return json.createObjectNode().put("taskId", draft.id()).toString();
    }
    private String get(HostRequest request, String key) {
        var values = jdbc.query("SELECT store_value FROM plugin_storage WHERE workspace_id=? AND plugin_id=? AND store_key=?",
                (row, i) -> row.getString(1), request.workspaceId(), request.pluginId(), key);
        return values.isEmpty() ? "" : Objects.toString(values.getFirst(), "");
    }
    private void put(HostRequest request, String key, String value) {
        jdbc.update("MERGE INTO plugin_storage(workspace_id,plugin_id,store_key,store_value,updated_at) KEY(workspace_id,plugin_id,store_key) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
                request.workspaceId(), request.pluginId(), key, value);
    }
    private void remove(HostRequest request, String key) {
        jdbc.update("DELETE FROM plugin_storage WHERE workspace_id=? AND plugin_id=? AND store_key=?", request.workspaceId(), request.pluginId(), key);
    }
    @Override public void close() {
        workspace = null; conversations.close();
        RuntimeException errors = null;
        for (AutoCloseable registration : registrations) try { registration.close(); }
        catch (Exception failure) {
            if (errors == null) errors = new IllegalStateException("关闭插件宿主服务失败", failure);
            else errors.addSuppressed(failure);
        }
        if (errors != null) throw errors;
    }
}
