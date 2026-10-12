package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.ContributionInfo;
import com.javaclaw.application.plugin.v2.PluginManagementService.RunLease;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.framework.spi.*;
import com.javaclaw.workflow.model.*;
import com.javaclaw.workflow.node.StatePathValidator;
import com.javaclaw.workflow.runtime.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 外部节点只接收图数据；精确工件与配置租约随工作流保存、恢复和释放。 */
public final class RemoteWorkflowExtensionProvider implements WorkflowExtensionPlanProvider {
    private static final String PREFIX = "external.";
    private final WorkflowExtensionPlanProvider builtins;
    private final PluginManagementService plugins;
    private final WorkspaceManager workspaces;
    private final ObjectMapper json;
    private final JsonSchemaValidator schemas = new JsonSchemaValidator();

    public RemoteWorkflowExtensionProvider(WorkflowExtensionPlanProvider builtins,
            PluginManagementService plugins, WorkspaceManager workspaces, ObjectMapper json) {
        this.builtins = builtins; this.plugins = plugins; this.workspaces = workspaces; this.json = json;
    }

    @Override public WorkflowExtensionPlan compile(GraphDefinition definition) {
        WorkflowExtensionPlan base = builtins.compile(definition);
        List<RunLease> leases = new ArrayList<>();
        try {
            Set<String> required = new HashSet<>();
            definition.nodes().forEach(node -> required.add(node.executorType()));
            String workspace = workspaces.getCurrentWorkspaceId();
            for (var plugin : plugins.list(workspace).stream().sorted(Comparator.comparing(
                    PluginManagementService.PluginSummary::id)).toList()) {
                if (!available(plugin) || !plugin.capabilities().contains("WORKFLOW")) continue;
                if (plugins.details(workspace, plugin.id()).contributions().stream()
                        .noneMatch(value -> value.capability().equals("WORKFLOW")
                                && required.contains(type(plugin.id(), value)))) continue;
                leases.add(plugins.acquireRun(workspace, plugin.id()));
            }
            return combine(base, leases);
        } catch (RuntimeException failure) { release(base, leases); throw failure; }
    }

    @Override public Optional<WorkflowExtensionPlan> restore(List<ExtensionLock> locks) {
        List<ExtensionLock> exact = locks == null ? List.of() : List.copyOf(locks);
        var base = builtins.restore(exact.stream().filter(value -> !external(value)).toList());
        if (base.isEmpty()) return Optional.empty();
        List<RunLease> leases = new ArrayList<>();
        try {
            for (var lock : exact.stream().filter(RemoteWorkflowExtensionProvider::external).toList()) {
                if (!lock.workspaceId().equals(workspaces.getCurrentWorkspaceId()))
                    throw new SecurityException("工作流插件快照不属于当前工作区");
                leases.add(plugins.acquireRun(lock.workspaceId(), lock.extensionId().substring(PREFIX.length()),
                        lock.artifactSha256(), lock.generation()));
            }
            return Optional.of(combine(base.get(), leases));
        } catch (RuntimeException failure) { release(base.get(), leases); return Optional.empty(); }
    }

    private static boolean external(ExtensionLock lock) { return lock.extensionId().startsWith(PREFIX); }

    private WorkflowExtensionPlan combine(WorkflowExtensionPlan base, List<RunLease> leases) {
        var executors = new LinkedHashMap<>(base.executors());
        var locks = new ArrayList<>(base.locks());
        for (var lease : leases) {
            locks.add(new ExtensionLock(PREFIX + lease.pluginId(), SemanticVersion.parse("2.0.0"),
                    lease.artifactHash(), lease.configurationRevision(), lease.workspaceId()));
            for (var value : lease.contributions()) {
                if (!value.capability().equals("WORKFLOW") || metadata(value).has("template")) continue;
                String type = type(lease.pluginId(), value);
                if (executors.putIfAbsent(type, executor(type, value, lease, false)) != null)
                    throw new IllegalStateException("工作流节点类型重复: " + type);
            }
        }
        return new WorkflowExtensionPlan(base.generation(), locks, executors, () -> release(base, leases));
    }

    @Override public Optional<NodeExecutor> currentExecutor(String type) {
        var builtin = builtins.currentExecutor(type);
        if (builtin.isPresent()) return builtin;
        String workspace = workspaces.getCurrentWorkspaceId();
        for (var plugin : plugins.list(workspace)) {
            if (!available(plugin) || !plugin.capabilities().contains("WORKFLOW")) continue;
            for (var value : plugins.details(workspace, plugin.id()).contributions()) {
                if (value.capability().equals("WORKFLOW") && !metadata(value).has("template")
                        && type(plugin.id(), value).equals(type)) {
                    return Optional.of(executor(type, value, new PreviewLease(workspace, plugin.id()), true));
                }
            }
        }
        return Optional.empty();
    }

