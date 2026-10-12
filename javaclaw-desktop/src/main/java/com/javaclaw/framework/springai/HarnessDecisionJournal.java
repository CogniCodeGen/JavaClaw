package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.ModelDecisionV1;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.StepEvents;
import com.javaclaw.framework.core.ToolInvocationResult;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.util.SensitiveDataRedactor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.javaclaw.framework.springai.PersistedToolCallCodec.recoveryRequired;

/** 持久化并恢复模型控制申请，拒绝记录与成功决策始终分离。 */
final class HarnessDecisionJournal {
    private static final JsonSchemaValidator DECISION_FEEDBACK_VALIDATOR = new JsonSchemaValidator();
    private static final int MAX_DECISION_FEEDBACK_ISSUES = 8;
    private static final int MAX_DECISION_FEEDBACK_CHARACTERS = 1_800;
    private final ReasoningRequest request;
    private final RunStepQuery steps;
    private final RunStore runs;
    private final ObjectMapper json;

    HarnessDecisionJournal(ReasoningRequest request, RunStepQuery steps, RunStore runs, ObjectMapper json) {
        this.request = request;
        this.steps = steps;
        this.runs = runs;
        this.json = json;
    }

    JsonNode persistInvalidDecision(StepId modelStep, String invocation, JsonNode feedback) {
        StepId step = StepId.tool(request.runId(), invocation);
        AgentStep existing = steps.step(request.runId(), step).orElse(null);
        if (existing != null && (!request.runId().equals(existing.turnId())
                || !modelStep.value().equals(existing.causationStepId())
                || existing.kind() != AgentStep.Kind.ORCHESTRATION
                || existing.input() == null
                || !"harness.decision_invalid".equals(
                        existing.input().path("phase").asText(""))
                || !modelStep.value().equals(
                        existing.input().path("modelStepId").asText(""))
                || !invocation.equals(existing.input().path("invocationId").asText("")))) {
            throw new ToolRecoveryRequiredException(step.value(),
                    "persisted invalid harness decision step conflicts with provider call");
        }
        if (runs.eventsAfter(request.runId(), 0).stream().anyMatch(event ->
                event.type().equals("core.harness.decision_submitted")
                        && event.schemaVersion() == 1
                        && event.producer().equals("framework.springai")
                        && modelStep.value().equals(
                                event.payload().path("modelStepId").asText("")))) {
            throw new ToolRecoveryRequiredException(step.value(),
                    "invalid harness decision conflicts with a submitted decision");
        }
        if (existing != null && existing.state() == AgentStep.State.COMPLETED) {
            if (existing.output() == null || !existing.output().has("modelOutput")) {
                throw new ToolRecoveryRequiredException(step.value(),
                        "persisted invalid harness decision feedback is unavailable");
            }
            return existing.output().path("modelOutput");
        }
        if (existing != null && existing.input().has("feedback")) {
            // 即使已有后续收据，重启也必须复现原拒绝。
            feedback = existing.input().path("feedback");
        } else if (feedback == null) {
            // 兼容没有持久化详细反馈的中断拒绝记录。
            feedback = invalidDecisionFeedback(new IllegalArgumentException());
        }
        if (existing == null) {
            var input = JsonNodeFactory.instance.objectNode()
                    .put("phase", "harness.decision_invalid")
                    .put("modelStepId", modelStep.value())
                    .put("invocationId", invocation);
            input.set("feedback", feedback);
            StepEvents.started(request.events(), step, AgentStep.Kind.ORCHESTRATION,
                    input, modelStep.value());
        }
        var output = JsonNodeFactory.instance.objectNode().put("durationMillis", 0);
        output.set("rawOutput", feedback);
        output.set("modelOutput", feedback);
        output.put("status", ToolExecutionStatus.FAILED.name());
        StepEvents.completed(request.events(), step, output, null);
        return feedback;
    }

    JsonNode invalidDecisionFeedback(Exception invalid) {
        return invalidDecisionFeedback(invalid, null);
    }

