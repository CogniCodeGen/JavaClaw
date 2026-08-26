package com.javaclaw.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.application.agent.FrameworkToolApprovalCoordinator;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunLinkage;
import com.javaclaw.framework.api.RunOutcome;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.NodeExecutor;
import com.javaclaw.workflow.runtime.NodeResult;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Workflow AGENT_RUN node. It can only start and observe a child run through AgentClient. */
public final class AgentNodeExecutor implements NodeExecutor {
    private final AgentClient agents;
    private final WorkspaceContext workspace;

    /** Validation-only constructor used by graph schema tests. */
    public AgentNodeExecutor() {
        this(null, null);
    }

    public AgentNodeExecutor(AgentClient agents, WorkspaceContext workspace) {
        this.agents = agents;
        this.workspace = workspace;
    }

    @Override
    public String type() {
        return "agent";
    }

    @Override
    public List<String> validate(com.javaclaw.workflow.model.NodeDefinition node) {
        List<String> errors = new ArrayList<>();
        if (node.config().path("prompt").asText().isBlank()
                && node.config().path("expertRef").asText().isBlank()
                && node.config().path("agentDefinitionRef").asText().isBlank()) {
            errors.add("AGENT 必须配置 prompt 或 agentDefinitionRef");
        }
        int maxIters = node.config().path("maxIters").asInt(8);
        if (maxIters < 1 || maxIters > 100) errors.add("maxIters 必须在 1..100 之间");
        if (node.config().has("toolGroups") && !node.config().path("toolGroups").isArray()) {
            errors.add("AGENT toolGroups 必须是数组");
        }
        WorkflowToolGroupPolicy.validate(node.config().path("toolGroups"), errors);
        StatePathValidator.validate(node.config().path("outputKey").asText("agent.output"),
                "AGENT outputKey", errors);
        return List.copyOf(errors);
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) throws Exception {
        if (agents == null || workspace == null) {
            throw new IllegalStateException("AGENT_RUN executor is not bound to AgentClient");
        }
        JsonNode config = context.node().config();
        String input = TemplateRenderer.render(
                config.path("inputTemplate").asText("{{input}}"), context.state());
        String instruction = config.path("prompt").asText("").strip();
        if (!instruction.isBlank()) input = instruction + "\n\n" + input;

        String agentId = config.path("agentDefinitionRef").asText("").strip();
        if (agentId.isBlank()) {
            // Old expertRef values are imported into Agent Studio separately. Until published,
            // preserve execution with the built-in definition and include the role in the input.
            String legacyRole = config.path("expertRef").asText("").strip();
            agentId = "system.default";
            if (!legacyRole.isBlank()) input = "Requested role: " + legacyRole + "\n\n" + input;
        }
        String profile = config.path("runProfileRef").asText("chat").strip();
        int maxToolCalls = config.path("maxIters").asInt(8) * 4;
        long timeoutSeconds = Math.max(30, config.path("timeoutSeconds").asLong(600));
        RunBudget budget = new RunBudget(Duration.ofSeconds(timeoutSeconds),
                250_000, 80_000, maxToolCalls, new BigDecimal("100"));

        Map<String, JsonNode> attributes = new LinkedHashMap<>();
        attributes.put("workflowNodeId", JsonNodeFactory.instance.textNode(context.node().id()));
        attributes.put("modelProfile", JsonNodeFactory.instance.textNode(
                config.path("modelProfile").asText("default")));
        var allowedToolGroups = JsonNodeFactory.instance.arrayNode();
        WorkflowToolGroupPolicy.read(config.path("toolGroups"))
                .forEach(allowedToolGroups::add);
        attributes.put(com.javaclaw.framework.api.ToolGroupAccess.ATTRIBUTE,
                allowedToolGroups);
        JsonNode workDir = context.state().get("workDir");
        if (!workDir.isMissingNode() && !workDir.isNull()) attributes.put("workDir", workDir);

        RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest(agentId))
                .profile(RunProfileRef.latest(profile))
                .source(InvocationSource.workflow(context.runId()))
                .scope(new RunScope(workspace.workspaceId(), "workflow", context.threadId()))
                .input(InputBlock.text(input))
                .linkage(new RunLinkage(null, context.runId(), context.runId()))
                .permissionCeiling(permissions(config))
                .budget(budget)
                .attributes(attributes)
                .build();