    @Override public List<WorkflowTemplateContribution> currentTemplates() {
        List<WorkflowTemplateContribution> result = new ArrayList<>(builtins.currentTemplates());
        String workspace = workspaces.getCurrentWorkspaceId();
        for (var plugin : plugins.list(workspace)) {
            if (!available(plugin) || !plugin.capabilities().contains("WORKFLOW")) continue;
            for (var value : plugins.details(workspace, plugin.id()).contributions()) {
                if (value.capability().equals("WORKFLOW") && metadata(value).has("template"))
                    result.add(new WorkflowTemplateContribution(type(plugin.id(), value),
                            value.name(), metadata(value).path("template")));
            }
        }
        return List.copyOf(result);
    }

    private NodeExecutor executor(String type, ContributionInfo contribution, RunLease pinned, boolean acquire) {
        return new NodeExecutor() {
            @Override public String type() { return type; }
            @Override public boolean supportsAutomaticRetry() { return false; }
            @Override public List<String> validate(NodeDefinition node) {
                try { return schemas.validate(json.readTree(contribution.inputSchemaJson()), node.config(), "")
                        .stream().map(value -> value.path() + ": " + value.message()).toList(); }
                catch (Exception failure) { return List.of("插件节点 Schema 无效"); }
            }
            @Override public NodeResult execute(NodeExecutionContext context) throws Exception {
                RunLease lease = acquire ? plugins.acquireRun(pinned.workspaceId(), pinned.pluginId()) : pinned;
                try {
                    var payload = json.createObjectNode().put("workflowRunId", context.runId())
                            .put("nodeId", context.node().id()).put("invocationId", context.invocationId());
                    payload.set("configuration", context.node().config());
                    var publicState = context.state().toObjectNode();
                    // 宿主保留字段包含 Agent 归属与执行控制信息，不跨插件边界传递。
                    var reserved = new ArrayList<String>();
                    publicState.fieldNames().forEachRemaining(key -> { if (key.startsWith("_")) reserved.add(key); });
                    publicState.remove(reserved);
                    payload.set("state", publicState);
                    var call = plugins.invoke(lease, "WORKFLOW", contribution.id(), "execute", payload.toString(),
                            Duration.ofMinutes(5), event -> { });
                    JsonNode result;
                    try {
                        while (true) {
                            if (context.cancellation().isCancelled() || Thread.currentThread().isInterrupted())
                                throw new GraphCancelledException();
                            try { result = json.readTree(call.completion().get(100, TimeUnit.MILLISECONDS).payloadJson()); break; }
                            catch (TimeoutException waiting) { /* 定期检查工作流取消。 */ }
                            catch (java.util.concurrent.ExecutionException failure) {
                                if (failure.getCause() instanceof PluginManagementService.PluginException problem
                                        && problem.code().equals("UNKNOWN_OUTCOME"))
                                    throw new NodeOutcomeUnknownException(problem.getMessage(), problem);
                                throw failure;
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new GraphCancelledException();
                            }
                        }
                    } finally { if (!call.completion().isDone()) call.cancel(); }
                    StatePatch.Builder patch = StatePatch.builder();
                    var fields = result.path("setValues").fields();
                    while (fields.hasNext()) {
                        var field = fields.next();
                        if (!publicPath(field.getKey()))
                            throw new IllegalArgumentException("插件节点返回非法状态路径: " + field.getKey());
                        patch.setJson(field.getKey(), field.getValue());
                    }
                    String prompt = result.path("interruptPrompt").asText("");
                    String responseKey = result.path("interruptResponseKey").asText("human.response");
                    if (!prompt.isBlank() && !publicPath(responseKey))
                        throw new IllegalArgumentException("插件节点返回非法中断状态路径");
                    return new NodeResult(patch.build(), result.path("output").asText(""), prompt.isBlank()
                            ? null : new NodeResult.Interrupt(prompt, responseKey));
                } finally { if (acquire) lease.close(); }
            }
        };
    }

    private String type(String plugin, ContributionInfo value) { return "plugin." + plugin + "." + value.id(); }
    private static boolean publicPath(String path) {
        return StatePathValidator.isValid(path) && !path.startsWith("_");
    }
    private static boolean available(PluginManagementService.PluginSummary plugin) {
        return plugin.enabled() && (plugin.state() == PluginManagementService.State.READY
                || plugin.state() == PluginManagementService.State.DEGRADED);
    }
    private JsonNode metadata(ContributionInfo value) {
        try { return json.readTree(value.metadataJson()); }
        catch (Exception failure) { throw new IllegalArgumentException("工作流贡献元数据无效", failure); }
    }
    private static void release(WorkflowExtensionPlan base, List<RunLease> leases) {
        try { base.close(); } finally { leases.forEach(RunLease::close); }
    }

    /** 仅用于编辑期解析，执行时重新取得租约；运行计划始终使用固定实例。 */
    private record PreviewLease(String workspaceId, String pluginId) implements RunLease {
        public String runId() { throw new UnsupportedOperationException(); }
        public String artifactHash() { throw new UnsupportedOperationException(); }
        public long configurationRevision() { throw new UnsupportedOperationException(); }
        public String instanceId() { throw new UnsupportedOperationException(); }
        public String manifestJson() { return "{}"; }
        public Set<String> grantedPermissions() { return Set.of(); }
        public List<ContributionInfo> contributions() { return List.of(); }
        public void close() { }
    }
}
