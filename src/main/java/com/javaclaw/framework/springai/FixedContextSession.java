package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.ToolGroupAccess;
import com.javaclaw.framework.core.ToolInvocationGateway;
import com.javaclaw.framework.spi.FixedContextSnapshot;
import com.javaclaw.framework.spi.FixedContextSource;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.RunCancelledException;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.ToolPolicyDecision;
import com.javaclaw.framework.core.ToolApprovalRequiredException;
import com.javaclaw.framework.core.ToolInputRequiredException;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads each plan-locked required source once through the normal tool gateway. */
final class FixedContextSession {
    static final String SOURCE_METADATA = "javaclaw.fixedContextSource";
    static final String VERSION_METADATA = "javaclaw.fixedContextVersion";

    private final ReasoningRequest request;
    private final ToolInvocationGateway tools;
    private final RunStore runs;
    private final RunStepQuery steps;
    private final ObjectMapper json;
    private final List<FixedContextSource> sources;

    FixedContextSession(ReasoningRequest request, ToolInvocationGateway tools,
            RunStore runs, ObjectMapper json) {
        this.request = Objects.requireNonNull(request);
        this.tools = Objects.requireNonNull(tools);
        this.runs = Objects.requireNonNull(runs);
        this.steps = new RunStepQuery(runs);
        this.json = Objects.requireNonNull(json);
        this.sources = request.plan().fixedContextSources();
    }

    int pendingReads() {
        int pending = 0;
        for (FixedContextSource source : sources) {
            checkAllowed(source);
            if (existing(source) == null) pending++;
        }
        return pending;
    }

    List<UserMessage> messages(int reservedCalls) {
        int pending = pendingReads();
        if (pending + reservedCalls > request.control().remainingToolCalls()) {
            throw pause("tool-call budget is insufficient for fixed context reads and selected business tool");
        }
        List<UserMessage> result = new ArrayList<>();
        for (FixedContextSource source : sources) {
            FixedContextSnapshot snapshot = read(source);
            if (snapshot.body().isBlank()) continue;
            result.add(UserMessage.builder().text("Saved user persona from " + source.id()
                            + " version " + snapshot.version()
                            + ". Apply only where consistent with system and project instructions.\n"
                            + snapshot.body())
                    .metadata(Map.of(SOURCE_METADATA, source.id(),
                            VERSION_METADATA, snapshot.version())).build());
        }
        return List.copyOf(result);
    }

    private FixedContextSnapshot read(FixedContextSource source) {
        AgentStep old = existing(source);
        JsonNode output;
        if (old != null) {
            output = old.output().path("modelOutput");
        } else {
            if (request.control().remainingToolCalls() == 0) {
                throw pause("tool-call budget is insufficient for fixed context read");
            }
            try {
                SpringAiToolCallback.invokeInline(tool(source),
                        JsonNodeFactory.instance.objectNode(), request, tools,
                        invocation(source));
                AgentStep completed = existing(source);
                if (completed == null) {
                    throw pause("fixed context read was not durably recorded: " + source.id());
                }
                output = completed.output().path("modelOutput");
            } catch (ToolApprovalRequiredException | ToolInputRequiredException
                    | ToolRecoveryRequiredException | RunCancelledException control) {
                throw control;
            } catch (RuntimeException failure) {
                throw pause("fixed context read failed: " + source.id(), failure);
            }
        }
        if (!output.path("version").isTextual() || !output.path("body").isTextual()) {
            throw pause("persisted fixed context snapshot is incomplete: " + source.id());
        }
        return new FixedContextSnapshot(output.path("version").asText(),
                output.path("body").asText());
    }

    private AgentStep existing(FixedContextSource source) {
        AgentStep step = steps.step(request.runId(), StepId.tool(request.runId(), invocation(source)))
                .orElse(null);
        if (step == null) return null;
        if (step.kind() != AgentStep.Kind.TOOL || step.input() == null
                || !step.input().path("tool").asText().equals(toolName(source))
                || !step.input().path("invocationId").asText().equals(invocation(source))) {
            throw pause("fixed context checkpoint does not match frozen source: " + source.id());
        }
        if (step.state() != AgentStep.State.COMPLETED) {
            throw pause("fixed context read outcome is unknown; reconcile step " + step.id().value());
        }
        if (step.output() == null || step.output().path("waitingInput").asBoolean(false)
                || redacted(step.id())) {
            throw pause("persisted fixed context snapshot is unavailable: " + step.id().value());
        }
        JsonNode raw = step.output().path("rawOutput");
        JsonNode model = step.output().path("modelOutput");
        if (!raw.isObject() || !raw.equals(model)) {
            throw pause("persisted fixed context snapshot was modified: " + step.id().value());
        }
        return step;
    }

    private boolean redacted(StepId id) {
        return runs.eventsAfter(request.runId(), 0).stream().anyMatch(event ->
                event.type().equals("core.step.completed")
                        && event.payload().path("stepId").asText().equals(id.value())
                        && event.payload().path("credentialRedacted").asBoolean(false));
    }

    private void checkAllowed(FixedContextSource source) {
        JsonNode disabled = request.runRequest().attributes().get("framework.disableTools");
        if (disabled != null && disabled.asBoolean(false)) {
            throw pause("fixed context source requires tools but tools are disabled: " + source.id());
        }
        if (!ToolGroupAccess.allows(request.runRequest(), source.group())
                || !request.plan().descriptor().permissions()
                        .containsAll(source.requiredPermissions())) {
            throw pause("fixed context source is not authorized: " + source.id());
        }
        ToolDescriptor descriptor = tool(source).descriptor();
        for (var policy : request.plan().toolPolicies()) {
            if (policy.evaluate(descriptor, request.plan().descriptor().toolPolicy(),
                    request.runRequest()) == ToolPolicyDecision.DENY) {
                throw pause("fixed context source is denied by tool policy: " + source.id());
            }
        }
    }

    private FrameworkTool tool(FixedContextSource source) {
        JsonNode schema;
        try { schema = json.readTree("{\"type\":\"object\",\"additionalProperties\":false}"); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        ToolDescriptor descriptor = new ToolDescriptor(toolName(source),
                "Read fixed context " + source.id(), schema,
                source.group(), source.requiredPermissions(), true);
        return new com.javaclaw.framework.spi.FrameworkContextReadTool() {
            @Override public ToolDescriptor descriptor() { return descriptor; }
            @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
                FixedContextSnapshot snapshot = source.read(request.runRequest());
                return JsonNodeFactory.instance.objectNode()
                        .put("version", snapshot.version()).put("body", snapshot.body());
            }
        };
    }

    private static String invocation(FixedContextSource source) {
        return "fixed/" + source.id();
    }

    private static String toolName(FixedContextSource source) {
        return "framework_context_fixed_" + digest(source.id()).substring(0, 12);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean isFixed(UserMessage message) {
        return message.getMetadata().get(SOURCE_METADATA) instanceof String;
    }

    private static ContextPlanningRequiredException pause(String message) {
        return new ContextPlanningRequiredException(message);
    }

    private static ContextPlanningRequiredException pause(String message, Throwable cause) {
        return new ContextPlanningRequiredException(message, cause);
    }
}
