package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Delegation tools share the AgentEngine and keep each named child in its own durable Thread. */
public final class SubAgentTools implements ToolProviderFactory {
    private final Supplier<AgentClient> agents;
    private final RunStore runs;
    private final java.util.function.BiConsumer<ToolContext, RunHandle> observer;

    public SubAgentTools(Supplier<AgentClient> agents, RunStore runs) {
        this(agents, runs, (context, handle) -> { });
    }

    public SubAgentTools(Supplier<AgentClient> agents, RunStore runs,
                         java.util.function.BiConsumer<ToolContext, RunHandle> observer) {
        this.agents = Objects.requireNonNull(agents);
        this.runs = Objects.requireNonNull(runs);
        this.observer = Objects.requireNonNull(observer);
    }

    @Override public List<FrameworkTool> create(ToolContext parent) {
        return List.of(tool(parent, "spawn", "Start work in a named child Agent using only the supplied task and selected context."),
                tool(parent, "send", "Send follow-up work or requested input to the same named child Agent."),
                tool(parent, "result", "Read a delegated turn's status and final result; optionally wait up to 30 seconds."),
                tool(parent, "interrupt", "Interrupt one delegated turn belonging to this parent thread."));
    }

    private FrameworkTool tool(ToolContext parent, String operation, String description) {
        ToolDescriptor descriptor = new ToolDescriptor("subagent_" + operation, description,
                schema(operation), "subagent", PermissionSet.of("tool.read"), true);
        return new FrameworkTool() {
            @Override public ToolDescriptor descriptor() { return descriptor; }
            @Override public JsonNode execute(JsonNode input, ToolExecutionContext execution) throws Exception {
                execution.cancellation().throwIfCancelled();
                if (!execution.runId().equals(parent.runId())) throw new SecurityException("parent turn mismatch");
                return switch (operation) {
                    case "spawn", "send" -> startOrSend(parent, execution, input, operation.equals("send"));
                    case "result" -> result(parent, execution, input);
                    case "interrupt" -> interrupt(parent, input);
                    default -> throw new IllegalStateException("unknown delegation operation");
                };
            }
        };
    }

    private JsonNode startOrSend(ToolContext parent, ToolExecutionContext execution,
                                JsonNode input, boolean followUp) {
        String key = required(input, "childKey");
        RunScope child = childScope(parent.scope(), key);
        String task = required(input, "task");
        String context = input.path("selectedContext").asText("");
        int depth = parent.request().attributes().getOrDefault("framework.delegationDepth",
                JsonNodeFactory.instance.numberNode(0)).asInt();
        if (depth >= 8) throw new IllegalStateException("maximum child Agent depth reached");
        var waiting = followUp ? agents.get().activeTurn(child).orElse(null) : null;
        RunHandle handle;
        if (waiting != null && waiting.state() == RunState.WAITING_INPUT) {
            requireOwned(parent, waiting.id());
            handle = agents.get().resume(waiting.id(), new ResumeCommand("input",
                    object().put("text", task).put("selectedContext", context)
                            .put("delegationInvocationId", execution.invocationId())));
        } else {
            Map<String, JsonNode> attributes = new LinkedHashMap<>();
            for (String name : List.of("workDir", "framework.allowedToolGroups")) {
                JsonNode value = parent.request().attributes().get(name);
                if (value != null) attributes.put(name, value);
            }
            attributes.put("framework.delegationDepth", JsonNodeFactory.instance.numberNode(depth + 1));
            attributes.put("framework.parentStepId", JsonNodeFactory.instance.textNode(
                    StepId.tool(parent.runId(), execution.invocationId()).value()));
            RunRequest request = RunRequest.builder().agent(parent.request().agent())
                    .profile(RunProfileRef.latest("subagent"))
                    .source(new InvocationSource("subagent", key)).scope(child)
                    .input(InputBlock.text(context.isBlank() ? task : task + "\n\nSelected parent context:\n" + context))
                    .linkage(new RunLinkage(parent.runId(), parent.request().linkage().workflowRunId(), key))
                    .permissionCeiling(parent.permissions()).budget(parent.request().budget())
                    .idempotencyKey("delegate:" + parent.runId() + ":" + execution.invocationId())
                    .attributes(attributes).build();
            handle = agents.get().start(request);
        }
        observer.accept(parent, handle);
        return receipt(child, key, agents.get().get(handle.id()));
    }

