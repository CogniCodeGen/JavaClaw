package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.framework.spi.RunCancelledException;
import com.javaclaw.util.ProjectAccessPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;

/** Compiles a structured host-capability task contract before Run execution. */
public final class TaskContractCompiler {
    public static final String ATTRIBUTE = "framework.taskContract";
    public static final String ORIGINAL_REQUEST_ATTRIBUTE = "framework.taskOriginalRequest";
    public static final String RESOLVED_REQUEST_ATTRIBUTE = "framework.taskResolvedRequest";
    // Give both bounded attempts a useful response window. The shared ceiling and
    // the owner's deadline still apply, so repair cannot restart the turn budget.
    private static final Duration MAX_PLANNING_TIME = Duration.ofSeconds(30);
    private static final Duration MAX_PLANNING_ATTEMPT_TIME = Duration.ofSeconds(15);
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_HISTORY_MESSAGE_CHARACTERS = 2_000;
    private static final String DESKTOP_SUBJECT_INSTRUCTION =
            "desktop.launch and desktop.open prove only launch admission or session establishment; "
                    + "their requiredSubject must be an empty string. Put requested window content "
                    + "in a separate desktop.observe criterion for the same application with a "
                    + "nonblank requiredSubject. ";
    private final ModelTaskGateway models;
    private final ObjectMapper json;
    private final TrustedCapabilityRegistry capabilities;

    public TaskContractCompiler(ModelTaskGateway models, ObjectMapper json) {
        this(models, json, TrustedCapabilityRegistry.builtins());
    }