    JsonNode invalidDecisionFeedback(Exception invalid, String rawArguments) {
        String reasonCode;
        String detail;
        if (invalid instanceof DecisionValidationException validation) {
            reasonCode = validation.reasonCode;
            detail = validation.getMessage();
        } else if (invalid instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            reasonCode = "DECISION_JSON_INVALID";
            detail = "Arguments must be a JSON object matching the harness_submit_decision schema.";
        } else {
            reasonCode = "DECISION_SCHEMA_INVALID";
            detail = "Arguments must match the harness_submit_decision schema: decision must be "
                    + "CLAIM_DONE, CONTINUE, NEEDS_INPUT or BLOCKED; userMessage and "
                    + "unmetCriterionIds are required; no extra properties are allowed.";
            String issues = decisionSchemaFeedback(rawArguments, invalid);
            if (!issues.isEmpty()) detail += " Validation issues: " + issues;
        }
        var feedback = JsonNodeFactory.instance.objectNode()
                .put("accepted", false)
                .put("errorCode", "INVALID_DECISION_ARGUMENTS")
                .put("reasonCode", reasonCode)
                .put("message", detail + " Correct only the control arguments; do not repeat "
                        + "business tools just to repair this rejection.");
        var refs = feedback.putArray("availableEvidenceRefs");
        List<String> trusted = HarnessDecisionEvidence.trustedRefs(runs, request.runId());
        trusted.stream().skip(Math.max(0, trusted.size() - 4)).forEach(refs::add);
        var criteria = feedback.putArray("availableCriterionIds");
        availableCriterionIds().stream().limit(32).forEach(criteria::add);
        return feedback;
    }

