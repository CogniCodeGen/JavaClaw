package com.javaclaw.server.extension;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.agent.tool.ToolHooks;

/** Adapts process-isolated Plugin 4.0 hooks to the governed tool runtime. */
public final class PluginToolHooks {
    private final PluginCatalog catalog;
    private final PluginProcessRuntime processes;
    private final ObjectMapper json;

    /** 绑定插件目录和受监督调用端口，将声明 Hook 适配到统一工具治理链。 */
    public PluginToolHooks(PluginCatalog catalog, PluginProcessRuntime processes, ObjectMapper json) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 返回当前声明的前置安全 Hook；执行为同步有界调用，失败关闭。 */
    public List<ToolHooks.PreToolHook> preHooks() {
        return List.of(invocation -> {
            ToolHooks.Invocation current = invocation;
            for (Contribution contribution : contributions(PluginProcessKind.PRE_TOOL_HOOK)) {
                ToolHooks.PreToolDecision decision = invokePre(contribution, current);
                if (!decision.allowed()) {
                    return decision;
                }
                current = new ToolHooks.Invocation(
                        current.call(), current.origin(), current.risk(), decision.arguments(), decision.policy());
            }
            return ToolHooks.PreToolDecision.allow(current);
        });
    }

    /** 返回当前声明的后置审计 Hook；由治理链异步调度，失败不改变工具结果。 */
    public List<ToolHooks.PostToolHook> postHooks() {
        return List.of((invocation, result) -> {
            for (Contribution contribution : contributions(PluginProcessKind.POST_TOOL_HOOK)) {
                invokePost(contribution, invocation, result);
            }
        });
    }

    private ToolHooks.PreToolDecision invokePre(Contribution contribution, ToolHooks.Invocation invocation)
            throws Exception {
        JsonNode response = processes.invoke(
                contribution.pluginId(),
                contribution.processId(),
                request("hook/preTool", invocation, null),
                context(invocation));
        JsonNode result = result(response);
        if (!result.path("allowed").isBoolean()) {
            throw new IllegalStateException("pre-tool hook result must contain boolean allowed");
        }
        JsonNode arguments = result.has("arguments") ? result.get("arguments") : invocation.arguments();
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalStateException("pre-tool hook arguments must be an object");
        }
        boolean allowed = result.path("allowed").booleanValue();
        String reason = result.path("reason").isTextual()
                ? result.path("reason").textValue()
                : "plugin policy denied execution";
        return new ToolHooks.PreToolDecision(allowed, reason, arguments, invocation.policy());
    }

    private void invokePost(
            Contribution contribution,
            ToolHooks.Invocation invocation,
            com.javaclaw.core.api.ToolExecutionResult result)
            throws Exception {
        result(processes.invoke(
                contribution.pluginId(),
                contribution.processId(),
                request("hook/postTool", invocation, result),
                context(invocation)));
    }

    private ObjectNode request(
            String method, ToolHooks.Invocation invocation, com.javaclaw.core.api.ToolExecutionResult result) {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", "hook_" + UUID.randomUUID().toString().replace("-", ""));
        request.put("method", method);
        ObjectNode params = request.putObject("params");
        params.put("threadId", invocation.call().thread().id().value());
        params.put("turnId", invocation.call().turn().id().value());
        params.put("toolCallId", invocation.call().call().id());
        params.put("tool", invocation.call().call().name());
        params.put("origin", invocation.origin().name());
        params.put("risk", invocation.risk().name());
        params.set("arguments", invocation.arguments());
        ObjectNode policy = params.putObject("sandboxPolicy");
        policy.put("mode", invocation.policy().mode().name());
        policy.put("networkMode", invocation.policy().network().mode().name());
        if (result != null) {
            ObjectNode output = params.putObject("result");
            output.put("itemKind", result.item().kind());
            output.put("modelContent", result.modelContent());
        }
        return request;
    }

    private PluginInvocationContext context(ToolHooks.Invocation invocation) {
        return new PluginInvocationContext(
                invocation.call().thread().workingDirectory(),
                invocation.policy().protectedRoots(),
                invocation.policy(),
                invocation.policy().filteredEnvironment(System.getenv()));
    }

    private static JsonNode result(JsonNode response) {
        if (response.has("error")) {
            throw new IllegalStateException("plugin hook returned JSON-RPC error: "
                    + response.path("error").path("message").asText("unknown error"));
        }
        JsonNode result = response.get("result");
        if (result == null || !result.isObject()) {
            throw new IllegalStateException("plugin hook result must be an object");
        }
        return result;
    }

    private List<Contribution> contributions(PluginProcessKind kind) {
        return catalog.list().stream()
                .flatMap(plugin -> plugin.processes().stream()
                        .filter(process -> process.declaration().kind() == kind)
                        .map(process -> new Contribution(
                                plugin.manifest().id(), process.declaration().id())))
                .sorted(Comparator.comparing(Contribution::pluginId).thenComparing(Contribution::processId))
                .toList();
    }

    private record Contribution(String pluginId, String processId) {}
}
