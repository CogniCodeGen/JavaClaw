package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 显式持久化 JSON 边界，使公共领域契约不依赖 Jackson，并保留兼容所需的载荷字段。 */
final class ThreadJsonCodec {
    private final ObjectMapper json = new ObjectMapper();

    String inputs(List<TurnInput> values) {
        ArrayNode array = json.createArrayNode();
        values.forEach(value -> array.add(input(value)));
        return write(array);
    }

    List<TurnInput> inputs(String value) {
        List<TurnInput> result = new ArrayList<>();
        for (JsonNode node : read(value)) {
            switch (node.path("type").asText()) {
                case "text" -> result.add(new TurnInput.Text(node.path("text").asText()));
                case "attachment" ->
                    result.add(new TurnInput.AttachmentRef(
                            node.path("sha256").asText(),
                            node.path("mediaType").asText(),
                            node.path("displayName").asText()));
                default ->
                    throw new IllegalArgumentException(
                            "unknown turn input type: " + node.path("type").asText());
            }
        }
        return List.copyOf(result);
    }

    String config(TurnConfig value) {
        ObjectNode root = json.createObjectNode();
        root.put("model", value.model());
        root.put("provider", value.provider());
        root.put("reasoningEffort", value.reasoningEffort());
        root.put("workingDirectory", value.workingDirectory().toString());
        root.put("approvalPolicy", value.approvalPolicy().name());
        root.set("enabledTools", strings(value.enabledTools()));
        root.set("attributes", map(value.attributes()));
        root.set("sandboxPolicy", sandbox(value.sandboxPolicy()));
        return write(root);
    }

    TurnConfig config(String value) {
        JsonNode root = read(value);
        return new TurnConfig(
                root.path("model").asText(),
                root.path("provider").asText(),
                root.path("reasoningEffort").asText(),
                Path.of(root.path("workingDirectory").asText()),
                sandbox(root.path("sandboxPolicy")),
                ApprovalPolicy.valueOf(root.path("approvalPolicy").asText()),
                stringSet(root.path("enabledTools")),
                stringMap(root.path("attributes")));
    }