        var handle = agents.start(request);
        ConversationCallbacks callbacks = context.callbacks();
        ToolCallOrigin approvalOrigin = ToolCallOrigin.managedTask(
                context.runId(), workDir.isTextual() ? workDir.asText() : null);
        Disposable events = handle.events(0).subscribe(event -> forward(
                event, callbacks, agents, handle, approvalOrigin));
        try (AutoCloseable ignored = context.cancellation().onCancel(() -> agents.cancel(
                handle.id(), new CancelReason("WORKFLOW_CANCELLED", context.runId())))) {
            RunOutcome outcome = await(handle.completion().toCompletableFuture(), context,
                    timeoutSeconds, handle.id());
            if (!outcome.successful()) {
                throw new IllegalStateException(outcome.error() == null
                        ? "Agent child run ended in " + outcome.state() : outcome.error());
            }
            JsonNode output = outcome.output();
            String text = output == null ? "" : output.path("text").asText("");
            if (text.isBlank() && output != null) text = output.path("value").asText("");
            text = com.javaclaw.util.ChineseOutputGuard.enforceUserVisibleReply(text);
            String outputKey = config.path("outputKey").asText("agent.output");
            return NodeResult.output(StatePatch.builder().set(outputKey, text).build(), text);
        } finally {
            events.dispose();
        }
    }

    private RunOutcome await(
            java.util.concurrent.CompletableFuture<RunOutcome> completion,
            NodeExecutionContext context,
            long timeoutSeconds,
            com.javaclaw.framework.api.RunId runId) throws Exception {
        long remaining = timeoutSeconds * 10;
        while (remaining-- > 0) {
            context.cancellation().throwIfCancelled();
            try {
                return completion.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Observe workflow cancellation between waits.
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                if (cause instanceof Exception checked) throw checked;
                throw new IllegalStateException(cause);
            }
        }
        agents.cancel(runId, new CancelReason("WORKFLOW_NODE_TIMEOUT", context.node().id()));
        throw new TimeoutException("Agent workflow node timed out");
    }

    private static PermissionSet permissions(JsonNode config) {
        if (config.path("allowMutatingTools").asBoolean(false)) {
            return PermissionSet.of("tool.read", "tool.execute");
        }
        return PermissionSet.of("tool.read");
    }

    private static void forward(
            RunEventEnvelope event,
            ConversationCallbacks callbacks,
            AgentClient agents,
            com.javaclaw.framework.api.RunHandle handle,
            ToolCallOrigin origin) {
        if (event.type().equals("core.run.waiting_approval")) {
            java.util.concurrent.CompletableFuture.runAsync(
                    () -> FrameworkToolApprovalCoordinator.resolve(
                            agents, handle, origin, event.payload()));
            return;
        }
        if (callbacks == null) return;
        switch (event.type()) {
            case "core.model.started" -> callbacks.onEvent(new ConversationEvent.Hint(
                    "工作流 Agent 正在推理…"));
            case "core.tool.started" -> callbacks.onEvent(new ConversationEvent.Hint(
                    "工作流 Agent 调用工具：" + event.payload().path("tool").asText("unknown")));
            case "core.tool.completed" -> callbacks.onEvent(new ConversationEvent.ToolResult(
                    event.payload().path("tool").asText("unknown"),
                    event.payload().path("output").toString()));
            default -> {
                if (!event.type().startsWith("core.run.")) {
                    callbacks.onEvent(new ConversationEvent.Custom(event.type(), event.payload()));
                }
            }
        }
    }
}
