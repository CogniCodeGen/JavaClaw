package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将远程声明转换为宿主拥有的能力；整个 Run 共享不可变实例租约。 */
public final class RemotePluginRuntime implements ExternalPluginRuntime {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RemotePluginRuntime.class);
    private final PluginManagementService plugins;
    private final ObjectMapper json;
    private final com.javaclaw.application.inference.InferenceCatalogPort models;

    public RemotePluginRuntime(PluginManagementService plugins, ObjectMapper json) {
        this(plugins, json, null);
    }
    public RemotePluginRuntime(PluginManagementService plugins, ObjectMapper json,
                               com.javaclaw.application.inference.InferenceCatalogPort models) {
        this.plugins = Objects.requireNonNull(plugins);
        this.json = Objects.requireNonNull(json);
        this.models = models;
    }

    @Override public Snapshot capture(RunRequest request) {
        List<RunLease> leases = new ArrayList<>();
        List<ExternalPluginLock> locks = new ArrayList<>();
        try {
            for (PluginSummary plugin : plugins.list(request.scope().workspaceId()).stream()
                    .filter(PluginSummary::enabled).sorted(Comparator.comparing(PluginSummary::id)).toList()) {
                if (plugin.state() != State.READY && plugin.state() != State.DEGRADED) {
                    if (plugins.details(request.scope().workspaceId(), plugin.id()).contributions().stream()
                            .filter(value -> value.capability().equals("HOOK"))
                            .anyMatch(value -> parse(value.metadataJson()).path("constraint").asBoolean(false)
                                    || parse(value.metadataJson()).path("phase").asText().equals("constraint")))
                        throw new IllegalStateException("已启用的插件约束不可用: " + plugin.id());
                    continue;
                }
                RunLease lease = plugins.acquireRun(request.scope().workspaceId(), plugin.id());
                leases.add(lease);
                var contributions = lease.contributions().stream().map(value ->
                        new ExternalPluginLock.Contribution(value.id(), value.capability(), value.name(),
                                value.description(), value.inputSchemaJson(), value.metadataJson())).toList();
                locks.add(new ExternalPluginLock(lease.workspaceId(), lease.pluginId(), lease.artifactHash(),
                        lease.configurationRevision(), contributions, hostConfiguration(lease)));
            }
            return new Frozen(locks, leases);
        } catch (RuntimeException failure) {
            leases.forEach(RunLease::close);
            throw failure;
        }
    }

    private String hostConfiguration(RunLease lease) {
        if (models == null || lease.contributions().stream().noneMatch(value -> value.capability().equals("MODEL")))
            return "{}";
        var profiles = models.profiles().stream().filter(value -> value.runtimeId().equals(lease.pluginId())).toList();
        var assetIds = profiles.stream().map(com.javaclaw.inference.api.InferenceModelProfile::assetId)
                .collect(java.util.stream.Collectors.toSet());
        var body = json.createObjectNode();
        body.set("profiles", json.valueToTree(profiles));
        body.set("assets", json.valueToTree(models.assets().stream().filter(value -> assetIds.contains(value.id())).toList()));
        return body.toString();
    }

    @Override public Snapshot restore(List<ExternalPluginLock> locks) {
        List<RunLease> leases = new ArrayList<>();
        try {
            for (ExternalPluginLock lock : locks) leases.add(plugins.acquireRun(lock.workspaceId(),
                    lock.pluginId(), lock.artifactHash(), lock.configurationRevision()));
            return new Frozen(locks, leases);
        } catch (RuntimeException failure) {
            leases.forEach(RunLease::close);
            throw new IllegalStateException("原插件工件或配置不可用，Run 恢复已阻止", failure);
        }
    }

    private final class Frozen implements Snapshot {
        private final List<ExternalPluginLock> locks;
        private final List<RunLease> leases;
        private final AtomicBoolean closed = new AtomicBoolean();
        Frozen(List<ExternalPluginLock> locks, List<RunLease> leases) {
            this.locks = List.copyOf(locks); this.leases = List.copyOf(leases);
        }
        @Override public List<ExternalPluginLock> locks() { return locks; }

        @Override public List<FrameworkTool> tools(ToolContext context) {
            List<FrameworkTool> tools = new ArrayList<>();
            for (Bound bound : contributions("TOOL")) tools.add(new FrameworkTool() {
                private final ToolDescriptor descriptor = new ToolDescriptor(
                        "plugin_" + safe(bound.lock.pluginId()) + "_" + safe(bound.contribution.id()),
                        bound.contribution.description(), parse(bound.contribution.inputSchemaJson()),
                        "plugins", PermissionSet.of("tool.execute"), false);
                @Override public ToolDescriptor descriptor() { return descriptor; }
                @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext execution) throws Exception {
                    execution.cancellation().throwIfCancelled();
                    Duration timeout = Duration.between(java.time.Instant.now(), execution.deadline());
                    if (timeout.isNegative() || timeout.isZero()) throw new IllegalStateException("插件工具已超过期限");
                    PluginInvocation call = plugins.invoke(bound.lease, "TOOL", bound.contribution.id(),
                            "execute", arguments.toString(), timeout, ignored -> { });
                    try (var cancellation = execution.cancellation().onCancel(call::cancel)) {
                        return parse(await(call, timeout));
                    }
                }
            });
            return List.copyOf(tools);
        }

        @Override public String prepare(RunRequest request, ExtensionStateView state) {
            StringBuilder context = new StringBuilder();
            for (String kind : List.of("SKILL", "CONTEXT")) for (Bound bound : contributions(kind)) {
                try { append(context, call(bound, "retrieve", json.valueToTree(request))); }
                catch (RuntimeException failure) {
                    log.warn("可选插件上下文失败 plugin={} contribution={}", bound.lock.pluginId(), bound.contribution.id());
                }
            }
            for (Bound hook : hooks("before_turn")) {
                try { append(context, hook(hook, "before_turn", json.valueToTree(request), request).path("output")); }
                catch (RuntimeException failure) {
                    if (constraint(hook)) throw failure;
                    log.warn("可选插件回合钩子失败 plugin={}", hook.lock.pluginId());
                }
            }
            return context.toString();
        }

        @Override public RunConstraints restrict(RunConstraints current, RunRequest request) {
            RunConstraints result = current;
            for (Bound hook : hooks("constraint")) {
                var input = json.createObjectNode();
                input.set("allowedPermissions", json.valueToTree(result.permissions().values().stream().map(Permission::value).toList()));
                input.put("remainderBudget", result.budget().maxToolCalls());
                input.set("budget", json.valueToTree(result.budget()));
                input.set("request", json.valueToTree(request));
                input.set("input", input.deepCopy());
                JsonNode response = call(hook, "constraint", input);
                denyIfRequested(response);
                PermissionSet permissions = result.permissions();
                if (response.path("allowedPermissions").isArray()) {
                    List<String> allowed = new ArrayList<>();
                    response.path("allowedPermissions").forEach(value -> allowed.add(value.asText()));
                    permissions = permissions.intersect(PermissionSet.of(allowed.toArray(String[]::new)));
                }
                RunBudget budget = result.budget();
                JsonNode proposed = response.path("output");
                if (proposed.path("budget").isObject()) {
                    try { budget = budget.restrictWith(json.treeToValue(proposed.path("budget"), RunBudget.class)); }
                    catch (Exception invalid) { throw new IllegalStateException("插件返回无效预算约束", invalid); }
                }
                if (response.has("remainderBudget")) {
                    long remaining = response.path("remainderBudget").asLong(-1);
                    if (remaining < 0 || remaining > result.budget().maxToolCalls()) throw new SecurityException("插件扩大工具调用预算");
                    budget = new RunBudget(budget.timeout(), budget.maxInputTokens(), budget.maxOutputTokens(),
                            (int) Math.min(remaining, budget.maxToolCalls()), budget.maxCost());
                }
                result = new RunConstraints(permissions, budget);
            }
            return result;
        }

        @Override public JsonNode observeModel(JsonNode output, RunRequest request, RunId runId) {
            for (Bound hook : hooks("after_model")) {
                try { denyIfRequested(hook(hook, "after_model", output, request)); }
                catch (RuntimeException failure) {
                    if (constraint(hook)) throw failure;
                    log.warn("可选插件模型观察失败 plugin={} contribution={}", hook.lock.pluginId(), hook.contribution.id());
                }
            }
            return output;
        }

        @Override public JsonNode toolResult(JsonNode view, ToolDescriptor tool,
                                              ToolExecutionContext context, RunRequest request) {
            JsonNode result = view;
            for (Bound hook : hooks("tool_result")) {
                try {
                    var input = json.createObjectNode().put("tool", tool.name());
                    input.set("modelView", result);
                    JsonNode response = hook(hook, "tool_result", input, request).path("output");
                    denyIfRequested(response);
                    // 仅修改模型可见视图，原始结果与副作用凭据仍由内核审计保存。
                    if (response.has("modelView")) result = response.get("modelView").deepCopy();
                } catch (RuntimeException failure) {
                    if (constraint(hook)) throw failure;
                    log.warn("可选插件工具结果钩子失败 plugin={} contribution={}", hook.lock.pluginId(), hook.contribution.id());
                }
            }
            return result;
        }

        private List<Bound> contributions(String kind) {
            List<Bound> result = new ArrayList<>();
            for (int i = 0; i < locks.size(); i++) for (var contribution : locks.get(i).contributions()) {
                if (kind.equalsIgnoreCase(contribution.capability())) result.add(new Bound(locks.get(i), leases.get(i), contribution));
            }
            return result;
        }
        private List<Bound> hooks(String phase) {
            return contributions("HOOK").stream().filter(bound -> phase.equals(
                    parse(bound.contribution.metadataJson()).path("phase").asText("before_turn")))
                    .sorted(Comparator.comparingInt((Bound value) -> parse(value.contribution.metadataJson()).path("priority").asInt())
                            .thenComparing(value -> value.lock.pluginId()).thenComparing(value -> value.contribution.id())).toList();
        }
        private boolean constraint(Bound bound) {
            JsonNode metadata = parse(bound.contribution.metadataJson());
            return metadata.path("constraint").asBoolean(false) || "constraint".equals(metadata.path("phase").asText());
        }
        private JsonNode hook(Bound bound, String operation, JsonNode input, RunRequest request) {
            var envelope = json.createObjectNode();
            envelope.set("input", input);
            envelope.set("allowedPermissions", json.valueToTree(request.permissionCeiling().values().stream().map(Permission::value).toList()));
            envelope.put("remainderBudget", request.budget().maxToolCalls());
            return call(bound, operation, envelope);
        }
        private JsonNode call(Bound bound, String operation, JsonNode input) {
            Duration timeout = Duration.ofSeconds(30);
            return parse(await(plugins.invoke(bound.lease, bound.contribution.capability(), bound.contribution.id(),
                    operation, input.toString(), timeout, ignored -> { }), timeout));
        }
        @Override public void close() { if (closed.compareAndSet(false, true)) leases.forEach(RunLease::close); }
    }

    private record Bound(ExternalPluginLock lock, RunLease lease, ExternalPluginLock.Contribution contribution) { }
    private JsonNode parse(String value) {
        try { return json.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (Exception failure) { throw new IllegalArgumentException("插件返回无效 JSON", failure); }
    }
    private static String safe(String value) {
        String prefix = value.replaceAll("[^a-zA-Z0-9_]", "_");
        try {
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return prefix.substring(0, Math.min(16, prefix.length())) + "_" + digest.substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static void append(StringBuilder target, JsonNode value) {
        if (value.isTextual()) target.append(value.asText()).append('\n');
        else if (value.has("text")) target.append(value.path("text").asText()).append('\n');
        else target.append(value).append('\n');
    }
    private static void denyIfRequested(JsonNode response) {
        if (response.has("output")) response = response.path("output");
        if (response.path("denied").asBoolean(false) || (response.has("allowed") && !response.path("allowed").asBoolean()))
            throw new SecurityException("插件约束拒绝本次执行");
    }
    private static String await(PluginInvocation call, Duration timeout) {
        try { return call.completion().get(timeout.toMillis(), TimeUnit.MILLISECONDS).payloadJson(); }
        catch (InterruptedException interrupted) {
            call.cancel(); Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("插件调用失败", failure.getCause());
        } catch (java.util.concurrent.TimeoutException failure) {
            call.cancel(); throw new IllegalStateException("插件调用超时", failure);
        }
    }
}