    /** 只提供校验位置与固定修正说明，不回显参数值或异常文本。 */
    private String decisionSchemaFeedback(String rawArguments, Exception invalid) {
        if (rawArguments == null) return "";
        JsonNode arguments;
        try {
            arguments = json.readTree(rawArguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            return "";
        }
        if (arguments == null) return "/decision must be a JSON object.";
        var schema = ModelDecisionV1.schema();
        var validation = DECISION_FEEDBACK_VALIDATOR.validate(schema, arguments, "/decision");
        if (validation.isEmpty()) {
            // 构造器不变量独立于 schema 校验，只允许固定宿主说明。
            return switch (java.util.Objects.toString(invalid.getMessage(), "")) {
                case "model decision exceeds protocol bounds" ->
                        "Control strings and arrays must remain within the declared protocol bounds.";
                case "terminal model decision requires userMessage" ->
                        "/decision/userMessage must be nonblank unless decision is CONTINUE.";
                case "completion claim cannot list unmet criteria" ->
                        "/decision/unmetCriterionIds must be empty for CLAIM_DONE.";
                default -> "";
            };
        }
        List<String> details = new ArrayList<>();
        Set<String> locations = new HashSet<>();
        if (arguments.isObject()) {
            arguments.fieldNames().forEachRemaining(field -> {
                if (!schema.path("properties").has(field)
                        && details.size() < MAX_DECISION_FEEDBACK_ISSUES) {
                    String location = "/decision/" + safeDecisionField(field);
                    details.add(location + " [additionalProperties]: property is not allowed; "
                            + "submit only decision, userMessage, evidenceRefs and unmetCriterionIds.");
                    locations.add(location);
                }
            });
            schema.path("required").forEach(required -> {
                String field = required.asText();
                if (!arguments.has(field) && details.size() < MAX_DECISION_FEEDBACK_ISSUES) {
                    String location = "/decision/" + field;
                    details.add(location + " [required]: required property is missing.");
                    locations.add(location);
                }
            });
        }
        for (var issue : validation) {
            if (details.size() >= MAX_DECISION_FEEDBACK_ISSUES) break;
            String location = safeDecisionLocation(issue.path());
            if (!locations.add(location)) continue;
            String code = issue.code() != null && issue.code().matches("[A-Za-z0-9_.-]{1,64}")
                    ? issue.code() : "schema.validation";
            details.add(location + " [" + code + "]: " + decisionFieldExpectation(location));
        }
        String result = String.join("; ", details);
        while (result.length() > MAX_DECISION_FEEDBACK_CHARACTERS && details.size() > 1) {
            details.removeLast();
            result = String.join("; ", details);
        }
        return result;
    }

    private static String safeDecisionField(String field) {
        String normalized = field.toLowerCase(java.util.Locale.ROOT);
        return field.matches("[A-Za-z_][A-Za-z0-9_]{0,47}")
                && !normalized.matches(".*(?:password|passwd|secret|token|apikey|authorization|cookie).*")
                && !SensitiveDataRedactor.containsLikelyCredential(field) ? field : "<unrecognized-property>";
    }

    private static String safeDecisionLocation(String location) {
        if (location != null && location.matches(
                "/decision/(?:decision|userMessage|evidenceRefs|unmetCriterionIds)(?:/[0-9]{1,2})?")) {
            int lastSlash = location.lastIndexOf('/');
            String last = location.substring(lastSlash + 1);
            if (!last.matches("[0-9]+") || Integer.parseInt(last) < 32) return location;
        }
        return "/decision";
    }

    private static String decisionFieldExpectation(String location) {
        if (location.equals("/decision/decision")) {
            return "must be a string: CLAIM_DONE, CONTINUE, NEEDS_INPUT or BLOCKED.";
        }
        if (location.equals("/decision/userMessage")) {
            return "must be a string of at most 12000 characters.";
        }
        if (location.startsWith("/decision/evidenceRefs")) {
            if (!location.equals("/decision/evidenceRefs")) return "must be a string of 1 to 256 characters.";
            return "must be an array of at most 32 strings, each 1 to 256 characters.";
        }
        if (location.startsWith("/decision/unmetCriterionIds")) {
            if (!location.equals("/decision/unmetCriterionIds")) return "must be a string of 1 to 128 characters.";
            return "must be an array of at most 32 unique strings, each 1 to 128 characters.";
        }
        return "must be an object containing only the declared control properties and required fields.";
    }

    private List<String> availableCriterionIds() {
        return com.javaclaw.framework.core.TaskResultEvaluator.latestContractV3(
                runs.eventsAfter(request.runId(), 0), json)
                .filter(contract -> contract.applicable())
                .map(contract -> contract.criteria().stream()
                        .map(com.javaclaw.framework.api.TaskCriterionV3::id).toList())
                .orElse(List.of());
    }

    private static final class DecisionValidationException extends IllegalArgumentException {
        private final String reasonCode;

        private DecisionValidationException(String reasonCode, String detail) {
            super(detail);
            this.reasonCode = reasonCode;
        }
    }

    JsonNode persistDecision(StepId modelStep, String invocation,
            ModelDecisionV1 decision, JsonNode arguments) {
        StepId step = StepId.tool(request.runId(), invocation);
        JsonNode acknowledgement = JsonNodeFactory.instance.objectNode()
                .put("accepted", true).put("decision", decision.decision().name());
        var prior = runs.eventsAfter(request.runId(), 0).stream()
                .filter(event -> event.type().equals("core.harness.decision_submitted")
                        && event.schemaVersion() == 1
                        && event.producer().equals("framework.springai")
                        && modelStep.value().equals(
                                event.payload().path("modelStepId").asText("")))
                .toList();
        if (prior.size() > 1 || prior.size() == 1
                && (!invocation.equals(prior.getFirst().payload().path("invocationId").asText(""))
                    || !decision.toJson().equals(prior.getFirst().payload().path("value")))) {
            throw new ToolRecoveryRequiredException(step.value(),
                    "conflicting harness decisions for one model step");
        }
        AgentStep existing = steps.step(request.runId(), step).orElse(null);
        if (existing != null && existing.state() == AgentStep.State.COMPLETED) {
            if (prior.isEmpty() || existing.kind() != AgentStep.Kind.ORCHESTRATION
                    || !"harness.decision".equals(existing.input().path("phase").asText(""))) {
                throw new ToolRecoveryRequiredException(step.value(),
                        "completed harness decision lacks a matching control event");
            }
            return existing.output().path("modelOutput");
        }
        if (existing == null) {
            var input = JsonNodeFactory.instance.objectNode()
                    .put("phase", "harness.decision")
                    .put("modelStepId", modelStep.value())
                    .put("invocationId", invocation);
            input.set("arguments", arguments);
            StepEvents.started(request.events(), step, AgentStep.Kind.ORCHESTRATION,
                    input, modelStep.value());
        } else if (existing.kind() != AgentStep.Kind.ORCHESTRATION
                || !"harness.decision".equals(existing.input().path("phase").asText(""))
                || !modelStep.value().equals(existing.input().path("modelStepId").asText(""))) {
            throw new ToolRecoveryRequiredException(step.value(),
                    "persisted harness decision step does not match provider step");
        }
        if (prior.isEmpty()) {
            var payload = JsonNodeFactory.instance.objectNode()
                    .put("modelStepId", modelStep.value())
                    .put("invocationId", invocation)
                    .put("decision", decision.decision().name())
                    .put("userMessage", decision.userMessage());
            payload.set("evidenceRefs", decision.toJson().path("evidenceRefs"));
            payload.set("unmetCriterionIds", decision.toJson().path("unmetCriterionIds"));
            payload.set("value", decision.toJson());
            request.events().emit("core.harness.decision_submitted", 1,
                    "framework.springai", payload);
        }
        var output = JsonNodeFactory.instance.objectNode().put("durationMillis", 0);
        output.set("rawOutput", acknowledgement);
        output.set("modelOutput", acknowledgement);
        output.put("status", ToolExecutionStatus.SUCCEEDED.name());
        StepEvents.completed(request.events(), step, output, null);
        return acknowledgement;
    }

    void validateEvidenceRefs(ModelDecisionV1 decision) {
        if (decision.evidenceRefs().isEmpty()) return;
        Set<String> trusted = new HashSet<>(
                HarnessDecisionEvidence.trustedRefs(runs, request.runId()));
        if (!trusted.containsAll(decision.evidenceRefs())) {
            throw new DecisionValidationException("UNKNOWN_EVIDENCE_REFERENCE",
                    "evidenceRefs must contain exact host-issued IDs from tool response "
                            + "evidenceRefs or availableEvidenceRefs. Do not use tool names, "
                            + "result summaries, failed receipts or invented IDs. If no suitable "
                            + "evidence is available, omit evidenceRefs or use []; explain the "
                            + "blocker in userMessage and use NEEDS_INPUT or BLOCKED when appropriate.");
        }
    }

    void validateUnmetCriterionIds(ModelDecisionV1 decision) {
        if (decision.unmetCriterionIds().isEmpty()) return;
        if (!availableCriterionIds().containsAll(decision.unmetCriterionIds())) {
            throw new DecisionValidationException("UNKNOWN_CRITERION_ID",
                    "unmetCriterionIds must contain only IDs from the frozen task contract, "
                            + "listed in availableCriterionIds. Do not invent IDs or use condition "
                            + "descriptions; use [] when none apply.");
        }
    }
    List<Message> restoreCompletedDecisionArguments(
            List<Message> messages, List<AgentStep> history, AgentStep recoveredModel) {
        List<Message> restored = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant)) {
                restored.add(message);
                continue;
            }
            List<AssistantMessage.ToolCall> calls = new ArrayList<>(assistant.getToolCalls());
            boolean changed = false;
            for (int index = 0; index < calls.size(); index++) {
                AssistantMessage.ToolCall call = calls.get(index);
                if (!HarnessDecisionToolCallback.NAME.equals(call.name())
                        || syntacticallyValidJson(call.arguments())) continue;
                // 历史中的脱敏控制调用必须绑定原完成模型与调用 ID；
                // 不修复待处理调用，也不从展示文字推断决策。
                List<AgentStep> owners = history.stream().filter(step ->
                        step.turnId().equals(request.runId())
                                && step.kind() == AgentStep.Kind.MODEL
                                && step.state() == AgentStep.State.COMPLETED
                                && step.startSequence() <= recoveredModel.startSequence()
                                && step.lastSequence() <= recoveredModel.lastSequence()
                                && completedDecisionCallMatches(step, call)).toList();
                if (calls.size() != 1 || owners.size() != 1) {
                    throw recoveryRequired(recoveredModel,
                            "Historical harness arguments lack a unique completed model binding");
                }
                AgentStep owner = owners.getFirst();
                PersistedToolCallCodec.validatePersistedToolVisibility(request.runId(), owner, List.of(call));
                String invocation = PersistedToolCallCodec.invocationId(owner.id(), call);
                AgentStep control = steps.step(request.runId(),
                        StepId.tool(request.runId(), invocation)).orElseThrow(() ->
                                recoveryRequired(owner, "Historical harness decision is unavailable"));
                if (control.state() != AgentStep.State.COMPLETED) {
                    throw recoveryRequired(owner, "Historical harness decision is not completed");
                }
                if (control.input() != null && "harness.decision_invalid".equals(
                        control.input().path("phase").asText(""))) {
                    // 已拒绝的非法 JSON 只重放原拒绝，不补造有效参数或完成决策。
                    persistInvalidDecision(owner.id(), invocation, null);
                    continue;
                }
                recoverCompletedDecision(control, owner.id(), invocation);
                // 只使用已验证的逐叶脱敏类型输入，恢复 JSON 结构而非隐藏内容。
                String arguments = ModelDecisionV1.fromJson(
                        control.input().path("arguments")).toJson().toString();
                calls.set(index, new AssistantMessage.ToolCall(
                        call.id(), call.type(), call.name(), arguments));
                changed = true;
            }
            restored.add(changed ? AssistantMessage.builder().content(assistant.getText())
                    .toolCalls(calls).media(assistant.getMedia())
                    .properties(assistant.getMetadata()).build() : assistant);
        }
        return restored;
    }

    private static boolean completedDecisionCallMatches(
            AgentStep model, AssistantMessage.ToolCall call) {
        JsonNode output = model.output();
        if (output == null || !"assistant".equals(output.path("message").path("role").asText())) {
            return false;
        }
        JsonNode calls = output.path("message").path("toolCalls");
        if (!calls.isArray() || calls.size() != 1) return false;
        JsonNode recorded = calls.get(0);
        return recorded.path("id").isTextual() && call.id().equals(recorded.path("id").asText())
                && recorded.path("type").isTextual() && call.type().equals(recorded.path("type").asText())
                && recorded.path("name").isTextual() && call.name().equals(recorded.path("name").asText())
                && recorded.path("arguments").isTextual()
                && call.arguments().equals(recorded.path("arguments").asText());
    }

    private boolean syntacticallyValidJson(String arguments) {
        if (arguments == null || arguments.isBlank()) return false;
        try {
            JsonNode value = json.reader().with(
                    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(arguments);
            return value != null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return false;
        }
    }

    ToolInvocationResult recoverDecision(
            StepId modelStep, String invocation, String rawArguments) {
        AgentStep existing = steps.step(request.runId(),
                StepId.tool(request.runId(), invocation)).orElse(null);
        if (existing != null && existing.input() != null && "harness.decision_invalid".equals(
                existing.input().path("phase").asText(""))) {
            return new ToolInvocationResult(
                    persistInvalidDecision(modelStep, invocation, null),
                    Duration.ZERO, ToolExecutionStatus.FAILED);
        }
        if (existing != null && existing.state() == AgentStep.State.COMPLETED) {
            // 成功控制调用的参数可能已脱敏；恢复绑定的结构化记录，不解析不透明文字。
            return recoverCompletedDecision(existing, modelStep, invocation);
        }
        JsonNode arguments;
        ModelDecisionV1 decision;
        try {
            arguments = json.readTree(rawArguments);
            decision = ModelDecisionV1.fromJson(arguments);
            validateUnmetCriterionIds(decision);
            validateEvidenceRefs(decision);
        } catch (Exception invalid) {
            return new ToolInvocationResult(
                    persistInvalidDecision(modelStep, invocation, invalidDecisionFeedback(invalid, rawArguments)),
                    Duration.ZERO, ToolExecutionStatus.FAILED);
        }
        return new ToolInvocationResult(
                persistDecision(modelStep, invocation, decision, arguments),
                Duration.ZERO, ToolExecutionStatus.SUCCEEDED);
    }

    private ToolInvocationResult recoverCompletedDecision(
            AgentStep existing, StepId modelStep, String invocation) {
        String unavailable = "Completed harness decision lacks a complete trusted structured "
                + "record; recovery is limited and provider arguments will not be reconstructed";
        try {
            JsonNode input = existing.input();
            JsonNode output = existing.output();
            if (!existing.turnId().equals(request.runId())
                    || !existing.id().equals(StepId.tool(request.runId(), invocation))
                    || existing.kind() != AgentStep.Kind.ORCHESTRATION
                    || !modelStep.value().equals(existing.causationStepId())
                    || input == null || !input.isObject() || output == null || !output.isObject()
                    || !"harness.decision".equals(input.path("phase").asText(""))
                    || !modelStep.value().equals(input.path("modelStepId").asText(""))
                    || !invocation.equals(input.path("invocationId").asText(""))) {
                throw new IllegalArgumentException();
            }
            List<RunEventEnvelope> submitted = runs.eventsAfter(request.runId(), 0).stream()
                    .filter(event -> event.runId().equals(request.runId().value())
                            && event.type().equals("core.harness.decision_submitted")
                            && event.schemaVersion() == 1
                            && event.producer().equals("framework.springai")
                            && modelStep.value().equals(
                                    event.payload().path("modelStepId").asText("")))
                    .toList();
            if (submitted.size() != 1) throw new IllegalArgumentException();
            RunEventEnvelope event = submitted.getFirst();
            JsonNode record = event.payload();
            if (event.sequence() <= existing.startSequence()
                    || event.sequence() >= existing.lastSequence()
                    || !invocation.equals(record.path("invocationId").asText(""))) {
                throw new IllegalArgumentException();
            }
            ModelDecisionV1 decision = ModelDecisionV1.fromJson(record.path("value"));
            JsonNode normalized = decision.toJson();
            if (!normalized.equals(record.path("value"))
                    || !normalized.path("decision").equals(record.path("decision"))
                    || !normalized.path("userMessage").equals(record.path("userMessage"))
                    || !normalized.path("evidenceRefs").equals(record.path("evidenceRefs"))
                    || !normalized.path("unmetCriterionIds").equals(record.path("unmetCriterionIds"))) {
                throw new IllegalArgumentException();
            }
            // 按同一逐叶脱敏规则比较结构化输入，不恢复任何隐藏文本。
            ModelDecisionV1 persisted = ModelDecisionV1.fromJson(input.path("arguments"));
            ModelDecisionV1 redacted = new ModelDecisionV1(decision.decision(),
                    SensitiveDataRedactor.redactText(decision.userMessage()),
                    decision.evidenceRefs().stream().map(SensitiveDataRedactor::redactText).toList(),
                    decision.unmetCriterionIds().stream().map(SensitiveDataRedactor::redactText).toList());
            JsonNode acknowledgement = JsonNodeFactory.instance.objectNode()
                    .put("accepted", true).put("decision", decision.decision().name());
            if (!persisted.toJson().equals(redacted.toJson())
                    || !ToolExecutionStatus.SUCCEEDED.name().equals(output.path("status").asText(""))
                    || !acknowledgement.equals(output.path("rawOutput"))
                    || !acknowledgement.equals(output.path("modelOutput"))) {
                throw new IllegalArgumentException();
            }
            return new ToolInvocationResult(output.path("modelOutput"),
                    Duration.ZERO, ToolExecutionStatus.SUCCEEDED);
        } catch (Exception invalid) {
            throw new ToolRecoveryRequiredException(existing.id().value(), unavailable);
        }
    }
}