    String item(ThreadItem value) {
        ObjectNode root = json.createObjectNode();
        root.put("kind", value.kind());
        switch (value) {
            case ThreadItem.Checkpoint item -> {
                root.put("executionId", item.executionId())
                        .put("definitionHash", item.definitionHash())
                        .put("stepId", item.stepId())
                        .put("status", item.status())
                        .put("iteration", item.iteration())
                        .put("usedModelCalls", item.usedModelCalls())
                        .put("usedTokens", item.usedTokens())
                        .put("elapsedMillis", item.elapsedMillis())
                        .put("summary", item.summary());
                root.set("outputs", map(item.outputs()));
                root.set("completedSteps", strings(item.completedSteps()));
            }
            case ThreadItem.Evaluation item -> {
                root.put("scope", item.scope()).put("passed", item.passed()).put("summary", item.summary());
                root.set("evidenceItemIds", strings(item.evidenceItemIds()));
                root.set("remaining", strings(item.remaining()));
            }
            case ThreadItem.Artifact item -> {
                root.put("artifactId", item.artifactId())
                        .put("category", item.category())
                        .put("name", item.name())
                        .put("revision", item.revision())
                        .put("content", item.content());
                root.set("sources", strings(item.sources()));
            }
            case ThreadItem.EffectReceipt receipt -> {
                root.put("key", receipt.key())
                        .put("tool", receipt.tool())
                        .put("state", receipt.state())
                        .put("summary", receipt.summary());
                if (receipt.result() != null) {
                    root.putObject("result")
                            .put("modelContent", receipt.result().modelContent())
                            .set("item", read(item(receipt.result().item())));
                }
            }
            case ThreadItem.UserMessage item -> {
                root.put("text", item.text());
                if (!item.attachments().isEmpty()) {
                    ArrayNode attachments = root.putArray("attachments");
                    item.attachments()
                            .forEach(reference -> attachments
                                    .addObject()
                                    .put("sha256", reference.sha256())
                                    .put("mediaType", reference.mediaType())
                                    .put("displayName", reference.displayName()));
                }
            }
            case ThreadItem.AgentMessage item -> root.put("text", item.text());
            case ThreadItem.ReasoningSummary item -> root.put("text", item.text());
            case ThreadItem.Plan item -> {
                ArrayNode steps = root.putArray("steps");
                item.steps()
                        .forEach(step -> steps.addObject()
                                .put("step", step.step())
                                .put("status", step.status().name()));
                if (!item.details().equals(ThreadItem.Plan.PlanDetails.EMPTY)) {
                    var details = item.details();
                    ObjectNode node = root.putObject("details");
                    node.put("goal", details.goal()).put("scope", details.scope());
                    node.set("dependencies", strings(details.dependencies()));
                    node.set("acceptanceCriteria", strings(details.acceptanceCriteria()));
                    node.set("risks", strings(details.risks()));
                    node.set("openQuestions", strings(details.openQuestions()));
                }
            }
            case ThreadItem.PromptDraft item -> {
                root.put("profileId", item.profileId())
                        .put("expectedRevision", item.expectedRevision())
                        .put("draft", item.draft());
                root.set("changes", strings(item.changes()));
                root.set("warnings", strings(item.warnings()));
            }
            case ThreadItem.CommandExecution item -> {
                root.set("argv", strings(item.argv()));
                root.put("exitCode", item.exitCode())
                        .put("stdout", item.stdout())
                        .put("stderr", item.stderr())
                        .put("timedOut", item.timedOut())
                        .put("truncated", item.truncated());
            }
            case ThreadItem.FileChange item ->
                root.put("path", item.path())
                        .put("change", item.change().name())
                        .put("diff", item.diff());
            case ThreadItem.McpToolCall item ->
                root.put("server", item.server()).put("tool", item.tool()).set("result", map(item.result()));
            case ThreadItem.DynamicToolCall item ->
                root.put("tool", item.tool()).set("result", map(item.result()));
            case ThreadItem.ApprovalRequest item ->
                root.put("approvalId", item.approvalId())
                        .put("reason", item.reason())
                        .put("risk", item.risk());
            case ThreadItem.UserInputRequest item ->
                root.put("requestId", item.requestId())
                        .put("prompt", item.prompt())
                        .set("choices", strings(item.choices()));
            case ThreadItem.UserInputResponse item ->
                root.put("requestId", item.requestId())
                        .put("value", item.value())
                        .put("cancelled", item.cancelled());
            case ThreadItem.SubagentCall item ->
                root.put("childThreadId", item.childThreadId().value())
                        .put("task", item.task())
                        .put("summary", item.summary());
            case ThreadItem.WebSearch item -> root.put("query", item.query()).set("sources", strings(item.sources()));
            case ThreadItem.ImageView item -> root.put("uri", item.uri()).put("description", item.description());
            case ThreadItem.ContextUsage item ->
                root.put("source", item.source())
                        .put("sourceId", item.sourceId())
                        .put("revision", item.revision())
                        .put("summary", item.summary());
            case ThreadItem.ContextCompaction item -> {
                // id-only Item 的有效载荷仅保留 kind；摘要与 opaque 内容属于 ConversationWindow。
            }
            case ThreadItem.ErrorItem item ->
                root.put("code", item.code()).put("message", item.message()).put("retryable", item.retryable());
        }
        return write(root);
    }

    private static List<TurnInput.AttachmentRef> attachmentReferences(JsonNode value) {
        List<TurnInput.AttachmentRef> result = new ArrayList<>();
        for (JsonNode reference : value) {
            result.add(new TurnInput.AttachmentRef(
                    reference.path("sha256").asText(),
                    reference.path("mediaType").asText(),
                    reference.path("displayName").asText()));
        }
        return List.copyOf(result);
    }

