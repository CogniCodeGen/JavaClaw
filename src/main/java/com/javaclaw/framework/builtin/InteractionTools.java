package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.InteractionMode;
import com.javaclaw.framework.api.InteractionTask;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.spi.ToolExecutionResultV1;
import com.javaclaw.framework.core.InteractionExecutionPolicy;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.InteractionDelegateGateway;
import com.javaclaw.framework.spi.ToolContext;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolProviderFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Role-specific handoff tools. Child waiting and completion belong to the AgentEngine. */
public final class InteractionTools implements ToolProviderFactory {
    private final InteractionDelegateGateway gateway;
    private final ObjectMapper json;

    public InteractionTools(InteractionDelegateGateway gateway, ObjectMapper json) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.json = Objects.requireNonNull(json, "json");
    }

    public static boolean isTrustedTool(FrameworkTool tool) {
        return tool != null && tool.getClass().getEnclosingClass() == InteractionTools.class
                && Set.of(InteractionExecutionPolicy.DELEGATE_TOOL,
                        InteractionExecutionPolicy.SELECT_MODE_TOOL,
                        InteractionExecutionPolicy.WAIT_EVENT_TOOL,
                        InteractionExecutionPolicy.READ_RESULT_TOOL).contains(tool.descriptor().name());
    }

    public static boolean isTrustedDelegateTool(FrameworkTool tool) {
        return isTrustedTool(tool) && InteractionExecutionPolicy.DELEGATE_TOOL.equals(tool.descriptor().name());
    }

    public static boolean isTrustedSelectModeTool(FrameworkTool tool) {
        return isTrustedTool(tool) && InteractionExecutionPolicy.SELECT_MODE_TOOL.equals(tool.descriptor().name());
    }

    public static boolean isTrustedWaitEventTool(FrameworkTool tool) {
        return isTrustedTool(tool) && InteractionExecutionPolicy.WAIT_EVENT_TOOL.equals(tool.descriptor().name());
    }

    public static boolean isTrustedReadResultTool(FrameworkTool tool) {
        return isTrustedTool(tool) && InteractionExecutionPolicy.READ_RESULT_TOOL.equals(tool.descriptor().name());
    }

    /** Only these exact host implementations may classify their closed pre-subscription/state rejections. */
    public static boolean isTrustedControlRejection(FrameworkTool tool, ToolExecutionResultV1 result) {
        return result != null && result.status() == ToolExecutionStatus.FAILED
                && (isTrustedWaitEventTool(tool) && result.errorCode().equals("WAIT_BASELINE_REQUIRED")
                        && rejectedControl(result.data(), "interaction.wait_rejected", "WAIT_BASELINE_REQUIRED")
                    || isTrustedSelectModeTool(tool) && result.errorCode().equals("FRESH_OBSERVATION_REQUIRED")
                        && rejectedControl(result.data(), "interaction.mode_rejected", "FRESH_OBSERVATION_REQUIRED"));
    }

    @Override public List<FrameworkTool> create(ToolContext context) {
        return InteractionExecutionPolicy.isInteraction(context.request())
                ? List.of(selectMode(context), waitEvent(context)) : List.of(delegate(context), readResult(context));
    }

    private FrameworkTool delegate(ToolContext parent) {
        ToolDescriptor descriptor = new ToolDescriptor(InteractionExecutionPolicy.DELEGATE_TOOL,
                "Delegate one browser or desktop task to the dedicated interaction executor. Supply the goal, "
                        + "necessary data and constraints. The host binds the complete original frozen interaction "
                        + "acceptance block in its original order; do not supply or rewrite criterion IDs. It waits for the "
                        + "child and returns its structured terminal result; do not poll or repeat the same handoff.",
                delegateSchema(), InteractionExecutionPolicy.TOOL_GROUP,
                PermissionSet.of("subagent.delegate"), false);
        return new FrameworkTool() {
            @Override public ToolDescriptor descriptor() { return descriptor; }
            @Override public JsonNode execute(JsonNode input, ToolExecutionContext execution) {
                requireRun(parent, execution);
                if (!InteractionExecutionPolicy.isMain(parent.request())) {
                    throw new SecurityException("interaction executors cannot delegate recursively");
                }
                InteractionTask task = new InteractionTask(1, execution.invocationId(), 1,
                        mode(input.path("mode").asText("AUTO")), input.path("goal").asText(""),
                        input.get("necessaryData"), strings(input.get("constraints"), "constraints"),
                        List.of());
                return json.valueToTree(gateway.delegate(parent, execution, task));
            }
        };
    }

    private FrameworkTool selectMode(ToolContext child) {
        ToolDescriptor descriptor = new ToolDescriptor(InteractionExecutionPolicy.SELECT_MODE_TOOL,
                "Select the BROWSER (Playwright) or DESKTOP (native application) backend for subsequent steps "
                        + "within this task's allowed modes. This preserves the same run, evidence and unknown effects. "
                        + "Before switching, optionally checkpoint necessary business data such as the values read. "
                        + "The JSON object is limited to 4000 characters; no passwords, tokens, cookies, raw screenshots "
                        + "or authoritative handles/evidence. Same-mode calls may update it. Observe afresh before acting.",
                selectSchema(), InteractionExecutionPolicy.TOOL_GROUP, PermissionSet.of("tool.read"), true);
        return new FrameworkTool() {
            @Override public ToolDescriptor descriptor() { return descriptor; }
            @Override public JsonNode execute(JsonNode input, ToolExecutionContext execution) {
                requireRun(child, execution);
                if (!InteractionExecutionPolicy.isInteraction(child.request())) {
                    throw new SecurityException("only the interaction executor can select a backend");
                }
                InteractionMode selected = mode(input.path("mode").asText(""));
                if (!selected.executable()
                        || !InteractionExecutionPolicy.allowedModes(child.request()).contains(selected)) {
                    throw new IllegalArgumentException("mode is outside this task's allowed backends");
                }
                return gateway.selectMode(child, execution, selected,
                        com.javaclaw.framework.core.InteractionBusinessCheckpoint.sanitize(input.get("checkpoint")));
            }
            @Override public ToolExecutionResultV1 executeResult(JsonNode input, ToolExecutionContext execution) {
                return interactionControlResult(execute(input, execution), "interaction.mode_rejected", "FRESH_OBSERVATION_REQUIRED");
            }
        };
    }

    private FrameworkTool waitEvent(ToolContext child) {
        ToolDescriptor descriptor = new ToolDescriptor(InteractionExecutionPolicy.WAIT_EVENT_TOOL,
                "Wait for a newer frame or lifecycle event from an owned desktop session without polling. "
                        + "Use the last observed capturedAtMillis. If the current task's next action is already "
                        + "available, take it instead of waiting. Default BOUNDED resumes on an event or after "
                        + "timeoutMillis with WAIT_TIMEOUT; observe fresh state and replan before acting. "
                        + "Choose UNTIL_CHANGE only when the task requires a real external change: timeoutMillis "
                        + "is then a subscription slice that renews silently until an event or the run deadline, "
                        + "not a promise to resume after that slice. Unchanged captures do not wake the wait. "
                        + "A timeout is not task completion or acceptance evidence.",
                waitSchema(), InteractionExecutionPolicy.TOOL_GROUP, PermissionSet.of("tool.read"), true);
        return new FrameworkTool() {
            @Override public ToolDescriptor descriptor() { return descriptor; }
            @Override public JsonNode execute(JsonNode input, ToolExecutionContext execution) {
                requireRun(child, execution);
                if (!InteractionExecutionPolicy.isInteraction(child.request())
                        || !InteractionExecutionPolicy.allowedModes(child.request()).contains(InteractionMode.DESKTOP)) {
                    throw new SecurityException("desktop event waiting is outside this task's backends");
                }
                return gateway.waitForEvent(child, execution, input.deepCopy());
            }
            @Override public ToolExecutionResultV1 executeResult(JsonNode input, ToolExecutionContext execution) {
                return interactionControlResult(execute(input, execution), "interaction.wait_rejected", "WAIT_BASELINE_REQUIRED");
            }
        };
    }

    private FrameworkTool readResult(ToolContext parent) {
        ToolDescriptor descriptor = new ToolDescriptor(InteractionExecutionPolicy.READ_RESULT_TOOL,
                "Read a bounded slice of a completed owned interaction child's business result using the "
                        + "run:<childRunId>:output reference returned by the host. This retrieves result text, "
                        + "not the child's full trace and not new observation or acceptance evidence.",
                readResultSchema(), InteractionExecutionPolicy.TOOL_GROUP, PermissionSet.of("tool.read"), true);
        return new FrameworkTool() {
            @Override public ToolDescriptor descriptor() { return descriptor; }
            @Override public JsonNode execute(JsonNode input, ToolExecutionContext execution) {
                requireRun(parent, execution);
                if (!InteractionExecutionPolicy.isMain(parent.request())) {
                    throw new SecurityException("only the parent can read delegated business results");
                }
                return gateway.readResult(parent, execution, input.deepCopy());
            }
        };
    }

    private static ObjectNode delegateSchema() {
        ObjectNode schema = object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("goal").put("type", "string").put("minLength", 1).put("maxLength", 8_000);
        properties.putObject("necessaryData").put("type", "object");
        properties.putObject("constraints").put("type", "array").put("maxItems", 16)
                .putObject("items").put("type", "string").put("minLength", 1).put("maxLength", 512);
        properties.putObject("mode").put("type", "string").putArray("enum")
                .add("AUTO").add("BROWSER").add("DESKTOP").add("HYBRID");
        schema.putArray("required").add("goal");
        return schema;
    }

    private static ObjectNode selectSchema() {
        ObjectNode schema = object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("mode").put("type", "string")
                .putArray("enum").add("BROWSER").add("DESKTOP");
        properties.putObject("checkpoint").put("type", "object").put("description",
                "Optional necessary business data, serialized JSON <=4000 characters. Unverified draft only; "
                        + "no credentials, raw screenshots, authority, reusable handles or acceptance proof.");
        schema.putArray("required").add("mode");
        return schema;
    }

    private static ObjectNode waitSchema() {
        ObjectNode schema = object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("sessionId").put("type", "string").put("minLength", 1).put("maxLength", 256);
        properties.putObject("afterCapturedAtMillis").put("type", "integer").put("minimum", 0)
                .put("description", "Exact capturedAtMillis from this session's successful host observation.");
        properties.putObject("waitMode").put("type", "string").put("default", "BOUNDED")
                .put("description", "BOUNDED returns WAIT_TIMEOUT when the whole wait expires. UNTIL_CHANGE "
                        + "silently renews subscription slices; use only for a task requiring an external change.")
                .putArray("enum").add("BOUNDED").add("UNTIL_CHANGE");
        properties.putObject("timeoutMillis").put("type", "integer").put("minimum", 1)
                .put("maximum", 30_000).put("default", 30_000)
                .put("description", "Whole wait bound for BOUNDED; subscription slice for UNTIL_CHANGE. "
                        + "Both remain within the run's original deadline.");
        schema.putArray("required").add("sessionId").add("afterCapturedAtMillis");
        return schema;
    }

    private static ObjectNode readResultSchema() {
        ObjectNode schema = object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("resultRef").put("type", "string").put("minLength", 1)
                .put("maxLength", 512).put("pattern", "^run:[^:]+:output$");
        properties.putObject("offset").put("type", "integer").put("minimum", 0)
                .put("maximum", Integer.MAX_VALUE).put("default", 0);
        properties.putObject("maxChars").put("type", "integer").put("minimum", 1)
                .put("maximum", 4_000).put("default", 4_000);
        schema.putArray("required").add("resultRef");
        return schema;
    }

    private static List<String> strings(JsonNode value, String field) {
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw new IllegalArgumentException(field + " requires strings");
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static InteractionMode mode(String value) {
        try {
            return InteractionMode.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("unsupported interaction mode", invalid);
        }
    }

    private static void requireRun(ToolContext context, ToolExecutionContext execution) {
        execution.cancellation().throwIfCancelled();
        if (!context.runId().equals(execution.runId())) throw new SecurityException("interaction run mismatch");
    }

    private static ToolExecutionResultV1 interactionControlResult(JsonNode raw, String kind, String errorCode) {
        return rejectedControl(raw, kind, errorCode)
                ? new ToolExecutionResultV1(ToolExecutionStatus.FAILED, raw, errorCode, raw.path("message").asText())
                : ToolExecutionResultV1.success(raw);
    }

    private static boolean rejectedControl(JsonNode raw, String kind, String errorCode) {
        return raw != null && raw.isObject() && raw.path("kind").asText().equals(kind)
                && raw.path("errorCode").asText().equals(errorCode)
                && raw.path("dispatchAttempted").isBoolean() && !raw.path("dispatchAttempted").booleanValue()
                && (kind.equals("interaction.mode_rejected")
                        ? raw.path("modeSelectionAccepted").isBoolean() && !raw.path("modeSelectionAccepted").booleanValue()
                        : raw.path("subscriptionCreated").isBoolean() && !raw.path("subscriptionCreated").booleanValue());
    }

    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
}
