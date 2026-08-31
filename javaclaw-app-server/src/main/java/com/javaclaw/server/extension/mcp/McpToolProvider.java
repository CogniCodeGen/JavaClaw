package com.javaclaw.server.extension.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.ToolAvailability;
import com.javaclaw.agent.tool.ToolHandler;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolProvider;
import com.javaclaw.agent.tool.ToolRisk;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** Dynamic MCP catalog. Non-tool MCP capabilities are exposed only as explicit governed tools. */
public final class McpToolProvider implements ToolProvider {
    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private final McpClientRegistry registry;
    private final ObjectMapper json;

    /** 绑定动态 MCP 注册表和 JSON 编码器，将远程能力显式工具化，不自动注入 Context。 */
    public McpToolProvider(McpClientRegistry registry, ObjectMapper json) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public String id() {
        return "mcp";
    }

    @Override
    public List<RegisteredTool> tools(TurnExecutionContext context) {
        try (McpClientRegistry.Snapshot clients = registry.snapshot(context)) {
            ArrayList<RegisteredTool> tools = new ArrayList<>();
            for (McpClientRegistry.Available available : clients.available()) {
                addServerTools(tools, available, context, false);
            }
            return List.copyOf(tools);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public Snapshot snapshot(TurnExecutionContext context) {
        McpClientRegistry.Snapshot clients = registry.snapshot(context);
        ArrayList<RegisteredTool> tools = new ArrayList<>();
        ArrayList<Failure> failures = new ArrayList<>();
        clients.failures().forEach(failure -> failures.add(new Failure(failure.serverId(), failure.message())));
        for (McpClientRegistry.Available available : clients.available()) {
            try {
                addServerTools(tools, available, context, true);
            } catch (Exception failure) {
                String message =
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                failures.add(
                        new Failure(safeProviderId(available.configuration().id()), message));
            }
        }
        return new Snapshot(List.copyOf(tools), List.copyOf(failures), clients);
    }

    private void addServerTools(
            List<RegisteredTool> target,
            McpClientRegistry.Available available,
            TurnExecutionContext turn,
            boolean reuseSnapshotClient)
            throws JsonProcessingException {
        McpConfiguration configuration = available.configuration();
        Map<String, String> names = McpToolNames.allocate(
                configuration.id(),
                available.tools().stream().map(McpRemoteTool::name).toList());
        for (McpRemoteTool remote : available.tools()) {
            String publicName = names.get(remote.name());
            ToolRisk risk = remote.annotations() != null
                            && remote.annotations().path("readOnlyHint").asBoolean(false)
                    ? ToolRisk.LOW
                    : ToolRisk.HIGH;
            target.add(registered(
                    configuration,
                    new ToolDescriptor(
                            publicName,
                            remote.description().isBlank() ? remote.name() : remote.description(),
                            json.writeValueAsString(remote.inputSchema())),
                    risk,
                    (client, invocation, arguments) -> client.callTool(remote, arguments, invocation),
                    remote.name(),
                    turn));
        }
        McpClient readClient = reuseSnapshotClient ? available.client() : null;
        target.add(synthetic(
                available,
                readClient,
                "resources_list",
                EMPTY_SCHEMA,
                ToolRisk.LOW,
                (client, invocation, arguments) -> client.listResources(),
                turn));
        target.add(synthetic(
                available,
                readClient,
                "resources_read",
                """
                {"type":"object","properties":{"uri":{"type":"string"}},
                 "required":["uri"],"additionalProperties":false}
                """,
                ToolRisk.LOW,
                (client, invocation, arguments) -> client.readResource(arguments, invocation),
                turn));
        target.add(synthetic(
                available,
                readClient,
                "resource_templates_list",
                EMPTY_SCHEMA,
                ToolRisk.LOW,
                (client, invocation, arguments) -> client.listResourceTemplates(),
                turn));
        target.add(synthetic(
                available,
                readClient,
                "prompts_list",
                EMPTY_SCHEMA,
                ToolRisk.LOW,
                (client, invocation, arguments) -> client.listPrompts(),
                turn));
        target.add(synthetic(
                available,
                readClient,
                "prompt_get",
                """
                {"type":"object","properties":{"name":{"type":"string"},
                 "arguments":{"type":"object"}},"required":["name"],
                 "additionalProperties":false}
                """,
                ToolRisk.LOW,
                (client, invocation, arguments) -> client.getPrompt(arguments, invocation),
                turn));
        target.add(synthetic(
                available,
                readClient,
                "completion_complete",
                """
                {"type":"object","properties":{"ref":{"type":"object"},
                 "argument":{"type":"object"},"context":{"type":"object"}},
                 "required":["ref","argument"],"additionalProperties":false}
                """,
                ToolRisk.LOW,
                (client, invocation, arguments) -> client.complete(arguments, invocation),
                turn));
    }

    private RegisteredTool synthetic(
            McpClientRegistry.Available available,
            McpClient scopedClient,
            String operation,
            String schema,
            ToolRisk risk,
            RemoteCall call,
            TurnExecutionContext turn) {
        McpConfiguration configuration = available.configuration();
        String name = McpToolNames.synthetic(configuration.id(), operation);
        return registered(
                configuration,
                new ToolDescriptor(name, "MCP " + configuration.name() + " " + operation.replace('_', ' '), schema),
                risk,
                call,
                operation,
                turn,
                scopedClient);
    }

    private RegisteredTool registered(
            McpConfiguration configuration,
            ToolDescriptor descriptor,
            ToolRisk risk,
            RemoteCall call,
            String auditName,
            TurnExecutionContext turn) {
        return registered(configuration, descriptor, risk, call, auditName, turn, null);
    }

    private RegisteredTool registered(
            McpConfiguration configuration,
            ToolDescriptor descriptor,
            ToolRisk risk,
            RemoteCall call,
            String auditName,
            TurnExecutionContext turn,
            McpClient scopedClient) {
        ToolAvailability availability = new ToolAvailability() {
            @Override
            public void verify() {
                registry.verifyEnabled(configuration.id(), configuration.revision());
            }

            @Override
            public java.util.Optional<Identity> identity() {
                return java.util.Optional.of(new Identity(configuration.id(), configuration.revision()));
            }
        };
        return new RegisteredTool(
                descriptor,
                ToolOrigin.MCP,
                risk,
                true,
                ceiling(configuration, turn),
                availability,
                handler(configuration, auditName, call, scopedClient));
    }

    private ToolHandler handler(
            McpConfiguration configuration, String auditName, RemoteCall call, McpClient scopedClient) {
        return context -> {
            McpClient client = scopedClient;
            boolean close = false;
            if (client == null) {
                client = registry.openInvocation(
                        configuration,
                        new TurnExecutionContext(
                                context.call().thread(),
                                context.call().turn(),
                                List.of(),
                                new java.util.concurrent.atomic.AtomicBoolean(),
                                com.javaclaw.core.api.TurnSteering.NONE,
                                context.call().scope()),
                        context.sandboxPolicy());
                close = true;
            }
            try {
                McpInvocation invocation = new McpInvocation(context.call(), context.events());
                JsonNode result = call.invoke(client, invocation, context.arguments());
                String canonical = json.writeValueAsString(result);
                boolean error = result.path("isError").asBoolean(false);
                Map<String, String> itemResult = Map.of(
                        "status",
                        error ? "error" : "completed",
                        "resultType",
                        result.path("resultType").asText("complete"),
                        "content",
                        canonical);
                return new ToolHandler.Result(
                        new ThreadItem.McpToolCall(configuration.id(), auditName, itemResult),
                        modelContent(result, canonical));
            } finally {
                if (close) {
                    client.close();
                }
            }
        };
    }

    private static SandboxPolicy ceiling(McpConfiguration configuration, TurnExecutionContext turn) {
        SandboxPolicy base = turn.turn().config().sandboxPolicy();
        NetworkPolicy network = configuration.transport() == McpConfiguration.Transport.HTTP
                ? new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, configuration.networkAllowlist())
                : NetworkPolicy.disabled();
        SandboxMode mode = base.mode() == SandboxMode.HOST_FULL_ACCESS ? SandboxMode.WORKSPACE_WRITE : base.mode();
        java.util.Set<java.nio.file.Path> reads = base.mode() == SandboxMode.HOST_FULL_ACCESS
                ? java.util.Set.of(turn.thread().workingDirectory())
                : base.readableRoots();
        java.util.Set<java.nio.file.Path> writes = mode == SandboxMode.READ_ONLY
                ? java.util.Set.of()
                : base.mode() == SandboxMode.HOST_FULL_ACCESS
                        ? java.util.Set.of(turn.thread().workingDirectory())
                        : base.writableRoots();
        return new SandboxPolicy(
                mode,
                reads,
                writes,
                base.protectedRoots(),
                network,
                base.inheritedEnvironment(),
                minimum(base.timeout(), configuration.timeout()),
                Math.min(base.outputLimitBytes(), configuration.outputLimitBytes()));
    }

    private String modelContent(JsonNode result, String canonical) {
        StringBuilder text = new StringBuilder();
        result.path("content").forEach(block -> {
            if (block.path("type").asText().equals("text")) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(block.path("text").asText());
            }
        });
        return text.isEmpty() ? canonical : text.toString();
    }

    private static Duration minimum(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static String safeProviderId(String value) {
        String result = value.replaceAll("[^A-Za-z0-9._-]", "_");
        result = Character.isLetter(result.charAt(0)) ? result : "mcp_" + result;
        return result.length() <= 160 ? result : result.substring(0, 160);
    }

    @FunctionalInterface
    private interface RemoteCall {
        JsonNode invoke(McpClient client, McpInvocation invocation, JsonNode arguments) throws Exception;
    }
}
