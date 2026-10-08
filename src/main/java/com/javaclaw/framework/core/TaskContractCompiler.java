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

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Compiles a structured host-capability task contract before Run execution. */
public final class TaskContractCompiler {
    public static final String ATTRIBUTE = "framework.taskContract";
    public static final String ORIGINAL_REQUEST_ATTRIBUTE = "framework.taskOriginalRequest";
    public static final String RESOLVED_REQUEST_ATTRIBUTE = "framework.taskResolvedRequest";
    public static final String VALIDATION_CONTRACT_REFERENCE_ATTRIBUTE = "framework.interaction.validation.contractReference";
    public static final String VALIDATION_CONTRACT_SOURCE_PROPERTY = "javaclaw.interaction.validation.contract-source-run";
    public static final String VALIDATION_CONTRACT_SEQUENCE_PROPERTY = "javaclaw.interaction.validation.contract-sequence";
    public static final String VALIDATION_CONTRACT_SHA256_PROPERTY = "javaclaw.interaction.validation.contract-sha256";
    // Give multi-stage LIGHT plans time to finish without forcing a second call.
    // Both attempts still consume the same shared ceiling and the owner's
    // remaining deadline; a repair never restarts the turn budget.
    private static final Duration MAX_PLANNING_TIME = Duration.ofSeconds(75);
    private static final Duration MAX_PLANNING_ATTEMPT_TIME = Duration.ofSeconds(45);
    private static final Duration MAX_PLANNING_REPAIR_TIME = Duration.ofSeconds(60);
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_HISTORY_MESSAGE_CHARACTERS = 2_000;
    private static final Set<String> BROWSER_INPUT_CAPABILITIES = Set.of(
            "browser.navigate", "browser.click", "browser.double_click", "browser.fill",
            "browser.select", "browser.check", "browser.upload", "browser.type",
            "browser.press_key", "browser.drag", "browser.hover", "browser.scroll",
            "browser.tab_new");
    private static final Set<String> BROWSER_PAGE_INPUT_CAPABILITIES = Set.of(
            "browser.click", "browser.double_click", "browser.fill", "browser.select",
            "browser.check", "browser.upload", "browser.type", "browser.press_key",
            "browser.drag", "browser.hover", "browser.scroll");
    private static final String DESKTOP_SUBJECT_INSTRUCTION =
            "desktop.launch and desktop.open prove only launch admission or session establishment; "
                    + "desktop.snapshot proves only this owned frame was captured and saved, never "
                    + "its logical window content. All three capabilities require an empty "
                    + "requiredSubject. Put requested window content "
                    + "in a separate desktop.observe criterion for the same application with a "
                    + "nonblank requiredSubject. When a later operation depends on values that must first "
                    + "be read from a page or application, those values and the resulting number are "
                    + "not known at planning time. Never fill in remembered, inferred, or guessed "
                    + "operands, identifiers, or final results. Preserve the complete requested dependency "
                    + "and operation in the downstream description and logical requiredSubject: the result "
                    + "of the requested operation on the values actually read from the named sources. "
                    + "Do not delete that dependency, omit the calculation, or replace it with a generic "
                    + "subject such as calculation result. A semantic condition states the goal, not proof "
                    + "that the source values or their computed relationship have been verified. Existing "
                    + "host observation rules still apply; if the available evidence cannot establish "
                    + "the full dependency, execution must remain unverified rather than accept a weaker "
                    + "condition. Clarify an actual missing human choice when needed, but do not classify "
                    + "values discoverable by observation as missing human intent. ";
    private static final String BROWSER_OUTCOME_INSTRUCTION =
            "For browser tasks, distinguish the requested result from a chosen interaction path. "
                    + "Opening a search site to look up information does not require visiting its "
                    + "homepage and typing into a field as separate acceptance criteria. A direct "
                    + "result-page navigation is an equally valid path unless the human explicitly "
                    + "requires those intermediate steps. Preserve explicitly requested actions and "
                    + "their order; never remove them just because a result page is already visible. "
                    + "For a requested lookup, include browser.observe conditions for the actual "
                    + "requested result, not merely admission of navigation or entry of the search "
                    + "query. If only the website is specified and the eventual result URL is not "
                    + "known, use its exact bare host as the observation target (for example "
                    + "www.example.com), without guessing a result path or query parameters. An "
                    + "explicitly requested full URL remains an exact URL target; preserve its query. "
                    + "For newly model-planned existing-page input actions (click, double_click, fill, select, check, "
                    + "upload, type, press_key, drag, hover and scroll), target is the page URL "
                    + "immediately before the input is dispatched, even when that input navigates. "
                    + "Never use an assumed post-input result URL for that input criterion. Keep "
                    + "the human-requested resulting page or content in a separate browser.observe "
                    + "criterion. browser.navigate instead targets its requested destination URL. "
                    + "Previously frozen contracts and explicit definitions retain their declared "
                    + "target phase; a missing phase keeps the returned-page meaning. "
                    + "Every browser input criterion must declare planning-only intentBasis as "
                    + "REQUESTED_ACTION or IMPLEMENTATION_CHOICE. REQUESTED_ACTION requires humanQuote: "
                    + "the shortest exact human wording that directly requests that action. A general "
                    + "lookup goal or a quote of the entire task cannot ground inferred fill, click "
                    + "or other interaction steps. IMPLEMENTATION_CHOICE requires humanQuote=\"\"; "
                    + "the host omits it before freezing acceptance only when a separate "
                    + "browser.observe criterion carries the requested result. Prefer omitting "
                    + "optional steps altogether. Never mark an explicitly requested action optional. ";
    private static final String BROWSER_SUBJECT_INSTRUCTION =
            "Only browser.observe can prove observed page content. All other browser capabilities "
                    + "require an empty requiredSubject; their receipts prove input admission only. "
                    + "A browser.observe requiredSubject is at most 128 characters and must be a "
                    + "short literal text fragment supported by the host observation, not an "
                    + "abstract success claim or a guessed result value. For multiple independently "
                    + "requested text fragments, use requiredTextFragments and requiredSubject=\"\". "
                    + "Do not join separated words into a made-up contiguous phrase. All fragments "
                    + "must appear in the same observed text; at most 8 fragments, 128 characters "
                    + "each, 256 characters total. Copy each fragment exactly from human input; "
                    + "never translate, concatenate or guess future result values or date formatting. "
                    + "A single nonempty requiredSubject must likewise be an exact human text fragment. "
                    + "If the task asks to read a value, identifier, document number, or other content "
                    + "not supplied by the human, do not put its anticipated value into requiredSubject "
                    + "or requiredTextFragments, even when you remember the page. Use only the human's "
                    + "exact visible source anchors, labels, or headings as the literal fragments; "
                    + "keep the requirement to read their actual associated values in the description. "
                    + "Do not invent numeric fragments or a downstream numeric answer before any observation. "
                    + "The fragments prove only literal text, never the complete business meaning. "
                    + "Preserve the complete "
                    + "requested outcome in their descriptions. Search-box values, a search-page "
                    + "title, query text echoed by links, and the URL alone do not establish that "
                    + "the requested answer or result content was read. Never replace the requested "
                    + "result with those weaker conditions merely to obtain a matching receipt. "
                    + "Use the supplied browserObservation date and zone to resolve relative dates "
                    + "without inventing a page's date format or forecast values. ";
    private static final String FILE_TARGET_INSTRUCTION =
            "For FILE criteria, filePaths is the trusted host path context. Relative targets "
                    + "resolve against filePaths.workingDirectory, not projectRoot. Prefer the "
                    + "exact requested filename relative to that directory or its correctly "
                    + "resolved absolute path. Copy every directory, date digit and filename "
                    + "without rewriting or guessing. A target outside an explicit workingDirectory "
                    + "must be an exact file path supplied by human input; projectRoot containment "
                    + "alone does not ground it in the requested task. Copy requested content in "
                    + "requiredSubject with its actual characters and line breaks; JSON-escape once, "
                    + "so parsing restores real line breaks rather than literal backslash+n, unless "
                    + "the human explicitly requested those literal characters. ";
    private static final String KNOWLEDGE_ANSWER_INSTRUCTION =
            "The acceptance capability catalog lists receipt-verifiable outcomes, not every executable tool. "
                    + "The exact host knowledge_search and knowledge_list identities declare idempotent "
                    + "tool.read contracts for answer material. If the goal is only to read existing knowledge "
                    + "and return an answer, including explicitly choosing either tool, return applicable=false, "
                    + "intentStatus=RESOLVED, criteria=[], reasonCodes=[], and unresolvedInputs=[]. Their names "
                    + "are not capability IDs. Do not invent a knowledge receipt criterion or a missing-capability "
                    + "diagnostic merely because those names are absent from this acceptance catalog. Current "
                    + "Run authorization, tool availability, document scope and retrieved facts remain unobserved; "
                    + "runtime tool discovery and execution must check them and report actual failures. This "
                    + "rule does not cover knowledge import, modification, deletion or clearing, real file "
                    + "operations, external actions or live application observations. Those requested outcomes "
                    + "still require applicable=true and the supplied supported acceptance criteria, even when "
                    + "combined with a knowledge question. Answer prose cannot prove those outcomes. ";
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