    public TaskContractCompiler(ModelTaskGateway models, ObjectMapper json,
            TrustedCapabilityRegistry capabilities) {
        this.models = models;
        this.json = Objects.requireNonNull(json, "json");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    TrustedCapabilityRegistry capabilities() { return capabilities; }

    /** Compile only declared host capabilities; no localized keyword fallback can grant proof. */
    public TaskContractV3 compileV3(RunId runId, RunRequest request, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String original = originalRequest(request);
        JsonNode explicit = request.attributes().get(ATTRIBUTE);
        if (explicit != null) {
            if (explicit.path("version").asInt(0) != 3)
                return TaskContractV3.unknown(original, "UNSUPPORTED_CONTRACT_VERSION");
            try {
                TaskContractV3 definition = json.treeToValue(explicit, TaskContractV3.class);
                return validateV3(request, new TaskContractV3(3, original,
                        definition.criteria(), definition.applicable(),
                        definition.reliable(), "definition", definition.reasonCodes(),
                        definition.unresolvedInputs(), definition.desktopObservationPolicy(),
                        definition.intentStatus()));
            } catch (Exception invalid) {
                return TaskContractV3.unknown(original, "INVALID_CONTRACT_DEFINITION");
            }
        }
        if (original.isBlank()) return TaskContractV3.unknown(original, "NO_REQUEST");
        if (models == null) return TaskContractV3.unknown(original, "MODEL_GATEWAY_UNAVAILABLE");
        if (managedCoordinator(request)) return TaskContractV3.unknown(original, "MANAGED_COORDINATOR");
        long started = System.nanoTime();
        Duration timeout = remainingPlanningTime(started, cancellation);
        if (timeout.isZero() || timeout.isNegative())
            return TaskContractV3.unknown(original, "PLANNING_BUDGET_EXHAUSTED");
        ObjectNode input = planningInput(request, original);
        JsonNode planned = null;
        TaskContractV3 contract;
        try {
            planned = models.executeInline(new ModelTaskRequest(
                    "task.contract.plan.v3", ModelTier.LIGHT, input, List.of(),
                    schemaV3(), runId, "task-contract", timeout, 0,
                    cancellation, false)).output();
            cancellation.throwIfCancelled();
            contract = validateV3(request,
                    decodeV3(resolvedOriginalRequest(request, original, planned), planned, "model"));
        } catch (RunCancelledException | BudgetExceededException terminal) {
            throw terminal;
        } catch (RuntimeException failure) {
            rethrowTerminalCause(failure);
            cancellation.throwIfCancelled();
            contract = TaskContractV3.unknown(original,
                    planningFailureCode(failure, false));
        }
        if (contract.reliable() || needsHuman(contract)) return contract;
        timeout = remainingPlanningTime(started, cancellation);
        if (timeout.isZero() || timeout.isNegative())
            return diagnostic(contract, "PLANNING_BUDGET_EXHAUSTED");
        ObjectNode repair = input.deepCopy();
        if (planned != null) repair.set("previousPlan", planned);
        repair.set("validationReasons", json.valueToTree(contract.reasonCodes()));
        repair.set("unresolvedInputs", json.valueToTree(contract.unresolvedInputs()));
        repair.put("repairInstruction", "This is the single read-only contract repair attempt. "
                + "Replan from the human request and supplied host catalog, correcting the previous "
                + "plan's validation failures. No tool has run and no effect or application state "
                + "has been observed. Do not invent authorization, targets, or success. If the "
                + "human request has enough information to define observable acceptance criteria, "
                + "return intentStatus=RESOLVED; discovery can resolve an explicitly named application's "
                + "identity later. Reclassify login status, the existing session, the current account "
                + "displayed by an application, and window availability as runtime state to discover "
                + "when the supplied host catalog provides an observation capability, not missing "
                + "human input. Never infer "
                + "that the application is logged in or that a particular account is active. If the "
                + "user asks for a particular account but does not identify it, or a needed decision "
                + "or authorization is actually absent, preserve that ambiguity. Every desktop.observe "
                + "criterion must specify the requested logical content in requiredSubject. "
                + DESKTOP_SUBJECT_INSTRUCTION + "If human "
                + "information is still missing, return intentStatus=NEEDS_HUMAN with specific "
                + "reasonCodes and unresolvedInputs. Write each unresolvedInputs item as a specific "
                + "clarification question in the human request's language. If required capabilities are absent, return "
                + "intentStatus=UNSUPPORTED with concrete reasonCodes. Do not mark intent resolved "
                + "merely to pass validation.");
        try {
            JsonNode repaired = models.executeInline(new ModelTaskRequest(
                    "task.contract.repair.v3", ModelTier.NORMAL, repair, List.of(),
                    schemaV3(), runId, "task-contract", timeout, 0,
                    cancellation, false)).output();
            cancellation.throwIfCancelled();
            TaskContractV3 result = validateV3(request,
                    decodeV3(resolvedOriginalRequest(request, original, repaired), repaired, "model-repair"));
            return result.reliable() || needsHuman(result)
                    ? result : diagnostic(result, "PLAN_REPAIR_EXHAUSTED");
        } catch (RunCancelledException | BudgetExceededException terminal) {
            throw terminal;
        } catch (RuntimeException failure) {
            rethrowTerminalCause(failure);
            cancellation.throwIfCancelled();
            return diagnostic(contract, planningFailureCode(failure, true));
        }
    }

    private ObjectNode planningInput(RunRequest request, String original) {
        ObjectNode input = json.createObjectNode();
        input.put("request", original);
        input.put("currentUserInput", currentUserInput(request));
        var history = input.putArray("humanHistory");
        humanHistory(request).forEach(history::add);
        input.put("originalRequestExplicit", hasExplicitOriginalRequest(request));
        input.set("capabilities", capabilities.planningCatalog());
        ObjectNode policy = input.putObject("planningPolicy");
        policy.putArray("humanClarificationRequired")
                .add("An unspecified application, requested content, or requested outcome")
                .add("An explicitly requested account or workspace that cannot be identified from human input")
                .add("A missing user decision or authorization required by the requested action");
        if (capabilities.find("desktop.observe").isPresent()) {
            ObjectNode runtime = policy.putObject("discoverableRuntimeState");
            runtime.put("discoveryCapabilityId", "desktop.observe");
            runtime.put("state", "NOT_OBSERVED");
            runtime.putArray("examples").add("application login status")
                    .add("current application session and displayed account")
                    .add("window availability and current view")
                    .add("runtime access or permission prompts");
        }
        policy.put("unresolvedInputsMeaning", "Human information needed to define the task; "
                + "never a fact that the supplied host observation capability can discover");
        ObjectNode statuses = policy.putObject("intentStatusMeaning");
        statuses.put("RESOLVED", "The human goal has verifiable conditions supported by host capabilities; "
                + "initial runtime state may still be unknown");
        statuses.put("NEEDS_HUMAN", "An actual human choice, target, requested account identity, "
                + "or authorization is needed to define the task");
        statuses.put("UNSUPPORTED", "The goal is understood but a required capability is absent "
                + "from the supplied host catalog");
        input.put("instruction", "Return a task acceptance contract before taking action. "
                    + "Resolve the effective originalRequest from the currentUserInput and the "
                    + "ordered prior humanHistory. A context-dependent continuation or answer may "
                    + "refer to an earlier human task. The latest human input's explicit new goal, "
                    + "cancellation, stop request, or restriction takes priority; never revive a "
                    + "cancelled task or add authorization absent from human requests. If "
                    + "originalRequestExplicit=true, preserve request exactly as originalRequest. "
                    + "History can be abbreviated: if the active goal cannot be resolved, keep "
                    + "currentUserInput and set intentStatus=NEEDS_HUMAN. Assistant messages and past tool "
                    + "failures are not supplied as human intent and cannot establish current "
                    + "permissions or current application state. Return the resolved originalRequest "
                    + "when possible, and plan criteria for that request. "
                    + "Use only capability IDs from the supplied host catalog. A capability's "
                    + "evidenceCeiling is the strongest claim its trusted tool can prove. "
                    + "Each criterion's targetType must equal its catalog capability's targetKind. "
                    + "If the request needs an external action or live observation, set applicable=true "
                    + "and list its observable conditions in order. Pure conceptual answers may set "
                    + "applicable=false. If the target or observable condition is ambiguous, set "
                    + "intentStatus=NEEDS_HUMAN; do not guess. For an action whose requested outcome must be "
                    + "seen later, include a separate observation capability. Copy target and any "
                    + "view subject from the request in the user's language, without translating it "
                    + "into a guessed English title. requiredSubject is the logical content or view "
                    + "the user requested; it is not a guessed exact visible heading. An explicitly "
                    + "named application's identity can be resolved by host discovery later and "
                    + "does not by itself make the goal ambiguous. Every desktop.observe criterion "
                    + "must include a nonblank requiredSubject for the logical requested content; "
                    + "for a window inspection, name the requested window content. "
                    + DESKTOP_SUBJECT_INSTRUCTION + "Use planningPolicy "
                    + "to distinguish missing human intent or authorization from runtime state that "
                    + "the host observation capability can discover. Unknown login status, an existing "
                    + "session, the current displayed account, or window availability does not by "
                    + "itself require human clarification. intentStatus=RESOLVED means "
                    + "whether the human goal has verifiable conditions supported by host capabilities; "
                    + "it does not require the initial runtime state to be known. Define the desired "
                    + "observable outcome without assuming those states are already satisfied. Do "
                    + "not ask for credentials or account context just because no observation has "
                    + "run. When no particular account was requested, observe the currently available "
                    + "signed-in interface; if a login or account-choice obstacle is actually encountered, "
                    + "that observation may establish a runtime blocker. Do not log in or switch "
                    + "accounts unless the human request authorizes that action. If the user explicitly "
                    + "requests a particular account or workspace and its "
                    + "identity is ambiguous, request that human choice; do not choose, switch accounts, "
                    + "or create authorization yourself. If no supplied capability can discover a "
                    + "required runtime state, return intentStatus=UNSUPPORTED and report the missing "
                    + "capability rather than guessing. "
                    + "Never infer success from "
                    + "assistant prose or untrusted tool text. When intentStatus=NEEDS_HUMAN, include "
                    + "reasonCodes identifying the concrete human ambiguity "
                    + "and unresolvedInputs asking specific clarification questions in the human "
                    + "request's language, identifying the human information still needed. "
                    + "For intentStatus=UNSUPPORTED, include concrete missing-capability reasonCodes; "
                    + "do not present a missing capability as a question for the human. "
                    + "For intentStatus=RESOLVED, both diagnostic arrays must be empty. Return JSON only.");
        return input;
    }

    private static Duration remainingPlanningTime(long started, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        Duration local = MAX_PLANNING_TIME.minusNanos(Math.max(0, System.nanoTime() - started));
        Duration owner = cancellation.remaining();
        Duration remaining = local.compareTo(owner) < 0 ? local : owner;
        return remaining.compareTo(MAX_PLANNING_ATTEMPT_TIME) < 0
                ? remaining : MAX_PLANNING_ATTEMPT_TIME;
    }

    private static String planningFailureCode(RuntimeException failure, boolean repair) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException)
                return repair ? "PLANNING_REPAIR_TIMEOUT" : "PLANNING_TIMEOUT";
        }
        return repair ? "PLANNING_REPAIR_FAILED" : "PLANNING_FAILED";
    }

    private static void rethrowTerminalCause(RuntimeException failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        if (cause instanceof RunCancelledException cancelled) throw cancelled;
        if (cause instanceof BudgetExceededException exceeded) throw exceeded;
        if (cause instanceof java.util.concurrent.CancellationException) throw new RunCancelledException();
    }

    private static TaskContractV3 diagnostic(TaskContractV3 contract, String code) {
        List<String> reasons = new ArrayList<>(contract.reasonCodes());
        reasons.add(code);
        return new TaskContractV3(3, contract.originalRequest(), contract.criteria(),
                contract.applicable(), false, contract.source(), reasons, contract.unresolvedInputs(),
                contract.desktopObservationPolicy(), contract.intentStatus());
    }

    /** 仅把经过结构及能力校验的模型澄清结果交给人类，不通过文字关键词推断意图。 */
    private static boolean needsHuman(TaskContractV3 contract) {
        return contract.intentStatus() == TaskContractV3.IntentStatus.NEEDS_HUMAN
                && !contract.reliable() && !contract.unresolvedInputs().isEmpty()
                && (contract.source().equals("model") || contract.source().equals("model-repair"));
    }

    private TaskContractV3 decodeV3(String original, JsonNode value, String source) {
        if (value == null || !value.isObject()) return TaskContractV3.unknown(original, "INVALID_PLAN");
        try {
            if (!value.path("applicable").isBoolean()
                    || !value.path("criteria").isArray()
                    || !validDiagnostics(value, "reasonCodes", 16, 64, true)
                    || !validDiagnostics(value, "unresolvedInputs", 12, 512, false))
                return TaskContractV3.unknown(original, "INVALID_PLAN");
            boolean applicable = value.path("applicable").asBoolean(true);
            boolean reliable = modelPlanReliable(value);
            TaskContractV3.IntentStatus intentStatus = value.has("intentStatus")
                    ? TaskContractV3.IntentStatus.valueOf(value.path("intentStatus").asText())
                    : TaskContractV3.IntentStatus.UNKNOWN;
            List<TaskCriterionV3> criteria = new ArrayList<>();
            for (JsonNode item : value.path("criteria")) {
                if (!item.isObject() || !item.path("id").isTextual()
                        || !item.path("description").isTextual() || !item.path("capabilityId").isTextual()
                        || !item.path("targetType").isTextual() || !item.path("target").isTextual()
                        || !item.path("requiredEvidence").isTextual()
                        || item.has("requiredSubject") && !item.path("requiredSubject").isTextual())
                    return TaskContractV3.unknown(original, "INVALID_PLAN");
                criteria.add(new TaskCriterionV3(item.path("id").asText(),
                        item.path("description").asText(),
                        item.path("capabilityId").asText(),
                        CapabilityMetadata.TargetKind.valueOf(item.path("targetType").asText()),
                        item.path("target").asText(),
                        com.javaclaw.framework.spi.EffectReceiptV1.Status.valueOf(
                                item.path("requiredEvidence").asText()),
                        item.path("requiredSubject").asText("")));
            }
            return new TaskContractV3(3, original, criteria, applicable, reliable, source,
                    diagnostics(value.path("reasonCodes")), diagnostics(value.path("unresolvedInputs")),
                    TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT, intentStatus);
        } catch (RuntimeException invalid) {
            return TaskContractV3.unknown(original, "INVALID_PLAN");
        }
    }

    /** One strict intent interpretation for both contract decoding and history-based goal resolution. */
    private static boolean modelPlanReliable(JsonNode value) {
        if (!validDiagnostics(value, "reasonCodes", 16, 64, true)
                || !validDiagnostics(value, "unresolvedInputs", 12, 512, false))
            throw new IllegalArgumentException("invalid plan diagnostics");
        if (!value.has("intentStatus")) {
            // Old model protocols keep their original reliability meaning.
            if (!value.path("reliable").isBoolean())
                throw new IllegalArgumentException("missing legacy reliability");
            return value.path("reliable").asBoolean();
        }
        if (!value.path("intentStatus").isTextual())
            throw new IllegalArgumentException("invalid intent status");
        boolean reasonsPresent = !diagnostics(value.path("reasonCodes")).isEmpty();
        boolean humanInputsMissing = diagnostics(value.path("unresolvedInputs")).stream()
                .anyMatch(input -> !input.isBlank());
        boolean reliable = switch (value.path("intentStatus").asText()) {
            case "RESOLVED" -> {
                if (reasonsPresent || !diagnostics(value.path("unresolvedInputs")).isEmpty())
                    throw new IllegalArgumentException("resolved intent has unresolved diagnostics");
                yield true;
            }
            case "NEEDS_HUMAN" -> {
                if (!reasonsPresent || !humanInputsMissing)
                    throw new IllegalArgumentException("human clarification needs specific missing inputs");
                yield false;
            }
            case "UNSUPPORTED" -> {
                if (!reasonsPresent)
                    throw new IllegalArgumentException("unsupported intent needs a reason");
                yield false;
            }
            default -> throw new IllegalArgumentException("unsupported intent status");
        };
        if (value.has("reliable") && (!value.path("reliable").isBoolean()
                || value.path("reliable").asBoolean() != reliable))
            throw new IllegalArgumentException("conflicting legacy reliability");
        return reliable;
    }

    private static boolean validDiagnostics(JsonNode value, String key, int limit,
            int maxLength, boolean codes) {
        if (!value.has(key)) return true;
        JsonNode values = value.path(key);
        if (!values.isArray() || values.size() > limit) return false;
        for (JsonNode item : values) {
            if (!item.isTextual() || item.asText().length() > maxLength
                    || codes && !item.asText().matches("[A-Z][A-Z0-9_]{0,63}")) return false;
        }
        return true;
    }

    private static List<String> diagnostics(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) for (JsonNode item : values) {
            if (item.isTextual()) result.add(item.asText());
            if (result.size() == 16) break;
        }
        return result;
    }

    private TaskContractV3 validateV3(RunRequest request, TaskContractV3 contract) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>(contract.reasonCodes());
        LinkedHashSet<String> invalid = new LinkedHashSet<>();
        boolean clarification = needsHuman(contract);
        if (!contract.reliable() && !clarification) reasons.add(contract.source().startsWith("model")
                ? "MODEL_UNRELIABLE" : "CONTRACT_UNRELIABLE");
        if (!contract.unresolvedInputs().isEmpty()) reasons.add("UNRESOLVED_INPUTS");
        if (!contract.desktopObservationSubjectsValid()) invalid.add("MISSING_OBSERVABLE_SUBJECT");
        if (contract.applicable() && contract.criteria().isEmpty() && !clarification) invalid.add("EMPTY_CRITERIA");
        String directory = request.attributes().getOrDefault("workDir",
                com.fasterxml.jackson.databind.node.TextNode.valueOf("")).asText("");
        List<TaskCriterionV3> normalized = new ArrayList<>();
        for (TaskCriterionV3 criterion : contract.criteria()) {
            String target = criterion.target();
            if (criterion.capabilityId().equals("desktop.observe")
                    && (criterion.id().length() > 120 || criterion.requiredSubject().length() > 240))
                invalid.add("UNVERIFIABLE_OBSERVABLE_SUBJECT");
            var descriptor = capabilities.find(criterion.capabilityId()).orElse(null);
            if (descriptor == null) invalid.add("UNSUPPORTED_CAPABILITY");
            else {
                if (!descriptor.targetKind().name().equals(criterion.targetType().name()))
                    invalid.add("TARGET_KIND_MISMATCH");
                if (TrustedCapabilityRegistry.rank(criterion.requiredEvidence())
                        > TrustedCapabilityRegistry.rank(descriptor.evidenceCeiling()))
                    invalid.add("EVIDENCE_CEILING_EXCEEDED");
            }
            if (descriptor != null && descriptor.targetKind()
                    == TrustedCapabilityRegistry.TargetKind.FILE) {
                try {
                    String rawTarget = target;
                    target = ProjectAccessPolicy.withWorkingDirectory(directory,
                            () -> ProjectAccessPolicy.resolveProjectPath(rawTarget).toString());
                } catch (Exception invalidTarget) {
                    invalid.add("INVALID_FILE_TARGET");
                }
            }
            normalized.add(new TaskCriterionV3(criterion.id(), criterion.description(),
                    criterion.capabilityId(), criterion.targetType(), target,
                    criterion.requiredEvidence(),
                    receiptSubject(criterion, contract, invalid)));
        }
        reasons.addAll(invalid);
        return new TaskContractV3(3, contract.originalRequest(), normalized,
                contract.applicable(), contract.reliable() && reasons.isEmpty(), contract.source(),
                List.copyOf(reasons), contract.unresolvedInputs(), contract.desktopObservationPolicy(),
                invalid.isEmpty() ? contract.intentStatus() : TaskContractV3.IntentStatus.UNKNOWN);
    }

    private String receiptSubject(TaskCriterionV3 criterion, TaskContractV3 contract,
            LinkedHashSet<String> reasons) {
        String subject = criterion.requiredSubject();
        if (subject.isBlank() || !(criterion.capabilityId().equals("desktop.launch")
                || criterion.capabilityId().equals("desktop.open"))) return subject;
        // 启动和建立会话不证明界面内容；模型标签只能由同一应用的独立观察条件承接。
        boolean observedSeparately = contract.criteria().stream().anyMatch(observation ->
                observation.capabilityId().equals("desktop.observe")
                        && observation.targetType() == CapabilityMetadata.TargetKind.DESKTOP_APPLICATION
                        && observation.target().equalsIgnoreCase(criterion.target())
                        && !observation.requiredSubject().isBlank()
                        && capabilities.supports(observation));
        if ((contract.source().equals("model") || contract.source().equals("model-repair"))
                && observedSeparately) return "";
        // 显式定义保留原要求，并在执行前拒绝无法由该能力证明的内容条件。
        reasons.add("UNSUPPORTED_RECEIPT_SUBJECT");
        return subject;
    }

    private JsonNode schemaV3() {
        ObjectNode criterion = json.createObjectNode().put("type", "object");
        ObjectNode fields = criterion.putObject("properties");
        fields.putObject("id").put("type", "string").put("maxLength", 120);
        fields.putObject("description").put("type", "string");
        ObjectNode capability = fields.putObject("capabilityId").put("type", "string");
        var ids = capability.putArray("enum");
        capabilities.planningCatalog().forEach(item -> ids.add(item.path("id").asText()));
        var targetTypes = fields.putObject("targetType").put("type", "string")
                .putArray("enum");
        for (CapabilityMetadata.TargetKind kind : CapabilityMetadata.TargetKind.values()) {
            targetTypes.add(kind.name());
        }
        fields.putObject("target").put("type", "string");
        fields.putObject("requiredEvidence").put("type", "string")
                .putArray("enum").add("ACCEPTED").add("OBSERVED").add("VERIFIED");
        fields.putObject("requiredSubject").put("type", "string").put("maxLength", 240)
                .put("description", DESKTOP_SUBJECT_INSTRUCTION);
        criterion.putArray("required").add("id").add("description")
                .add("capabilityId").add("targetType").add("target")
                .add("requiredEvidence").add("requiredSubject");
        criterion.put("additionalProperties", false);
        ObjectNode schema = json.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("originalRequest").put("type", "string")
                .put("minLength", 1).put("maxLength", 12_000)
                .put("description", "Effective task resolved from human history and the current "
                        + "human input, honoring the latest new goal, cancellation and restrictions. "
                        + "Preserve request when originalRequestExplicit is true.");
        properties.putObject("applicable").put("type", "boolean");
        properties.putObject("intentStatus").put("type", "string")
                .putArray("enum").add("RESOLVED").add("NEEDS_HUMAN").add("UNSUPPORTED");
        ObjectNode reasons = properties.putObject("reasonCodes").put("type", "array")
                .put("maxItems", 16).put("uniqueItems", true);
        reasons.putObject("items").put("type", "string")
                .put("pattern", "^[A-Z][A-Z0-9_]{0,63}$");
        ObjectNode unresolved = properties.putObject("unresolvedInputs").put("type", "array")
                .put("maxItems", 12).put("uniqueItems", true);
        unresolved.putObject("items").put("type", "string").put("maxLength", 512);
        properties.putObject("criteria").put("type", "array")
                .put("maxItems", 12).set("items", criterion);
        schema.putArray("required").add("applicable").add("intentStatus").add("criteria");
        schema.put("additionalProperties", false);
        return schema;
    }

    public static String originalRequest(RunRequest request) {
        JsonNode explicit = request.attributes().get(ORIGINAL_REQUEST_ATTRIBUTE);
        if (explicit != null && explicit.isTextual() && !explicit.asText().isBlank()) {
            return explicit.asText();
        }
        return currentUserInput(request);
    }

    public static String currentUserInput(RunRequest request) {
        List<String> current = new ArrayList<>();
        for (InputBlock block : request.inputs()) {
            if (block.type().equals("core.text")) current.add(block.data().path("text").asText(""));
        }
        return String.join("\n", current);
    }

    private static boolean hasExplicitOriginalRequest(RunRequest request) {
        JsonNode explicit = request.attributes().get(ORIGINAL_REQUEST_ATTRIBUTE);
        return explicit != null && explicit.isTextual() && !explicit.asText().isBlank();
    }

    private static List<String> humanHistory(RunRequest request) {
        List<String> messages = new ArrayList<>();
        for (InputBlock block : request.inputs()) {
            if (!block.type().equals("core.message")
                    || !block.data().path("role").asText("").equals("user")) continue;
            String text = block.data().path("text").asText("");
            if (text.isBlank()) continue;
            messages.add(text.length() <= MAX_HISTORY_MESSAGE_CHARACTERS ? text
                    : text.substring(0, MAX_HISTORY_MESSAGE_CHARACTERS) + "\n[history abbreviated]");
        }
        return List.copyOf(messages.subList(
                Math.max(0, messages.size() - MAX_HISTORY_MESSAGES), messages.size()));
    }

    private static String resolvedOriginalRequest(RunRequest request, String original, JsonNode planned) {
        if (hasExplicitOriginalRequest(request) || humanHistory(request).isEmpty()
                || planned == null) return original;
        try {
            if (!modelPlanReliable(planned)) return original;
        } catch (RuntimeException invalid) {
            return original;
        }
        JsonNode resolved = planned.path("originalRequest");
        return resolved.isTextual() && !resolved.asText().isBlank()
                && resolved.asText().length() <= 12_000 ? resolved.asText() : original;
    }

    private static boolean managedCoordinator(RunRequest request) {
        return request.attributes().getOrDefault("framework.managed",
                com.fasterxml.jackson.databind.node.BooleanNode.FALSE).asBoolean()
                || request.attributes().getOrDefault("framework.maintenance",
                com.fasterxml.jackson.databind.node.BooleanNode.FALSE).asBoolean();
    }

}
