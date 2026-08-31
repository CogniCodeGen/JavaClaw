package com.javaclaw.agent.tools;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import com.javaclaw.agent.collaboration.CollaborationGateway;
import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.ToolHandler;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolRisk;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** Governed model tools for parent/child Thread collaboration. */
public final class CollaborationTools {
    private static final Duration MAX_MODEL_WAIT = Duration.ofSeconds(60);

    private CollaborationTools() {}

    /** 注册 spawn/steer/wait/cancel/diff/apply/cleanup 协作工具，所有操作经同一治理管线。 */
    public static List<RegisteredTool> create(CollaborationGateway gateway, SandboxPolicy ceiling) {
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(ceiling, "ceiling");
        return List.of(
                spawn(gateway, ceiling),
                steer(gateway, ceiling),
                waitFor(gateway, ceiling),
                cancel(gateway, ceiling),
                diff(gateway, ceiling),
                apply(gateway, ceiling),
                cleanup(gateway, ceiling));
    }

    private static RegisteredTool spawn(CollaborationGateway gateway, SandboxPolicy ceiling) {
        return tool(
                "spawn_subagent",
                "Create a child Thread with an isolated context and start its first Turn.",
                """
                {"type":"object","additionalProperties":false,
                 "required":["task","idempotencyKey"],"properties":{
                  "task":{"type":"string","minLength":1,"maxLength":100000},
                  "writable":{"type":"boolean"},
                  "profileId":{"type":"string","minLength":1,"maxLength":200},
                  "idempotencyKey":{"type":"string","minLength":1,"maxLength":500}}}
                """,
                ToolRisk.HIGH,
                true,
                ceiling,
                context -> {
                    JsonNode args = context.arguments();
                    boolean writable = args.path("writable").asBoolean(false);
                    String profileId = textOr(args, "profileId", "profile_subagent");
                    String task = args.path("task").asText();
                    AgentThread child = gateway.spawn(new CollaborationGateway.SpawnRequest(
                            context.call().thread().id(),
                            task,
                            writable,
                            profileId,
                            args.path("idempotencyKey").asText()));
                    String summary = writable ? "isolated writable Git worktree" : "read-only";
                    return new ToolHandler.Result(
                            new ThreadItem.SubagentCall(child.id(), task, summary),
                            "Spawned child thread " + child.id() + " (" + summary + ").");
                });
    }

    private static RegisteredTool steer(CollaborationGateway gateway, SandboxPolicy ceiling) {
        return tool(
                "steer_subagent",
                "Send additional text to an active child Turn.",
                """
                {"type":"object","additionalProperties":false,
                 "required":["turnId","text"],"properties":{
                  "turnId":{"type":"string","minLength":1,"maxLength":200},
                  "text":{"type":"string","minLength":1,"maxLength":100000}}}
                """,
                ToolRisk.LOW,
                false,
                ceiling,
                context -> {
                    String turnId = context.arguments().path("turnId").asText();
                    boolean accepted = gateway.steer(
                            new TurnId(turnId),
                            new TurnInput.Text(context.arguments().path("text").asText()));
                    return dynamic(
                            "steer_subagent",
                            Map.of("accepted", Boolean.toString(accepted)),
                            accepted ? "Steering input accepted." : "Child Turn is not active.");
                });
    }

    private static RegisteredTool waitFor(CollaborationGateway gateway, SandboxPolicy ceiling) {
        return tool(
                "wait_subagent",
                "Wait briefly for a child Thread to become terminal.",
                """
                {"type":"object","additionalProperties":false,
                 "required":["childThreadId"],"properties":{
                  "childThreadId":{"type":"string","minLength":1,"maxLength":200},
                  "timeoutMillis":{"type":"integer","minimum":0,"maximum":60000}}}
                """,
                ToolRisk.LOW,
                false,
                ceiling,
                context -> {
                    ThreadId child = childId(context.arguments());
                    long millis = context.arguments().path("timeoutMillis").asLong(0);
                    Duration timeout = Duration.ofMillis(millis);
                    if (timeout.compareTo(MAX_MODEL_WAIT) > 0) {
                        timeout = MAX_MODEL_WAIT;
                    }
                    var terminal = gateway.waitForTerminal(child, timeout);
                    LinkedHashMap<String, String> result = new LinkedHashMap<>();
                    result.put("childThreadId", child.value());
                    result.put("terminal", Boolean.toString(terminal.isPresent()));
                    terminal.ifPresent(snapshot -> {
                        result.put(
                                "turnCount", Integer.toString(snapshot.turns().size()));
                        if (!snapshot.turns().isEmpty()) {
                            result.put(
                                    "lastStatus",
                                    snapshot.turns().getLast().status().name());
                        }
                        result.put("summary", boundedSummary(snapshot));
                    });
                    return dynamic(
                            "wait_subagent",
                            result,
                            terminal.map(CollaborationTools::boundedSummary)
                                    .orElse("Child Thread is still active or the wait timed out."));
                });
    }

