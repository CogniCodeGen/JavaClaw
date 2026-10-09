package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.ModelDecisionV1;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.framework.api.TaskStopReason;
import com.javaclaw.framework.api.TurnPausedException;
import com.javaclaw.framework.core.CancellableTaskStages;
import com.javaclaw.framework.core.ReasoningGateway;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.ReasoningResult;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.RunUsageLedger;
import com.javaclaw.framework.core.TaskEvidenceCollector;
import com.javaclaw.framework.core.TaskResultEvaluator;
import com.javaclaw.framework.core.TrustedCapabilityRegistry;
import com.javaclaw.framework.core.ToolApprovalRequiredException;
import com.javaclaw.framework.core.ToolGroupAccess;
import com.javaclaw.framework.core.ToolInputRequiredException;
import com.javaclaw.framework.core.ToolInvocationGateway;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.core.PendingEffectObservationRequiredException;
import com.javaclaw.framework.spi.AdvisorRuntimeContext;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.spi.ExtensionStateStore;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.RetryContext;
import com.javaclaw.framework.spi.RetryDirective;
import com.javaclaw.framework.spi.RunCancelledException;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ToolContext;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.model.tool.ToolCallingManager;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.javaclaw.framework.springai.ReasoningGatewaySupport.addProjectionStatistics;
import static com.javaclaw.framework.springai.ReasoningGatewaySupport.closeTools;
import static com.javaclaw.framework.springai.ReasoningGatewaySupport.providerMessages;
import static com.javaclaw.framework.springai.ReasoningGatewaySupport.token;
import static com.javaclaw.framework.springai.ReasoningGatewaySupport.unwrap;
import static com.javaclaw.framework.springai.ReasoningGatewaySupport.withoutSystemMessages;

/** Spring AI 2.0 implementation of the one ReAct reasoning loop. */
public final class SpringAiReasoningGateway implements ReasoningGateway {
    public static final int TOOL_CALLING_ADVISOR_COUNT = 1;
    private final SpringAiModelRegistry models;
    private final SpringAiAdvisorRegistry advisorRegistry;
    private final ToolInvocationGateway tools;
    private final ExtensionStateStore extensionState;
    private final RunUsageLedger usageLedger;
    private final ModelTaskGateway modelTasks;
    private final RunStore runStore;
    private final ObjectMapper json;
    private final CancellableTaskExecutor executor;
    private final ObservationRegistry observations;
    private final SpringAiPromptFactory prompts;
    private final com.javaclaw.framework.api.InteractionHistoryClient interactionHistory;
    private final TrustedCapabilityRegistry capabilities = TrustedCapabilityRegistry.builtins();

    public SpringAiReasoningGateway(
            SpringAiModelRegistry models,
            SpringAiAdvisorRegistry advisorRegistry,
            ToolInvocationGateway tools,
            ExtensionStateStore extensionState,
            RunUsageLedger usageLedger,
            ModelTaskGateway modelTasks,
            RunStore runStore,
            ObjectMapper json,
            CancellableTaskExecutor executor,
            ObservationRegistry observations) {
        this(models, advisorRegistry, tools, extensionState, usageLedger, modelTasks, runStore, json,
                executor, observations, null);
    }

    public SpringAiReasoningGateway(SpringAiModelRegistry models, SpringAiAdvisorRegistry advisorRegistry,
            ToolInvocationGateway tools, ExtensionStateStore extensionState, RunUsageLedger usageLedger,
            ModelTaskGateway modelTasks, RunStore runStore, ObjectMapper json, CancellableTaskExecutor executor,
            ObservationRegistry observations, com.javaclaw.framework.api.InteractionHistoryClient interactionHistory) {
        this.models = Objects.requireNonNull(models, "models");
        this.advisorRegistry = Objects.requireNonNull(advisorRegistry, "advisorRegistry");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.extensionState = Objects.requireNonNull(extensionState, "extensionState");
        this.usageLedger = Objects.requireNonNull(usageLedger, "usageLedger");
        this.modelTasks = Objects.requireNonNull(modelTasks, "modelTasks");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.json = Objects.requireNonNull(json, "json");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.prompts = new SpringAiPromptFactory(extensionState);
        this.interactionHistory = interactionHistory;
    }

    @Override
    public CompletionStage<ReasoningResult> execute(ReasoningRequest request) {
        return CancellableTaskStages.submit(executor, "primary-model-" + request.runId(),
                request.control().remaining(), request.control(), () -> reason(request));
    }

    /** Startup diagnostic invariant; extension advisors are checked separately. */
    public int toolCallingAdvisorCountPerPlan() {
        return TOOL_CALLING_ADVISOR_COUNT;
    }