    private JsonNode result(ToolContext parent, ToolExecutionContext execution, JsonNode input)
            throws InterruptedException {
        RunId id = new RunId(required(input, "turnId"));
        StoredRun child = requireOwned(parent, id);
        int seconds = Math.max(0, Math.min(30, input.path("waitSeconds").asInt(0)));
        Instant stop = Instant.now().plusSeconds(seconds);
        if (execution.deadline().isBefore(stop)) stop = execution.deadline();
        RunSnapshot snapshot = agents.get().get(id);
        while (!snapshot.state().terminal() && snapshot.state() == RunState.RUNNING
                && Instant.now().isBefore(stop)) {
            execution.cancellation().throwIfCancelled();
            Thread.sleep(Math.min(100, Math.max(1, Duration.between(Instant.now(), stop).toMillis())));
            snapshot = agents.get().get(id);
        }
        ObjectNode result = receipt(child.request().scope(), child.request().source().id(), snapshot);
        if (snapshot.state().terminal()) {
            result.set("output", snapshot.output());
            if (snapshot.error() != null) result.put("error", snapshot.error());
        } else if (snapshot.state() == RunState.WAITING_APPROVAL || snapshot.state() == RunState.WAITING_INPUT) {
            result.put("requiresUserAction", true);
            result.put("message", "The child is waiting for user input or tool approval.");
        }
        return result;
    }

    private JsonNode interrupt(ToolContext parent, JsonNode input) {
        RunId id = new RunId(required(input, "turnId"));
        StoredRun child = requireOwned(parent, id);
        boolean accepted = agents.get().cancel(id, new CancelReason("PARENT_INTERRUPTED", parent.runId().value()));
        return receipt(child.request().scope(), child.request().source().id(), agents.get().get(id))
                .put("accepted", accepted);
    }

    private StoredRun requireOwned(ToolContext parent, RunId id) {
        StoredRun child = runs.find(id).orElseThrow(() -> new IllegalArgumentException("child turn not found"));
        RunId creator = child.request().linkage().parentRunId();
        StoredRun owner = creator == null ? null : runs.find(creator).orElse(null);
        if (owner == null || !owner.request().scope().equals(parent.scope())
                || !child.request().scope().workspaceId().equals(parent.scope().workspaceId())
                || !child.request().scope().userId().equals(parent.scope().userId())) {
            throw new SecurityException("turn does not belong to this parent thread");
        }
        return child;
    }

    static RunScope childScope(RunScope parent, String childKey) {
        String identity = parent.workspaceId() + "\0" + parent.userId() + "\0" + parent.sessionId() + "\0" + childKey;
        return new RunScope(parent.workspaceId(), parent.userId(),
                "child:" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
    }

    private static ObjectNode receipt(RunScope child, String key, RunSnapshot snapshot) {
        return object().put("childKey", key).put("threadId", child.sessionId())
                .put("turnId", snapshot.id().value()).put("state", snapshot.state().name());
    }

    private static ObjectNode schema(String operation) {
        ObjectNode schema = object().put("type", "object").put("additionalProperties", false);
        var properties = schema.putObject("properties");
        var required = schema.putArray("required");
        if (operation.equals("spawn") || operation.equals("send")) {
            properties.putObject("childKey").put("type", "string").put("minLength", 1).put("maxLength", 80);
            properties.putObject("task").put("type", "string").put("minLength", 1);
            properties.putObject("selectedContext").put("type", "string");
            required.add("childKey").add("task");
        } else {
            properties.putObject("turnId").put("type", "string").put("minLength", 1);
            required.add("turnId");
            if (operation.equals("result")) properties.putObject("waitSeconds").put("type", "integer")
                    .put("minimum", 0).put("maximum", 30);
        }
        return schema;
    }

    private static String required(JsonNode input, String key) {
        String value = input.path(key).asText("").strip();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " must not be blank");
        return value;
    }
    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
}