    private static RegisteredTool cancel(CollaborationGateway gateway, SandboxPolicy ceiling) {
        return tool(
                "cancel_subagent",
                "Interrupt active Turns in a child Thread.",
                """
                {"type":"object","additionalProperties":false,
                 "required":["childThreadId"],"properties":{
                  "childThreadId":{"type":"string","minLength":1,"maxLength":200}}}
                """,
                ToolRisk.MEDIUM,
                false,
                ceiling,
                context -> {
                    boolean interrupted = gateway.cancel(childId(context.arguments()));
                    return dynamic(
                            "cancel_subagent",
                            Map.of("interrupted", Boolean.toString(interrupted)),
                            interrupted ? "Child Turn interrupted." : "No active child Turn found.");
                });
    }

    private static RegisteredTool diff(CollaborationGateway gateway, SandboxPolicy ceiling) {
        return tool(
                "diff_subagent",
                "Generate a bounded binary-safe patch from a managed child worktree.",
                """
                {"type":"object","additionalProperties":false,
                 "required":["childThreadId"],"properties":{
                  "childThreadId":{"type":"string","minLength":1,"maxLength":200}}}
                """,
                ToolRisk.LOW,
                false,
                ceiling,
                context -> patchResult("diff_subagent", gateway.diff(childId(context.arguments()))));
    }

    private static RegisteredTool apply(CollaborationGateway gateway, SandboxPolicy ceiling) {
        return tool(
                "apply_subagent_patch",
                "Apply a child worktree patch to its parent using an isolated temporary index.",
                """
                {"type":"object","additionalProperties":false,
                 "required":["childThreadId","idempotencyKey"],"properties":{
                  "childThreadId":{"type":"string","minLength":1,"maxLength":200},
                  "idempotencyKey":{"type":"string","minLength":1,"maxLength":500}}}
                """,
                ToolRisk.HIGH,
                true,
                ceiling,
                context -> patchResult(
                        "apply_subagent_patch",
                        gateway.apply(
                                context.call().thread().id(),
                                childId(context.arguments()),
                                context.arguments().path("idempotencyKey").asText())));
    }

    private static RegisteredTool cleanup(CollaborationGateway gateway, SandboxPolicy ceiling) {
        return tool(
                "cleanup_subagent",
                "Remove a managed child worktree; discarding unmerged work requires explicit consent.",
                """
                {"type":"object","additionalProperties":false,
                 "required":["childThreadId"],"properties":{
                  "childThreadId":{"type":"string","minLength":1,"maxLength":200},
                  "discardUnmerged":{"type":"boolean"}}}
                """,
                ToolRisk.HIGH,
                true,
                ceiling,
                context -> {
                    boolean cleaned = gateway.cleanup(
                            childId(context.arguments()),
                            context.arguments().path("discardUnmerged").asBoolean(false));
                    return dynamic(
                            "cleanup_subagent",
                            Map.of("cleaned", Boolean.toString(cleaned)),
                            cleaned ? "Managed worktree cleaned." : "Cleanup is still required.");
                });
    }

    private static RegisteredTool tool(
            String name,
            String description,
            String schema,
            ToolRisk risk,
            boolean approval,
            SandboxPolicy ceiling,
            ToolHandler handler) {
        return new RegisteredTool(
                new ToolDescriptor(name, description, schema), ToolOrigin.BUILTIN, risk, approval, ceiling, handler);
    }

    private static ToolHandler.Result patchResult(String tool, CollaborationGateway.PatchResult value) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("status", value.status());
        if (value.patchAttachmentSha256() != null) {
            result.put("patchAttachmentSha256", value.patchAttachmentSha256());
        }
        if (!value.conflicts().isEmpty()) {
            result.put("conflicts", String.join("\n", value.conflicts()));
        }
        result.put("message", value.message());
        return dynamic(tool, result, value.status() + ": " + value.message());
    }

    private static ToolHandler.Result dynamic(String tool, Map<String, String> result, String modelContent) {
        return new ToolHandler.Result(new ThreadItem.DynamicToolCall(tool, result), modelContent);
    }

    private static ThreadId childId(JsonNode arguments) {
        return new ThreadId(arguments.path("childThreadId").asText());
    }

    private static String textOr(JsonNode node, String name, String fallback) {
        String value = node.path(name).asText("").strip();
        return value.isEmpty() ? fallback : value;
    }

    private static String boundedSummary(ThreadSnapshot snapshot) {
        ArrayList<String> messages = new ArrayList<>();
        snapshot.items().stream()
                .map(value -> value.item())
                .filter(Objects::nonNull)
                .forEach(item -> {
                    if (item instanceof ThreadItem.AgentMessage message) {
                        messages.add(message.text());
                    } else if (item instanceof ThreadItem.ErrorItem error) {
                        messages.add(error.code() + ": " + error.message());
                    }
                });
        String value = messages.isEmpty() ? "Child Thread completed without a text result." : messages.getLast();
        return value.length() <= 8_000 ? value : value.substring(0, 8_000);
    }
}