    private ReasoningResult reason(ReasoningRequest request) {
        try {
            request.control().throwIfCancelled();
            AtomicInteger currentAttempt = new AtomicInteger(1);
            AtomicInteger responseIndex = new AtomicInteger();
            ModelStepJournal journal = new ModelStepJournal(request, runStore, json);
            HarnessDecisionToolCallback decisionCallback = journal.decisionCallback();
            StepContextProjector projector = new StepContextProjector(
                    request.plan().descriptor().stepContextPolicy(), json);
            ProviderContextBoundary boundary = new ProviderContextBoundary();
            List<FrameworkTool> runTools = new ArrayList<>(createTools(request));
            ChatResponse response = null;
            Throwable callFailure = null;
            try {
                ToolCatalogSession catalog = null;
                if (request.plan().descriptor().stepContextPolicy() != null && !runTools.isEmpty()) {
                    catalog = new ToolCatalogSession(request, runStore, json,
                            request.plan().descriptor().stepContextPolicy(), runTools);
                    runTools.add(catalog);
                }
                List<ToolCallback> callbacks = new ArrayList<>(SpringAiToolCatalog.createCallbacks(
                        request, runTools, tools, json, journal));
                callbacks.add(decisionCallback);
                SpringAiToolCatalog.ensureUniqueNames(callbacks);
                if (catalog != null) catalog.bindCallbacks(callbacks);
                ModelStepJournal.Recovery recovered = journal.recover(runTools, tools, catalog, true);
                String systemPrompt;
                List<Message> messages;
                StepContextProjector.Projection activeProjection;
                List<Message> originalHistory = prompts.messages(request,
                        recovered == null ? List.of() : recovered.providerMessages(), runStore);
                boolean onDemandEnabled = request.plan().descriptor().onDemandContextPolicy() != null;
                if (recovered == null) {
                    String prepared = onDemandEnabled
                            ? TurnPreparationJournal.prepare(request, runStore, extensionState) : "";
                    systemPrompt = prompts.systemPrompt(request)
                            + (prepared.isBlank() ? "" : "\n\n" + prepared);
                    messages = originalHistory;
                    activeProjection = projector.project(
                            providerMessages(systemPrompt, messages));
                    messages = withoutSystemMessages(activeProjection.messages());
                } else {
                    systemPrompt = "";
                    messages = new ArrayList<>(recovered.providerMessages());
                    activeProjection = new StepContextProjector.Projection(
                            recovered.providerMessages(), recovered.statistics());
                    response = recovered.finalResponse();
                }
                OnDemandContextSession onDemand = null;
                if (onDemandEnabled && response == null) {
                    String stablePrompt = systemPrompt;
                    if (recovered != null) {
                        String prepared = TurnPreparationJournal.prepare(request, runStore, extensionState);
                        stablePrompt = prompts.systemPrompt(request)
                                + (prepared.isBlank() ? "" : "\n\n" + prepared);
                    }
                    onDemand = new OnDemandContextSession(request, catalog, modelTasks,
                            tools, runStore, json, originalHistory, decisionCallback,
                            List.of(new SystemMessage(stablePrompt)), capabilities, interactionHistory);
                    if (recovered != null) onDemand.replayOnce(recovered);
                }
                if (recovered == null && request.approvedToolInvocation() != null
                        && !request.approvedToolInvocation().challenge().trustedContextRead()) {
                    throw new ToolRecoveryRequiredException(request.runId().value(),
                            "approved business tool has no durable model Step; restart the Run");
                }
                if (response == null) {
                    ChatModel rawModel = models.require(request.plan().descriptor().modelPolicyRef());
                    ChatModel model = new SpringAiMeteredChatModel(
                            rawModel, journal, currentAttempt,
                            () -> usageLedger.beginModelCall(request.runId()),
                            request.control()::throwIfCancelled,
                            value -> meter(request, value, currentAttempt.get(),
                                    responseIndex.incrementAndGet()),
                            failure -> meterFailure(request, failure, currentAttempt.get(),
                                    responseIndex.incrementAndGet()),
                            projector, catalog, request, boundary);
                    List<Advisor> customAdvisors = advisorRegistry.create(
                            request.plan().descriptor().advisors(), request.plan().advisorFactories(),
                            new AdvisorRuntimeContext(
                                    request.runId(), request.runRequest(),
                                    extensionState.view(request.runId()), modelTasks, request.control()));

                    // Exactly one recursive advisor owns tool execution. Its manager uses our registry.
                    var advisorBuilder = SpringAiToolCallingAdvisor
                            .builder(projector, catalog, boundary, onDemand)
                            .toolCallingManager(new GuardedToolCallingManager(
                                    ToolCallingManager.builder()
                                            .observationRegistry(observations).build(), journal));
                    ChatClient client = ChatClient.builder(model, observations, null, null, advisorBuilder)
                            .defaultAdvisors(customAdvisors)
                            .build();
                    ToolCatalogProjection toolProjection = onDemand != null
                            ? new ToolCatalogProjection(List.of(), 0,
                                    catalog == null ? 0 : catalog.summaries().size())
                            : catalog == null
                                    ? SpringAiToolCatalog.project(callbacks, Set.of(),
                                            request.plan().descriptor().stepContextPolicy())
                                    : catalog.project(messages);
                    ObjectNode started = JsonNodeFactory.instance.objectNode();
                    started.put("modelPolicy", request.plan().descriptor().modelPolicyRef());
                    started.put("toolCount", toolProjection.callbacks().size());
                    started.put("availableToolCount", toolProjection.availableToolCount());
                    started.put("toolSchemaCharacters", toolProjection.schemaCharacters());
                    started.put("toolCallingAdvisorCount", 1);
                    addProjectionStatistics(started, activeProjection.statistics());
                    request.events().emit("core.model.started", 1, "framework.springai", started);

                    response = callWithRetry(client, systemPrompt, messages, callbacks, request,
                            currentAttempt, journal, runTools, catalog, onDemand,
                            DecisionReplyStream.eligible(request, rawModel));
                }
            } catch (Throwable failure) {
                callFailure = failure;
                throw failure;
            } finally {
                try {
                    closeTools(runTools);
                } catch (RuntimeException closeFailure) {
                    if (callFailure != null) callFailure.addSuppressed(closeFailure);
                    else throw closeFailure;
                }
            }
            if (response == null || response.getResult() == null) {
                throw new IllegalStateException("Spring AI returned no chat result");
            }

            List<RunEventEnvelope> runEvents = runStore.eventsAfter(request.runId(), 0);
            SubmittedDecision submitted = latestSubmittedDecision(request, runEvents).orElse(null);
            if (submitted == null) {
                return repairProtocolOrPause(request, runEvents, "MODEL_DECISION_MISSING",
                        "The model did not submit a valid harness decision");
            }
            ModelDecisionV1 decision = submitted.value();
            if (decision.decision() == ModelDecisionV1.Decision.NEEDS_INPUT) {
                return ReasoningResult.waitingForInput(
                        JsonNodeFactory.instance.objectNode()
                                .put("kind", "harness.needs_input")
                                .put("text", decision.userMessage()),
                        "MODEL_NEEDS_INPUT");
            }
            if (decision.decision() == ModelDecisionV1.Decision.CONTINUE) {
                // A nonterminal control decision must not finalize delivery or evaluate a final answer.
                TaskCompletionDecision continuation = reviewTaskCompletion(request,
                        JsonNodeFactory.instance.objectNode().put("text", decision.userMessage()),
                        runEvents, true);
                if (continuation.repair()) {
                    request.control().enterTaskRepair();
                    return reason(request);
                }
                if (continuation.pause()) {
                    return new ReasoningResult(RunState.PAUSED, continuation.output(), "TASK_UNVERIFIED");
                }
                // Generation-only tasks have no actionable frozen criteria to repair.
                return repairProtocolOrPause(request, runEvents, "MODEL_CONTINUE_NONTERMINAL",
                        "CONTINUE requires further work or a terminal CLAIM_DONE, NEEDS_INPUT or BLOCKED decision");
            }
            ObjectNode output = JsonNodeFactory.instance.objectNode();
            output.put("text", decision.userMessage());
            output.put("modelDecision", decision.decision().name());
            String modelName = response.getMetadata() == null
                    ? request.plan().descriptor().modelPolicyRef()
                    : response.getMetadata().getModel();
            output.put("model", modelName == null ? "" : modelName);
            PrimaryUsage primaryUsage = primaryUsage(request);
            long inputTokens = primaryUsage.inputTokens();
            long outputTokens = primaryUsage.outputTokens();
            ObjectNode usage = output.putObject("usage");
            usage.put("inputTokens", inputTokens);
            usage.put("outputTokens", outputTokens);
            usage.put("estimatedCostCny", primaryUsage.estimatedCostCny());

            JsonNode guarded = output;
            for (var guard : request.plan().outputGuards()) {
                guarded = Objects.requireNonNull(guard.validate(
                                guarded, request.runRequest(), request.runId()),
                        "output guard result");
            }
            if (!request.plan().evaluationPolicies().isEmpty()) {
                ObjectNode resultObject;
                if (guarded instanceof ObjectNode object) {
                    resultObject = object.deepCopy();
                } else {
                    resultObject = JsonNodeFactory.instance.objectNode();
                    resultObject.set("value", guarded);
                }
                ObjectNode assessments = resultObject.putObject("assessments");
                for (var policy : request.plan().evaluationPolicies()) {
                    JsonNode assessment = policy.evaluate(runEvents, resultObject, modelTasks);
                    assessments.set(policy.id(), assessment);
                    request.events().emit(policy.id() + ".assessment",
                            policy.eventSchemaVersion(assessment),
                            "framework.builtin", assessment);
                }
                guarded = resultObject;
            }
            guarded = request.plan().validateOutput(guarded);
            if (decision.decision() == ModelDecisionV1.Decision.BLOCKED) {
                request.events().emit("core.task.stop", 3, "framework.springai",
                        JsonNodeFactory.instance.objectNode().put("reasonCode",
                                TaskStopReason.MODEL_BLOCKED.name()));
            }
            TaskCompletionDecision taskDecision = decision.decision()
                    == ModelDecisionV1.Decision.CLAIM_DONE
                    ? reviewTaskCompletion(request, guarded, runEvents)
                    : new TaskCompletionDecision(false, false, guarded);
            if (taskDecision.repair()) {
                request.control().enterTaskRepair();
                return reason(request);
            }
            guarded = taskDecision.output();
            ObjectNode completed = JsonNodeFactory.instance.objectNode();
            completed.put("model", modelName == null ? "" : modelName);
            completed.put("inputTokens", inputTokens);
            completed.put("outputTokens", outputTokens);
            completed.put("estimatedCostCny", primaryUsage.estimatedCostCny());
            request.events().emit("core.model.completed", 1, "framework.springai", completed);
            if (taskDecision.pause()) {
                return new ReasoningResult(RunState.PAUSED, guarded, "TASK_UNVERIFIED");
            }
            return ReasoningResult.completed(guarded);
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof com.javaclaw.framework.core.InteractionWaitRequiredException wait) {
                return new ReasoningResult(RunState.WAITING_CHILD, wait.context(), wait.getMessage());
            }
            if (cause instanceof com.javaclaw.framework.core.InteractionEventWaitRequiredException wait) {
                return new ReasoningResult(RunState.WAITING_EVENT, wait.context(), wait.getMessage());
            }
            if (cause instanceof GuardedToolCallingManager.ProtocolBatchException batch) {
                if (!batch.recoverable()) {
                    return protocolPause(request, "MODEL_DECISION_MULTIPLE_GENERATIONS",
                            "A control call appeared in a response with multiple generations");
                }
                return repairProtocolOrPause(request,
                        runStore.eventsAfter(request.runId(), 0),
                        "MODEL_DECISION_MIXED_BATCH",
                        "The harness decision was not the only call in its provider result");
            }
            if (cause instanceof ToolApprovalRequiredException approval) {
                ObjectNode waiting = JsonNodeFactory.instance.objectNode();
                waiting.set("approval", approval.challenge().toJson());
                return ReasoningResult.waitingForApproval(
                        waiting, approval.getMessage());
            }
            if (cause instanceof ToolInputRequiredException input) {
                return ReasoningResult.waitingForInput(input.context(), input.getMessage());
            }
            if (cause instanceof com.javaclaw.framework.core.DesktopNoProgressException stalled) {
                ObjectNode context = stalled.context();
                request.events().emit("core.desktop.no_progress", 1, "framework.core", context);
                request.events().emit("core.task.stop", 3, "framework.springai",
                        JsonNodeFactory.instance.objectNode().put("reasonCode", TaskStopReason.NO_PROGRESS.name()));
                return new ReasoningResult(RunState.PAUSED, context, TaskStopReason.NO_PROGRESS.name());
            }
            if (cause instanceof com.javaclaw.framework.core.CrossModeBusinessEffectUnverifiedException business) {
                request.events().emit("core.interaction.business_fence_blocked", 1, "framework.core", business.context());
                return new ReasoningResult(RunState.PAUSED, business.context(), "CROSS_MODE_BUSINESS_UNVERIFIED");
            }
            if (cause instanceof PendingEffectObservationRequiredException pending) {
                ObjectNode paused = JsonNodeFactory.instance.objectNode()
                        .put("kind", "tool.effect_observation_required")
                        .put("reasonCode", "EFFECT_OBSERVATION_REQUIRED")
                        .put("effectReason", pending.reason().name())
                        .put("text", pending.diagnostic())
                        .put("sourceRunId", pending.sourceRunId().isBlank()
                                ? request.runId().value() : pending.sourceRunId())
                        .put("invocationId", pending.invocationId())
                        .put("resourceKey", pending.resourceKey())
                        .put("status", pending.status() == null ? "PENDING" : pending.status().name())
                        .put("delivery", pending.delivery())
                        .put("dispatchAttempted", false);
                request.events().emit("core.effect.observation_required", 1, "framework.core", paused);
                return new ReasoningResult(RunState.PAUSED, paused, "EFFECT_OBSERVATION_REQUIRED");
            }
            if (cause instanceof ToolRecoveryRequiredException recovery) {
                ObjectNode paused = JsonNodeFactory.instance.objectNode()
                        .put("kind", "tool.recovery_required")
                        .put("stepId", recovery.stepId())
                        .put("reason", recovery.getMessage());
                if (!recovery.requestedTools().isEmpty()) {
                    var requested = paused.putArray("requestedTools");
                    recovery.requestedTools().forEach(requested::add);
                    var offered = paused.putArray("offeredTools");
                    recovery.offeredTools().forEach(offered::add);
                    paused.put("unfinishedAction", "The requested tool batch was not executed");
                }
                return new ReasoningResult(RunState.PAUSED,
                        paused,
                        recovery.getMessage());
            }
            if (cause instanceof ContextPlanningRequiredException planning) {
                return new ReasoningResult(RunState.PAUSED,
                        JsonNodeFactory.instance.objectNode().put("kind", "context.planning_required")
                                .put("reason", planning.getMessage()),
                        planning.getMessage());
            }
            if (cause instanceof TurnPausedException paused) {
                ObjectNode context = JsonNodeFactory.instance.objectNode()
                        .put("kind", "context.planning_required")
                        .put("reasonCode", paused.reason().name());
                if (!paused.contextSourceId().isBlank()) {
                    context.put("contextSourceId", paused.contextSourceId());
                }
                return new ReasoningResult(RunState.PAUSED, context, paused.getMessage());
            }
            if (cause instanceof BudgetExceededException budget) {
                var events = runStore.eventsAfter(request.runId(), 0);
                var contractV3 = TaskResultEvaluator.latestContractV3(events, json);
                request.events().emit("core.task.stop", 3,
                        "framework.springai",
                        JsonNodeFactory.instance.objectNode().put("reasonCode",
                                TaskStopReason.BUDGET_EXHAUSTED.name())
                                .put("budgetKind", budget.kind().name())
                                .put("budgetActual", budget.actual())
                                .put("budgetLimit", budget.limit()));
                ObjectNode summary = JsonNodeFactory.instance.objectNode();
                summary.put("kind", "harness.budget_exhausted");
                summary.put("budgetKind", budget.kind().name());
                summary.put("budgetActual", budget.actual());
                summary.put("budgetLimit", budget.limit());
                summary.put("text", "运行预算耗尽，尚未收到独立的任务完成决策，无法确认完成。");
                if (contractV3.map(TaskContractV3::applicable).orElse(false)) {
                    List<RunEventEnvelope> evidence = TaskEvidenceCollector.collect(runStore, request.runId());
                    TaskResult partial = TaskResultEvaluator.evaluateV3(contractV3.get(), evidence,
                            TaskStopReason.BUDGET_EXHAUSTED.name(), capabilities);
                    if (!partial.unmetCriteria().isEmpty()) {
                        summary.put("unmetCriteria", bounded(
                                String.join("；", partial.unmetCriteria()), 800));
                    }
                }
                return new ReasoningResult(RunState.PAUSED, summary,
                        TaskStopReason.BUDGET_EXHAUSTED.name());
            }
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        }
    }

    private TaskCompletionDecision reviewTaskCompletion(
            ReasoningRequest request, JsonNode output, List<RunEventEnvelope> events) {
        return reviewTaskCompletion(request, output, events, false);
    }

    private TaskCompletionDecision reviewTaskCompletion(
            ReasoningRequest request, JsonNode output, List<RunEventEnvelope> events,
            boolean requireTerminalDecision) {
        var contractV3 = TaskResultEvaluator.latestContractV3(events, json);
        if (contractV3.isEmpty()) return new TaskCompletionDecision(false, false, output);
        int taskSchemaVersion = 3;
        boolean applicable = contractV3.get().applicable();
        if (!applicable) return new TaskCompletionDecision(false, false, output);

        String modelStepId = new RunStepQuery(runStore).steps(request.runId()).stream()
                .filter(step -> step.kind() == AgentStep.Kind.MODEL
                        && step.state() == AgentStep.State.COMPLETED)
                .max(Comparator.comparingLong(AgentStep::lastSequence))
                .map(step -> step.id().value()).orElse("");
        List<RunEventEnvelope> evidence = TaskEvidenceCollector.collect(runStore, request.runId());
        TaskResult result = TaskResultEvaluator.evaluateV3(contractV3.get(), evidence, "", capabilities);
        boolean evidenceComplete = result.outcome() == TaskOutcome.VERIFIED_COMPLETE;
        if (requireTerminalDecision) {
            result = TaskResultEvaluator.gateWithModelDecision(result, events);
        }
        ObjectNode review = (ObjectNode) json.valueToTree(result);
        review.put("modelStepId", modelStepId);
        request.events().emit("core.task.review", taskSchemaVersion, "framework.springai", review);
        if (result.outcome() == TaskOutcome.VERIFIED_COMPLETE) {
            return new TaskCompletionDecision(false, false, output);
        }

        List<RunEventEnvelope> repairs = events.stream()
                .filter(event -> event.type().equals("core.task.repair_requested"))
                .toList();
        RunEventEnvelope latestRepair = repairs.isEmpty() ? null : repairs.getLast();
        boolean progressed = latestRepair == null
                || meaningfulRepairProgress(events, evidence, latestRepair, result);
        boolean budgetAvailable = request.control().remainingToolCalls() > 0
                && request.control().remaining().compareTo(Duration.ofSeconds(3)) > 0
                && hasInputHeadroomForRepair(request, events);
        boolean reliable = contractV3.get().reliable();
        if (reliable && repairs.size() < 2 && progressed && budgetAvailable
                && !modelStepId.isBlank()) {
            String feedback = requireTerminalDecision && evidenceComplete
                    ? "已有宿主收据满足全部冻结验收条件。当前 CONTINUE 尚未提交终态完成决策。"
                            + "不要再次执行业务工具或重复写入、发送、删除、输入等副作用；"
                            + "只在独立 control batch 中提交 CLAIM_DONE，userMessage 包含实际完整答复，"
                            + "evidenceRefs 仅使用已给出的真实宿主引用，unmetCriterionIds=[]。"
                    : taskRepairFeedback(contractV3.get(), result);
            ObjectNode repair = JsonNodeFactory.instance.objectNode();
            repair.put("modelStepId", modelStepId);
            repair.put("feedback", feedback);
            repair.put("attempt", repairs.size() + 1);
            request.events().emit("core.task.repair_requested", taskSchemaVersion,
                    "framework.springai", repair);
            return new TaskCompletionDecision(true, false, output);
        }

        TaskStopReason reason = !reliable ? TaskStopReason.UNRELIABLE_CONTRACT
                : !progressed ? TaskStopReason.NO_PROGRESS
                : !budgetAvailable ? TaskStopReason.BUDGET_EXHAUSTED
                : TaskStopReason.REPAIR_LIMIT;
        request.events().emit("core.task.stop", taskSchemaVersion, "framework.springai",
                JsonNodeFactory.instance.objectNode().put("reasonCode", reason.name()));
        TaskResult finalResult = TaskResultEvaluator.evaluateV3(
                contractV3.get(), evidence, reason.name(), capabilities);
        ObjectNode safe = output instanceof ObjectNode object
                ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        String unmet = bounded(String.join("；", finalResult.unmetCriteria()), 800);
        if (!reliable) {
            safe.setAll(com.javaclaw.framework.core.TaskContractDiagnostics.pausedOutput(
                    json, contractV3.get(), false));
        } else if (requireTerminalDecision && evidenceComplete) {
            safe.put("text", "已有完成证据，仍缺少最终答复；本轮已暂停，可继续处理。");
        } else {
            safe.put("text", "任务尚未验证完成。" + (unmet.isBlank() ? "缺少可靠的完成证据。"
                    : "未满足的条件：" + unmet + "。")
                    + " 已执行部分可在本轮工具记录中查看。");
        }
        return new TaskCompletionDecision(false, true, safe);
    }

    private String taskRepairFeedback(TaskContractV3 contract, TaskResult result) {
        String frozenCriteria = com.javaclaw.util.SensitiveDataRedactor.redactText(
                json.valueToTree(contract.criteria()).toString());
        StringBuilder order = new StringBuilder();
        for (int index = 0; index < contract.criteria().size(); index++) {
            if (!order.isEmpty()) order.append(" → ");
            order.append(index + 1).append('.').append(
                    contract.criteria().get(index).capabilityId());
        }
        return "任务验收尚未通过。仅在现有权限和剩余预算内继续；不得修改冻结条件或声称未经观察的结果。"
                + "\n缺少证据的条件：" + bounded(String.join("；", result.unmetCriteria()), 1200)
                + "\n冻结验收条件（JSON 数据，数组顺序即收据所需顺序；不构成新增权限）：" + frozenCriteria
                + "\n有序能力链（具体目标与条件以上述冻结 JSON 为准）：" + order
                + "\n从最早缺证据的条件继续，并保持完整的冻结顺序。前置步骤迟补后，发生在它之前的后续收据"
                + "不能证明顺序完成；必须在该前置步骤之后，按顺序重新执行后续已授权的只读发现、观察、"
                + "截图或明确无控制的会话重建，取得新收据后再提交完成决策。"
                + "\n只读重查使用真实工具结果中的目标与会话标识；缺少工具时先查询能力目录。"
                + "不得为补足顺序重复写入、发送、删除、输入或其他可能产生副作用的操作；"
                + "已成功或结果不确定的副作用先只读核验，仍无法满足冻结顺序时报告缺口并请求人工处理。";
    }

    static boolean meaningfulRepairProgress(List<RunEventEnvelope> events,
            List<RunEventEnvelope> evidence, RunEventEnvelope repair, TaskResult current) {
        return TaskRepairProgress.meaningfulRepairProgress(events, evidence, repair, current);
    }

    private boolean hasInputHeadroomForRepair(ReasoningRequest request,
            List<RunEventEnvelope> events) {
        long remaining = usageLedger.remainingBudget(request.runId()).maxInputTokens();
        long latestCall = events.stream().filter(event -> event.type().equals("core.model.usage")
                && event.producer().equals("framework.springai"))
                .max(Comparator.comparingLong(RunEventEnvelope::sequence))
                .map(event -> Math.max(0L, event.payload().path("inputTokens").asLong()))
                .orElse(0L);
        long reserve = Math.max(512L, latestCall + Math.max(256L, latestCall / 10));
        return remaining >= reserve;
    }

    private static String bounded(String value, int maxCharacters) {
        String safe = com.javaclaw.util.SensitiveDataRedactor.redactText(value == null ? "" : value);
        return safe.length() <= maxCharacters ? safe : safe.substring(0, maxCharacters) + "…";
    }

    private record TaskCompletionDecision(boolean repair, boolean pause, JsonNode output) { }

    private Optional<SubmittedDecision> latestSubmittedDecision(
            ReasoningRequest request, List<RunEventEnvelope> events) {
        long lastRepair = TaskResultEvaluator.modelDecisionBoundary(events);
        RunEventEnvelope submitted = events.stream()
                .filter(event -> event.sequence() > lastRepair
                        && event.type().equals("core.harness.decision_submitted")
                        && event.schemaVersion() == 1
                        && event.producer().equals("framework.springai"))
                .max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (submitted == null) return Optional.empty();
        String modelStepId = submitted.payload().path("modelStepId").asText("");
        String invocationId = submitted.payload().path("invocationId").asText("");
        if (modelStepId.isBlank() || invocationId.isBlank()
                || events.stream().anyMatch(event -> event.sequence() > submitted.sequence()
                        && event.type().equals("core.step.started")
                        && event.producer().equals("framework.core")
                        && event.payload().path("kind").asText("").equals("MODEL"))
                || events.stream().anyMatch(event -> event.sequence() > submitted.sequence()
                        && event.type().equals("core.tool.started")
                        && event.producer().equals("framework.core"))) return Optional.empty();
        var steps = new RunStepQuery(runStore);
        var model = steps.step(request.runId(), new StepId(modelStepId)).orElse(null);
        var control = steps.step(request.runId(), StepId.tool(request.runId(), invocationId))
                .orElse(null);
        if (model == null || model.kind() != AgentStep.Kind.MODEL
                || model.state() != AgentStep.State.COMPLETED
                || model.startSequence() <= lastRepair
                || control == null || control.kind() != AgentStep.Kind.ORCHESTRATION
                || control.state() != AgentStep.State.COMPLETED
                || control.startSequence() <= lastRepair
                || !modelStepId.equals(control.input().path("modelStepId").asText(""))
                || !"harness.decision".equals(control.input().path("phase").asText(""))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SubmittedDecision(
                    ModelDecisionV1.fromJson(submitted.payload().path("value")), submitted));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private ReasoningResult protocolPause(ReasoningRequest request, String code, String detail) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("code", "PROTOCOL_ERROR").put("violationCode", code)
                .put("detail", detail);
        JsonNode rejection = "MODEL_DECISION_MISSING".equals(code)
                ? latestDecisionRejection(request) : null;
        addDecisionRejection(payload, rejection);
        request.events().emit("core.harness.protocol_violation", 1,
                "framework.springai", payload);
        ObjectNode output = JsonNodeFactory.instance.objectNode()
                .put("kind", "harness.protocol_violation").put("code", "PROTOCOL_ERROR")
                .put("violationCode", code).put("reason", detail);
        addDecisionRejection(output, rejection);
        return new ReasoningResult(RunState.PAUSED, output, "PROTOCOL_ERROR");
    }

    private JsonNode latestDecisionRejection(ReasoningRequest request) {
        return HarnessProtocolFeedback.latestDecisionRejection(request, runStore);
    }

    private static void addDecisionRejection(ObjectNode target, JsonNode rejection) {
        HarnessProtocolFeedback.addDecisionRejection(target, rejection);
    }

    private ReasoningResult repairProtocolOrPause(ReasoningRequest request,
            List<RunEventEnvelope> events, String code, String detail) {
        long attempts = events.stream().filter(event ->
                event.type().equals("core.harness.protocol_repair_requested")
                        && event.producer().equals("framework.springai")).count();
        String modelStepId = new RunStepQuery(runStore).steps(request.runId()).stream()
                .filter(step -> step.kind() == AgentStep.Kind.MODEL
                        && step.state() == AgentStep.State.COMPLETED)
                .max(Comparator.comparingLong(AgentStep::lastSequence))
                .map(step -> step.id().value()).orElse("");
        if (attempts >= 1 || modelStepId.isBlank()
                || request.control().remaining().compareTo(Duration.ofSeconds(3)) <= 0) {
            return protocolPause(request, code, detail);
        }
        JsonNode rejection = "MODEL_DECISION_MISSING".equals(code)
                ? latestDecisionRejection(request) : null;
        ObjectNode repair = JsonNodeFactory.instance.objectNode()
                .put("modelStepId", modelStepId).put("code", code)
                .put("attempt", attempts + 1)
                .put("feedback", protocolRepairFeedback(rejection));
        addDecisionRejection(repair, rejection);
        request.events().emit("core.harness.protocol_repair_requested", 1,
                "framework.springai", repair);
        return reason(request);
    }

    static String protocolRepairFeedback(JsonNode rejection) {
        return HarnessProtocolFeedback.protocolRepairFeedback(rejection);
    }

    private record SubmittedDecision(ModelDecisionV1 value, RunEventEnvelope event) { }

    private ChatResponse callWithRetry(
            ChatClient client,
            String systemPrompt,
            List<Message> messages,
            List<ToolCallback> callbacks,
            ReasoningRequest request,
            AtomicInteger currentAttempt,
            ModelStepJournal journal,
            List<FrameworkTool> runTools,
            ToolCatalogSession catalog,
            OnDemandContextSession onDemand,
            boolean streamReply) throws Throwable {
        int attempt = 1;
        while (true) {
            request.control().throwIfCancelled();
            currentAttempt.set(attempt);
            try {
                var prompt = client.prompt().messages(providerMessages(systemPrompt, messages)).tools(callbacks);
                if (!streamReply) return prompt.call().chatResponse();
                AtomicReference<ChatResponse> aggregate = new AtomicReference<>();
                new MessageAggregator().aggregate(prompt.stream().chatResponse(), aggregate::set).blockLast();
                return aggregate.get();
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                if (cause instanceof ToolApprovalRequiredException
                        || cause instanceof com.javaclaw.framework.core.InteractionWaitRequiredException
                        || cause instanceof com.javaclaw.framework.core.InteractionEventWaitRequiredException
                        || cause instanceof ToolInputRequiredException
                        || cause instanceof ToolRecoveryRequiredException
                        || cause instanceof PendingEffectObservationRequiredException
                        || cause instanceof com.javaclaw.framework.core.DesktopNoProgressException
                        || cause instanceof com.javaclaw.framework.core.CrossModeBusinessEffectUnverifiedException
                        || cause instanceof ContextPlanningRequiredException
                        || cause instanceof TurnPausedException
                        || cause instanceof BudgetExceededException
                        || cause instanceof RunCancelledException
                        || attempt >= 8) throw failure;
                RetryDirective directive = null;
                String policyId = null;
                var context = new RetryContext(
                        request.runId(), "spring-ai.chat", attempt, cause,
                        request.control().remaining());
                for (var policy : request.plan().retryPolicies()) {
                    var candidate = policy.evaluate(context);
                    if (candidate.isPresent()) {
                        directive = candidate.get();
                        policyId = policy.id();
                        break;
                    }
                }
                if (directive == null || !directive.retry()
                        || directive.delay().compareTo(request.control().remaining()) >= 0) {
                    throw failure;
                }
                ObjectNode retrying = JsonNodeFactory.instance.objectNode();
                retrying.put("attempt", attempt);
                retrying.put("nextAttempt", attempt + 1);
                retrying.put("delayMillis", directive.delay().toMillis());
                retrying.put("policy", policyId);
                retrying.put("errorType", cause.getClass().getName());
                request.events().emit("core.model.retrying", 1,
                        "framework.springai", retrying);
                awaitRetry(directive.delay(), request);
                ModelStepJournal.Recovery recovered = journal.recover(
                        runTools, tools, catalog, false);
                if (recovered != null) {
                    systemPrompt = "";
                    messages = recovered.providerMessages();
                    if (onDemand != null) onDemand.replayOnce(recovered);
                    if (recovered.finalResponse() != null) return recovered.finalResponse();
                }
                attempt++;
            }
        }
    }

    private void meter(
            ReasoningRequest request,
            ChatResponse response,
            int attempt,
            int responseIndex) {
        if (response == null) return;
        Usage responseUsage = response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        long inputTokens = token(responseUsage == null ? null : responseUsage.getPromptTokens());
        long outputTokens = token(responseUsage == null ? null : responseUsage.getCompletionTokens());
        String modelName = response.getMetadata() == null || response.getMetadata().getModel() == null
                ? request.plan().descriptor().modelPolicyRef()
                : response.getMetadata().getModel();
        meter(request, modelName, inputTokens, outputTokens, attempt, responseIndex, false);
    }

    private void meterFailure(
            ReasoningRequest request,
            ManagedInferenceChatModel.ManagedInferenceModelException failure,
            int attempt,
            int responseIndex) {
        meter(request, failure.model(), failure.usage().promptTokens(),
                failure.usage().completionTokens(), attempt, responseIndex, true);
    }

    private void meter(
            ReasoningRequest request,
            String modelName,
            long inputTokens,
            long outputTokens,
            int attempt,
            int responseIndex,
            boolean failed) {
        BigDecimal estimatedCost = BigDecimal.valueOf(
                com.javaclaw.agent.PricingTable.estimateCostCny(
                        modelName, inputTokens, outputTokens));
        RuntimeException ledgerFailure = null;
        try {
            usageLedger.record(request.runId(), inputTokens, outputTokens, estimatedCost);
        } catch (RuntimeException failure) {
            ledgerFailure = failure;
        }
        try {
            ObjectNode usage = JsonNodeFactory.instance.objectNode();
            usage.put("model", modelName);
            usage.put("attempt", attempt);
            usage.put("responseIndex", responseIndex);
            usage.put("inputTokens", inputTokens);
            usage.put("outputTokens", outputTokens);
            usage.put("estimatedCostCny", estimatedCost);
            usage.put("failed", failed);
            request.events().emit("core.model.usage", 1, "framework.springai", usage);
        } catch (RuntimeException eventFailure) {
            if (ledgerFailure == null) throw eventFailure;
            ledgerFailure.addSuppressed(eventFailure);
        }
        if (ledgerFailure != null) throw ledgerFailure;
    }

    private PrimaryUsage primaryUsage(ReasoningRequest request) {
        long inputTokens = 0;
        long outputTokens = 0;
        BigDecimal estimatedCost = BigDecimal.ZERO;
        for (var event : runStore.eventsAfter(request.runId(), 0)) {
            if (!event.type().equals("core.model.usage")) continue;
            inputTokens = Math.addExact(inputTokens,
                    Math.max(0, event.payload().path("inputTokens").asLong()));
            outputTokens = Math.addExact(outputTokens,
                    Math.max(0, event.payload().path("outputTokens").asLong()));
            JsonNode cost = event.payload().get("estimatedCostCny");
            if (cost != null && cost.isNumber()) {
                estimatedCost = estimatedCost.add(
                        cost.decimalValue().max(BigDecimal.ZERO));
            }
        }
        return new PrimaryUsage(inputTokens, outputTokens, estimatedCost);
    }

    private static void awaitRetry(
            java.time.Duration delay, ReasoningRequest request) {
        long remainingNanos = delay.toNanos();
        long quantum = java.time.Duration.ofMillis(100).toNanos();
        while (remainingNanos > 0) {
            request.control().throwIfCancelled();
            long wait = Math.min(remainingNanos, quantum);
            java.util.concurrent.locks.LockSupport.parkNanos(wait);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new RunCancelledException();
            }
            remainingNanos -= wait;
        }
    }

    private List<FrameworkTool> createTools(ReasoningRequest request) {
        JsonNode disabled = request.runRequest().attributes().get("framework.disableTools");
        if (disabled != null && disabled.asBoolean(false)) return List.of();
        ToolContext context = new ToolContext(
                request.runId(), request.runRequest().scope(),
                request.plan().descriptor().permissions(), request.control(),
                request.control().deadline(), request.runRequest());
        List<FrameworkTool> runTools = new ArrayList<>();
        try {
            for (var factory : request.plan().toolFactories()) {
                runTools.add(Objects.requireNonNull(factory.create(context), "framework tool"));
            }
            for (var provider : request.plan().toolProviderFactories()) {
                List<FrameworkTool> provided = Objects.requireNonNull(
                        provider.create(context), "framework tools");
                provided.forEach(tool -> runTools.add(Objects.requireNonNull(tool, "framework tool")));
            }
            for (int index = runTools.size() - 1; index >= 0; index--) {
                FrameworkTool tool = runTools.get(index);
                boolean groupAllowed = ToolGroupAccess.allows(
                        request.runRequest(), tool.descriptor().group());
                boolean permissionAllowed = request.plan().descriptor().permissions()
                        .containsAll(tool.descriptor().requiredPermissions());
                if (groupAllowed && permissionAllowed) continue;
                runTools.remove(index);
                try {
                    tool.close();
                } catch (Exception failure) {
                    throw new IllegalStateException("cannot close filtered tool", failure);
                }
            }
            bindTrustedCapabilities(runTools, capabilities);
        } catch (RuntimeException failure) {
            try {
                closeTools(runTools);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return runTools;
    }

    /** A descriptor alone is untrusted: an extension can copy every declared field. */
    static void bindTrustedCapabilities(
            List<FrameworkTool> runTools, TrustedCapabilityRegistry capabilities) {
        for (FrameworkTool tool : runTools) {
            String name = tool.descriptor().name();
            if (com.javaclaw.agent.ToolRiskRegistry.isKnownHostTool(name)
                    && !SpringAiAnnotatedToolRegistry.isExactHostTool(tool)) {
                throw new IllegalStateException("untrusted tool impersonates host tool: "
                        + name);
            }
            if (!capabilities.hasTrustedTool(name)) continue;
            if (!SpringAiAnnotatedToolRegistry.isTrustedReceiptSource(tool)) {
                throw new IllegalStateException("tool has no trusted receipt adapter: " + name);
            }
            capabilities.bindHostTool(tool.descriptor());
        }
    }

    private record PrimaryUsage(
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCostCny) { }


}