    /** Local paired validation may reuse a contract only when today's validator preserves it exactly. */
    void validateFrozenV3(RunRequest request, TaskContractV3 contract) {
        if (!contract.applicable() || !contract.reliable() || contract.criteria().isEmpty()
                || !validateV3(request, contract).equals(contract))
            throw new SecurityException("validation contract is invalid or changed under the current plan");
    }

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
        Duration timeout = remainingPlanningTime(started, cancellation, MAX_PLANNING_ATTEMPT_TIME);
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
                    decodeV3(request, resolvedOriginalRequest(request, original, planned), planned, "model"));
        } catch (RunCancelledException | BudgetExceededException terminal) {
            throw terminal;
        } catch (RuntimeException failure) {
            rethrowTerminalCause(failure);
            cancellation.throwIfCancelled();
            contract = TaskContractV3.unknown(original,
                    planningFailureCode(failure, false));
        }
        if (contract.reliable() || needsHuman(contract)) return contract;
        timeout = remainingPlanningTime(started, cancellation, MAX_PLANNING_REPAIR_TIME);
        if (timeout.isZero() || timeout.isNegative())
            return diagnostic(contract, "PLANNING_BUDGET_EXHAUSTED");
        ObjectNode repair = input.deepCopy();
        if (planned != null) repair.set("previousPlan", planned);
        repair.set("validationReasons", json.valueToTree(contract.reasonCodes()));
        repair.set("unresolvedInputs", json.valueToTree(contract.unresolvedInputs()));
        repair.put("repairInstruction", "This is the single read-only contract repair attempt. "
                + "Replan from the human request and supplied host catalog, correcting the previous "
                + "plan's validation failures. No tool has run and no effect or application state "
                + "has been observed. Do not invent authorization, targets, or success. For pure "
                + "response generation, return applicable=false, intentStatus=RESOLVED, criteria=[], "
                + "reasonCodes=[], and unresolvedInputs=[]. " + knowledgeAnswerInstruction()
                + "A filename or path requested as quoted "
                + "answer content or an example is not a real file target. Non-error explanations "
                + "such as NO_ACTION_REQUIRED must not appear in reasonCodes. If actual host actions "
                + "or live observations are requested, preserve their capability/evidence criteria. If the "
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
                + DESKTOP_SUBJECT_INSTRUCTION + BROWSER_OUTCOME_INSTRUCTION
                + BROWSER_SUBJECT_INSTRUCTION + FILE_TARGET_INSTRUCTION + "If human "
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
                    decodeV3(request, resolvedOriginalRequest(request, original, repaired), repaired, "model-repair"));
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

    /** Describes registered host identities only; never asserts current Run availability. */
    private static String knowledgeAnswerInstruction() {
        boolean declaredReads = List.of("knowledge_search", "knowledge_list").stream()
                .allMatch(name -> com.javaclaw.agent.ToolRiskRegistry.matchesHostImplementation(
                        name, com.javaclaw.agent.expert.KnowledgeExpert.class)
                        && com.javaclaw.agent.ToolRiskRegistry.isKnownHostReadOnly(name));
        return declaredReads ? KNOWLEDGE_ANSWER_INSTRUCTION : "";
    }

    private ObjectNode planningInput(RunRequest request, String original) {
        ObjectNode input = json.createObjectNode();
        input.put("request", original);
        input.put("currentUserInput", currentUserInput(request));
        var history = input.putArray("humanHistory");
        humanHistory(request).forEach(history::add);
        input.put("originalRequestExplicit", hasExplicitOriginalRequest(request));
        input.set("capabilities", capabilities.planningCatalog());
        ObjectNode filePaths = input.putObject("filePaths");
        String directory = workDirectory(request);
        filePaths.put("projectRoot", ProjectAccessPolicy.projectRoot().toString());
        filePaths.put("workingDirectoryExplicit", !directory.isBlank());
        try {
            filePaths.put("workingDirectory", ProjectAccessPolicy.withWorkingDirectory(directory,
                    () -> ProjectAccessPolicy.workingDirectory().toString()));
            filePaths.put("status", "VALID");
        } catch (Exception invalidDirectory) {
            filePaths.put("status", "INVALID_WORKING_DIRECTORY");
        }
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
        if (capabilities.find("browser.observe").isPresent()) {
            ZonedDateTime now = ZonedDateTime.now();
            ObjectNode browser = policy.putObject("browserObservation");
            browser.put("localDate", now.toLocalDate().toString());
            browser.put("zoneId", now.getZone().getId());
            browser.put("state", "NOT_OBSERVED");
            browser.put("targetMeaning", "A full URL is exact. A bare host constrains the "
                    + "observed page to that exact host when its final path is not known.");
            browser.put("subjectMeaning", "A short literal fragment from actual requested "
                    + "result content; search-query entry or echo cannot prove a lookup result.");
            browser.put("maximumSubjectCharacters", 128);
            browser.put("maximumTextFragments", 8);
            browser.put("maximumFragmentCharactersTotal", 256);
            browser.put("inputIntentMeaning", "REQUESTED_ACTION needs a shortest direct exact "
                    + "humanQuote; a general lookup does not request inferred fill/click steps. "
                    + "IMPLEMENTATION_CHOICE has an empty humanQuote and is omitted only when "
                    + "independent browser.observe acceptance preserves the result.");
        }
        policy.put("unresolvedInputsMeaning", "Human information needed to define the task; "
                + "never a fact that the supplied host observation capability can discover");
        ObjectNode statuses = policy.putObject("intentStatusMeaning");
        statuses.put("RESOLVED", "The human goal is defined. Requested actions or live observations "
                + "have verifiable conditions supported by host capabilities; initial runtime state "
                + "may still be unknown. Pure response generation is also RESOLVED with applicable=false, "
                + "criteria=[], reasonCodes=[], and unresolvedInputs=[]");
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
                    + "trustedTools names identify its exact host implementation, not additional "
                    + "capability IDs. " + knowledgeAnswerInstruction()
                    + "Internal framework_tool_catalog discovery/activation is a "
                    + "preparatory control step, not a business acceptance criterion. Optional "
                    + "preparation and implementation choices must not be added as mandatory "
                    + "acceptance criteria unless the human explicitly requests that step or its "
                    + "observable result. Preserve the order of explicitly requested steps. "
                    + "Desktop probe, application catalog and target listing use their separate discovery capabilities, "
                    + "never desktop.observe or a guessed window. For desktop.applications, copy the "
                    + "exact requested query to requiredSubject and use target=desktop; this criterion "
                    + "asks for the actual returned page, not proof an application exists, is absent, "
                    + "has a unique identity or is controllable. Preserve requested paging arguments "
                    + "for execution and report hasMore/truncated rather than inventing completeness. "
                    + "For desktop.probe and desktop.targets, target=desktop and requiredSubject is "
                    + "empty; these only read the actual capability state or target list. "
                    + "A capability's evidenceCeiling is the strongest claim its trusted tool can prove. "
                    + "Each criterion's targetType must equal its catalog capability's targetKind. "
                    + "If the request needs an external action or live observation, set applicable=true "
                    + "and list its observable conditions in order. For pure response generation "
                    + "such as explanations, fictional writing or quoted examples, return "
                    + "applicable=false, intentStatus=RESOLVED, criteria=[], reasonCodes=[], and "
                    + "unresolvedInputs=[]. A filename or path used only as requested answer content "
                    + "or a quoted example does not request a real file operation or observation. "
                    + "Do not invent a FILE criterion for such text. Non-error explanations such as "
                    + "NO_ACTION_REQUIRED are not reasonCodes. Explicitly requested real file, application "
                    + "or other host operations still need their supported observable criteria. "
                    + "If the target or observable condition is ambiguous, set "
                    + "intentStatus=NEEDS_HUMAN; do not guess. For an action whose requested outcome must be "
                    + "seen later, include a separate observation capability. Copy target and any "
                    + "view subject from the request in the user's language, without translating it "
                    + "into a guessed English title. requiredSubject is the logical content or view "
                    + "the user requested; it is not a guessed exact visible heading. An explicitly "
                    + "named application's identity can be resolved by host discovery later and "
                    + "does not by itself make the goal ambiguous. Every desktop.observe criterion "
                    + "must include a nonblank requiredSubject for the logical requested content; "
                    + "for a window inspection, name the requested window content. "
                    + DESKTOP_SUBJECT_INSTRUCTION + BROWSER_OUTCOME_INSTRUCTION
                    + BROWSER_SUBJECT_INSTRUCTION + FILE_TARGET_INSTRUCTION + "Use planningPolicy "
                    + "to distinguish missing human intent or authorization from runtime state that "
                    + "the host observation capability can discover. Unknown login status, an existing "
                    + "session, the current displayed account, or window availability does not by "
                    + "itself require human clarification. For applicable host tasks, intentStatus=RESOLVED "
                    + "means the human goal has verifiable conditions supported by host capabilities; "
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

    private static Duration remainingPlanningTime(long started, CancellationToken cancellation,
            Duration attemptCeiling) {
        cancellation.throwIfCancelled();
        Duration local = MAX_PLANNING_TIME.minusNanos(Math.max(0, System.nanoTime() - started));
        Duration owner = cancellation.remaining();
        Duration remaining = local.compareTo(owner) < 0 ? local : owner;
        return remaining.compareTo(attemptCeiling) < 0 ? remaining : attemptCeiling;
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

    private TaskContractV3 decodeV3(RunRequest request, String original, JsonNode value, String source) {
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
            boolean omittedBrowserImplementation = false;
            for (JsonNode item : value.path("criteria")) {
                if (!item.isObject() || !item.path("id").isTextual()
                        || !item.path("description").isTextual() || !item.path("capabilityId").isTextual()
                        || !item.path("targetType").isTextual() || !item.path("target").isTextual()
                        || !item.path("requiredEvidence").isTextual()
                        || item.has("requiredSubject") && !item.path("requiredSubject").isTextual())
                    return TaskContractV3.unknown(original, "INVALID_PLAN");
                TaskCriterionV3 criterion = new TaskCriterionV3(item.path("id").asText(),
                        item.path("description").asText(),
                        item.path("capabilityId").asText(),
                        CapabilityMetadata.TargetKind.valueOf(item.path("targetType").asText()),
                        item.path("target").asText(),
                        com.javaclaw.framework.spi.EffectReceiptV1.Status.valueOf(
                                item.path("requiredEvidence").asText()),
                        item.path("requiredSubject").asText(""), textFragments(item),
                        BROWSER_PAGE_INPUT_CAPABILITIES.contains(item.path("capabilityId").asText())
                                ? TaskCriterionV3.BrowserTargetPhase.INPUT_PAGE
                                : TaskCriterionV3.BrowserTargetPhase.RETURNED_PAGE);
                if (BROWSER_INPUT_CAPABILITIES.contains(criterion.capabilityId())) {
                    String basis = item.path("intentBasis").asText("");
                    if (!item.path("intentBasis").isTextual()
                            || !item.path("humanQuote").isTextual()
                            || !(basis.equals("REQUESTED_ACTION") || basis.equals("IMPLEMENTATION_CHOICE")))
                        return TaskContractV3.unknown(original, "MISSING_BROWSER_ACTION_INTENT");
                    String quote = item.path("humanQuote").asText();
                    if (basis.equals("REQUESTED_ACTION")) {
                        if (!humanContains(request, quote) || quote.length() > 512)
                            return TaskContractV3.unknown(original, "UNGROUNDED_BROWSER_ACTION");
                    } else {
                        if (!quote.isEmpty())
                            return TaskContractV3.unknown(original, "CONFLICTING_BROWSER_ACTION_INTENT");
                        if (!criterion.requiredSubject().isBlank()
                                || !criterion.requiredTextFragments().isEmpty())
                            return TaskContractV3.unknown(original, "UNSUPPORTED_RECEIPT_SUBJECT");
                        // 只移除模型明确标记的实现选择；显式定义和冻结的旧契约不走此路径。
                        omittedBrowserImplementation = true;
                        continue;
                    }
                }
                if (criterion.capabilityId().equals("browser.observe")
                        && (!criterion.requiredSubject().isBlank()
                            && !humanContains(request, criterion.requiredSubject())
                            || criterion.requiredTextFragments().stream()
                                    .anyMatch(fragment -> !humanContains(request, fragment))))
                    return TaskContractV3.unknown(original, "UNGROUNDED_BROWSER_LITERAL");
                criteria.add(criterion);
            }
            if (omittedBrowserImplementation && criteria.stream().noneMatch(criterion ->
                    criterion.capabilityId().equals("browser.observe")
                            && (!criterion.requiredSubject().isBlank()
                                || !criterion.requiredTextFragments().isEmpty())))
                return TaskContractV3.unknown(original, "MISSING_BROWSER_RESULT_CRITERION");
            return new TaskContractV3(3, original, criteria, applicable, reliable, source,
                    diagnostics(value.path("reasonCodes")), diagnostics(value.path("unresolvedInputs")),
                    TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT, intentStatus);
        } catch (RuntimeException invalid) {
            return TaskContractV3.unknown(original, "INVALID_PLAN");
        }
    }

    private static List<String> textFragments(JsonNode criterion) {
        if (!criterion.has("requiredTextFragments")) return List.of();
        JsonNode values = criterion.path("requiredTextFragments");
        if (!values.isArray() || values.size() > 8)
            throw new IllegalArgumentException("invalid browser text fragments");
        List<String> fragments = new ArrayList<>();
        int characters = 0;
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 128)
                throw new IllegalArgumentException("invalid browser text fragment");
            String text = value.asText().strip();
            characters += text.length();
            if (characters > 256 || fragments.contains(text))
                throw new IllegalArgumentException("invalid browser text fragment budget");
            fragments.add(text);
        }
        return List.copyOf(fragments);
    }

    /** 精确人类原文只用于约束模型规划，不能授予工具权限或证明页面结果。 */
    private static boolean humanContains(RunRequest request, String literal) {
        if (literal == null || literal.isBlank()) return false;
        List<String> inputs = new ArrayList<>(humanHistory(request));
        inputs.add(originalRequest(request));
        inputs.add(currentUserInput(request));
        return inputs.stream().anyMatch(text -> text.contains(literal));
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
        String directory = workDirectory(request);
        List<TaskCriterionV3> normalized = new ArrayList<>();
        for (TaskCriterionV3 criterion : contract.criteria()) {
            String target = criterion.target();
            if (criterion.capabilityId().equals("desktop.observe")
                    && (criterion.id().length() > 120 || criterion.requiredSubject().length() > 240))
                invalid.add("UNVERIFIABLE_OBSERVABLE_SUBJECT");
            if (criterion.capabilityId().equals("browser.observe")
                    && (criterion.id().length() > 120 || criterion.requiredSubject().length() > 128))
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
                            () -> {
                                Path resolved = ProjectAccessPolicy.resolveProjectPath(rawTarget);
                                // The working directory is a path base, not a permission boundary.
                                // A model may target another project directory only when that exact
                                // path is grounded in human input, never its own rewritten request.
                                if (contract.source().startsWith("model") && !directory.isBlank()
                                        && !resolved.startsWith(ProjectAccessPolicy.workingDirectory())
                                        && !hasHumanFileTarget(request, resolved)) {
                                    invalid.add("UNGROUNDED_FILE_TARGET");
                                }
                                return resolved.toString();
                            });
                } catch (Exception invalidTarget) {
                    invalid.add("INVALID_FILE_TARGET");
                }
            }
            normalized.add(new TaskCriterionV3(criterion.id(), criterion.description(),
                    criterion.capabilityId(), criterion.targetType(), target,
                    criterion.requiredEvidence(),
                    receiptSubject(criterion, contract, invalid), criterion.requiredTextFragments(),
                    criterion.browserTargetPhase()));
        }
        reasons.addAll(invalid);
        return new TaskContractV3(3, contract.originalRequest(), normalized,
                contract.applicable(), contract.reliable() && reasons.isEmpty(), contract.source(),
                List.copyOf(reasons), contract.unresolvedInputs(), contract.desktopObservationPolicy(),
                invalid.isEmpty() ? contract.intentStatus() : TaskContractV3.IntentStatus.UNKNOWN);
    }

    private static String workDirectory(RunRequest request) {
        return request.attributes().getOrDefault("workDir",
                com.fasterxml.jackson.databind.node.TextNode.valueOf("")).asText("");
    }

    private static boolean hasHumanFileTarget(RunRequest request, Path target) {
        String absolute = target.toString();
        String fromProjectRoot = ProjectAccessPolicy.projectRoot().relativize(target).toString();
        List<String> humanInputs = new ArrayList<>(humanHistory(request));
        humanInputs.add(originalRequest(request));
        humanInputs.add(currentUserInput(request));
        return humanInputs.stream().anyMatch(text -> containsFileLiteral(text, absolute)
                || containsFileLiteral(text, fromProjectRoot));
    }

    private static boolean containsFileLiteral(String text, String path) {
        if (path.isBlank()) return false;
        for (int offset = text.indexOf(path); offset >= 0; offset = text.indexOf(path, offset + 1)) {
            int end = offset + path.length();
            boolean starts = offset == 0 || fileLiteralBoundary(text.charAt(offset - 1));
            boolean ends = end == text.length() || fileLiteralBoundary(text.charAt(end));
            if (starts && ends) return true;
        }
        return false;
    }

    private static boolean fileLiteralBoundary(char value) {
        return Character.isWhitespace(value) || "`\"'“”‘’：:，,；;。！？!?（）()[]{}<>、".indexOf(value) >= 0;
    }

    private String receiptSubject(TaskCriterionV3 criterion, TaskContractV3 contract,
            LinkedHashSet<String> reasons) {
        String subject = criterion.requiredSubject();
        if (criterion.capabilityId().equals("mcp.secure_input.cancel")
                && !subject.matches("[A-Za-z0-9][A-Za-z0-9-]{0,127}")) {
            // A cancellation for another Header cannot establish this interaction.
            reasons.add("INVALID_SECURE_INPUT_SUBJECT");
            return subject;
        }
        if (!subject.isBlank() && (criterion.capabilityId().equals("desktop.click")
                || criterion.capabilityId().equals("desktop.type")
                || criterion.capabilityId().equals("desktop.key")
                || criterion.capabilityId().equals("desktop.scroll"))) {
            // Input receipts prove admission, not a logical control or content subject.
            reasons.add("UNSUPPORTED_RECEIPT_SUBJECT");
            return subject;
        }
        if (!subject.isBlank() && criterion.capabilityId().equals("desktop.snapshot")) {
            // A screenshot cannot establish logical content; do not silently drop a frozen condition.
            reasons.add("UNSUPPORTED_RECEIPT_SUBJECT");
            return subject;
        }
        if (!subject.isBlank() && BROWSER_INPUT_CAPABILITIES.contains(criterion.capabilityId())) {
            // Browser input admission cannot establish a requested page-content condition.
            // Reject the plan for repair rather than silently dropping the frozen requirement.
            reasons.add("UNSUPPORTED_RECEIPT_SUBJECT");
            return subject;
        }
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
                .put("description", DESKTOP_SUBJECT_INSTRUCTION + BROWSER_SUBJECT_INSTRUCTION);
        ObjectNode fragments = fields.putObject("requiredTextFragments").put("type", "array")
                .put("maxItems", 8).put("uniqueItems", true)
                .put("description", "browser.observe only: independent exact human text fragments "
                        + "that must all occur in the same observed body, at most 256 characters "
                        + "total. Use this instead of joining separated words into requiredSubject. "
                        + "Each fragment proves literal text only; preserve the full business "
                        + "result in description. Other capabilities must omit this field or use [].");
        fragments.putObject("items").put("type", "string").put("minLength", 1).put("maxLength", 128);
        fields.putObject("intentBasis").put("type", "string")
                .put("description", "Planning-only classification. Every browser input criterion "
                        + "must distinguish REQUESTED_ACTION, directly requested by the human, "
                        + "from IMPLEMENTATION_CHOICE, an optional interaction path. "
                        + "browser.observe may use REQUESTED_RESULT. This never grants authority.")
                .putArray("enum").add("REQUESTED_ACTION").add("IMPLEMENTATION_CHOICE").add("REQUESTED_RESULT");
        fields.putObject("humanQuote").put("type", "string").put("maxLength", 512)
                .put("description", "For REQUESTED_ACTION browser input, the shortest exact "
                        + "human quote directly requesting that action. A vague lookup goal cannot "
                        + "ground inferred fill/click steps; do not quote the whole task as a "
                        + "substitute. For IMPLEMENTATION_CHOICE this must be empty. Other "
                        + "capabilities may omit it.");
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