    ThreadItem item(String value) {
        JsonNode root = read(value);
        return switch (root.path("kind").asText()) {
            case "checkpoint" ->
                new ThreadItem.Checkpoint(
                        root.path("executionId").asText(),
                        root.path("definitionHash").asText(),
                        root.path("stepId").asText(),
                        root.path("status").asText(),
                        root.path("iteration").asInt(),
                        stringMap(root.path("outputs")),
                        stringList(root.path("completedSteps")),
                        root.path("usedModelCalls").asInt(),
                        root.path("usedTokens").asLong(),
                        root.path("elapsedMillis").asLong(),
                        root.path("summary").asText());
            case "evaluation" ->
                new ThreadItem.Evaluation(
                        root.path("scope").asText(),
                        root.path("passed").asBoolean(),
                        root.path("summary").asText(),
                        stringList(root.path("evidenceItemIds")),
                        stringList(root.path("remaining")));
            case "artifact" ->
                new ThreadItem.Artifact(
                        root.path("artifactId").asText(),
                        root.path("category").asText(),
                        root.path("name").asText(),
                        root.path("revision").asLong(),
                        root.path("content").asText(),
                        stringList(root.path("sources")));
            case "effectReceipt" ->
                new ThreadItem.EffectReceipt(
                        root.path("key").asText(),
                        root.path("tool").asText(),
                        root.path("state").asText(),
                        root.has("result")
                                ? new com.javaclaw.core.api.ToolExecutionResult(
                                        item(write(root.path("result").path("item"))),
                                        root.path("result").path("modelContent").asText())
                                : null,
                        root.path("summary").asText());
            case "userMessage" ->
                new ThreadItem.UserMessage(root.path("text").asText(), attachmentReferences(root.path("attachments")));
            case "agentMessage" -> new ThreadItem.AgentMessage(root.path("text").asText());
            case "reasoningSummary" ->
                new ThreadItem.ReasoningSummary(root.path("text").asText());
            case "plan" -> new ThreadItem.Plan(planSteps(root.path("steps")), planDetails(root.path("details")));
            case "promptDraft" ->
                new ThreadItem.PromptDraft(
                        root.path("profileId").asText(),
                        root.path("expectedRevision").asLong(),
                        root.path("draft").asText(),
                        stringList(root.path("changes")),
                        stringList(root.path("warnings")));
            case "commandExecution" ->
                new ThreadItem.CommandExecution(
                        stringList(root.path("argv")), root.path("exitCode").asInt(),
                        root.path("stdout").asText(), root.path("stderr").asText(),
                        root.path("timedOut").asBoolean(),
                                root.path("truncated").asBoolean());
            case "fileChange" ->
                new ThreadItem.FileChange(
                        root.path("path").asText(),
                        ThreadItem.FileChange.ChangeKind.valueOf(
                                root.path("change").asText()),
                        root.path("diff").asText());
            case "mcpToolCall" ->
                new ThreadItem.McpToolCall(
                        root.path("server").asText(), root.path("tool").asText(), stringMap(root.path("result")));
            case "dynamicToolCall" ->
                new ThreadItem.DynamicToolCall(root.path("tool").asText(), stringMap(root.path("result")));
            case "approvalRequest" ->
                new ThreadItem.ApprovalRequest(
                        root.path("approvalId").asText(),
                        root.path("reason").asText(),
                        root.path("risk").asText());
            case "userInputRequest" ->
                new ThreadItem.UserInputRequest(
                        root.path("requestId").asText(),
                        root.path("prompt").asText(),
                        stringList(root.path("choices")));
            case "userInputResponse" ->
                new ThreadItem.UserInputResponse(
                        root.path("requestId").asText(),
                        root.path("value").asText(),
                        root.path("cancelled").asBoolean());
            case "subagentCall" ->
                new ThreadItem.SubagentCall(
                        new ThreadId(root.path("childThreadId").asText()),
                        root.path("task").asText(),
                        root.path("summary").asText());
            case "webSearch" -> new ThreadItem.WebSearch(root.path("query").asText(), stringList(root.path("sources")));
            case "imageView" ->
                new ThreadItem.ImageView(
                        root.path("uri").asText(), root.path("description").asText());
            case "contextUsage" ->
                new ThreadItem.ContextUsage(
                        root.path("source").asText(), root.path("sourceId").asText(),
                        root.path("revision").asLong(), root.path("summary").asText());
            case "compaction", "contextCompaction" -> new ThreadItem.ContextCompaction();
            case "error" ->
                new ThreadItem.ErrorItem(
                        root.path("code").asText(),
                        root.path("message").asText(),
                        root.path("retryable").asBoolean());
            default ->
                throw new IllegalArgumentException(
                        "unknown stored item kind: " + root.path("kind").asText());
        };
    }

    /** 编码 ConversationWindow 中保留的用户消息列表；正文不进入 Item 或普通事件。 */
    String stringValues(List<String> values) {
        return write(strings(values));
    }

    /** 解码 ConversationWindow 的用户消息列表；旧空值归一为空列表。 */
    List<String> stringValues(String value) {
        return value == null || value.isBlank() ? List.of() : stringList(read(value));
    }

    /** 提取 v4 早期 Compaction Item，供一次性窗口回填；普通 Item 解码不会继续暴露摘要。 */
    LegacyCompaction legacyCompaction(String value) {
        JsonNode root = read(value);
        return new LegacyCompaction(
                root.path("summary").asText(),
                root.path("compactedThroughSequence").asLong());
    }

    record LegacyCompaction(String summary, long coveredSequence) {}

    private ThreadItem.Plan.PlanDetails planDetails(JsonNode value) {
        if (value.isMissingNode()) {
            return ThreadItem.Plan.PlanDetails.EMPTY;
        }
        return new ThreadItem.Plan.PlanDetails(
                value.path("goal").asText(),
                value.path("scope").asText(),
                stringList(value.path("dependencies")),
                stringList(value.path("acceptanceCriteria")),
                stringList(value.path("risks")),
                stringList(value.path("openQuestions")));
    }

    String mapValue(Map<String, String> values) {
        return write(map(values));
    }

    Map<String, String> mapValue(String value) {
        return stringMap(read(value));
    }

    String event(ThreadEvent event) {
        ObjectNode root = json.createObjectNode();
        root.put("eventId", event.eventId());
        root.put("threadId", event.threadId().value());
        if (event.turnId() != null) {
            root.put("turnId", event.turnId().value());
        }
        root.put("sequence", event.sequence());
        root.put("type", event.type());
        root.put("schemaVersion", event.schemaVersion());
        if (event.correlationId() != null) {
            root.put("correlationId", event.correlationId());
        }
        if (event.causationId() != null) {
            root.put("causationId", event.causationId());
        }
        root.set("payload", map(event.payload()));
        root.put("timestamp", event.timestamp().toString());
        return write(root);
    }

    private ObjectNode input(TurnInput value) {
        ObjectNode result = json.createObjectNode().put("type", value.type());
        if (value instanceof TurnInput.Text text) {
            result.put("text", text.text());
        } else if (value instanceof TurnInput.AttachmentRef attachment) {
            result.put("sha256", attachment.sha256())
                    .put("mediaType", attachment.mediaType())
                    .put("displayName", attachment.displayName());
        }
        return result;
    }

    private ObjectNode sandbox(SandboxPolicy value) {
        ObjectNode root = json.createObjectNode();
        root.put("mode", value.mode().name());
        root.set("readableRoots", paths(value.readableRoots()));
        root.set("writableRoots", paths(value.writableRoots()));
        root.set("protectedRoots", paths(value.protectedRoots()));
        root.put("networkMode", value.network().mode().name());
        root.set("allowedHosts", strings(value.network().allowedHosts()));
        root.set("inheritedEnvironment", strings(value.inheritedEnvironment()));
        root.put("timeoutMillis", value.timeout().toMillis());
        root.put("outputLimitBytes", value.outputLimitBytes());
        return root;
    }

    private static SandboxPolicy sandbox(JsonNode root) {
        return new SandboxPolicy(
                SandboxMode.valueOf(root.path("mode").asText()),
                pathSet(root.path("readableRoots")),
                pathSet(root.path("writableRoots")),
                pathSet(root.path("protectedRoots")),
                new NetworkPolicy(
                        NetworkPolicy.Mode.valueOf(root.path("networkMode").asText()),
                        stringSet(root.path("allowedHosts"))),
                stringSet(root.path("inheritedEnvironment")),
                Duration.ofMillis(root.path("timeoutMillis").asLong()),
                root.path("outputLimitBytes").asLong());
    }

    private List<ThreadItem.Plan.PlanStep> planSteps(JsonNode array) {
        List<ThreadItem.Plan.PlanStep> result = new ArrayList<>();
        for (JsonNode value : array) {
            result.add(new ThreadItem.Plan.PlanStep(
                    value.path("step").asText(),
                    ThreadItem.Plan.PlanStep.Status.valueOf(value.path("status").asText())));
        }
        return List.copyOf(result);
    }

    private ArrayNode paths(Set<Path> values) {
        ArrayNode array = json.createArrayNode();
        values.forEach(path -> array.add(path.toString()));
        return array;
    }

    private ArrayNode strings(Iterable<String> values) {
        ArrayNode array = json.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private ObjectNode map(Map<String, String> values) {
        ObjectNode object = json.createObjectNode();
        values.forEach(object::put);
        return object;
    }

    private static Set<Path> pathSet(JsonNode array) {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        for (JsonNode value : array) {
            result.add(Path.of(value.asText()));
        }
        return Set.copyOf(result);
    }

    private static Set<String> stringSet(JsonNode array) {
        return Set.copyOf(stringList(array));
    }

    private static List<String> stringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array) {
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(JsonNode object) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        object.fields()
                .forEachRemaining(
                        entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(result);
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot read persisted JSON", failure);
        }
    }

    private String write(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot write persisted JSON", failure);
        }
    }
}
