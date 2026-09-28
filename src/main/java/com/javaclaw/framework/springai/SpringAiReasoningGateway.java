package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.core.CancellableTaskStages;
import com.javaclaw.framework.core.ReasoningGateway;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.ReasoningResult;
import com.javaclaw.framework.core.RunUsageLedger;
import com.javaclaw.framework.core.ToolApprovalRequiredException;
import com.javaclaw.framework.core.ToolGroupAccess;
import com.javaclaw.framework.core.ToolInputRequiredException;
import com.javaclaw.framework.core.ToolInvocationGateway;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.model.tool.ToolCallingManager;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

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
            StepContextProjector projector = new StepContextProjector(
                    request.plan().descriptor().stepContextPolicy());
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
                List<ToolCallback> callbacks = SpringAiToolCatalog.createCallbacks(
                        request, runTools, tools, json, journal);
                if (catalog != null) catalog.bindCallbacks(callbacks);
                ModelStepJournal.Recovery recovered = journal.recover(runTools, tools, catalog, true);
                String systemPrompt;
                List<Message> messages;
                StepContextProjector.Projection activeProjection;
                List<Message> originalHistory = prompts.messages(request);
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
                    systemPrompt = recovered.systemPrompt();
                    messages = new ArrayList<>(recovered.messages());
                    activeProjection = new StepContextProjector.Projection(
                            recovered.messages(), recovered.statistics());
                    response = recovered.finalResponse();
                }
                OnDemandContextSession onDemand = null;
                if (onDemandEnabled && response == null) {
                    if (recovered != null) {
                        TurnPreparationJournal.prepare(request, runStore, extensionState);
                    }
                    onDemand = new OnDemandContextSession(request, catalog, modelTasks,
                            tools, runStore, json, originalHistory);
                    if (recovered != null) onDemand.replayOnce(recovered);
                }
                if (recovered == null && request.approvedToolInvocation() != null
                        && !request.approvedToolInvocation().challenge().tool()
                                .startsWith("framework_context_")) {
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
                            currentAttempt, journal, runTools, catalog, onDemand);
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

            String text = response.getResult().getOutput().getText();
            ObjectNode output = JsonNodeFactory.instance.objectNode();
            output.put("text", text == null ? "" : text);
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
                List<RunEventEnvelope> runEvents =
                        runStore.eventsAfter(request.runId(), 0);
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
            ObjectNode completed = JsonNodeFactory.instance.objectNode();
            completed.put("model", modelName == null ? "" : modelName);
            completed.put("inputTokens", inputTokens);
            completed.put("outputTokens", outputTokens);
            completed.put("estimatedCostCny", primaryUsage.estimatedCostCny());
            request.events().emit("core.model.completed", 1, "framework.springai", completed);
            return ReasoningResult.completed(guarded);
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof ToolApprovalRequiredException approval) {
                return ReasoningResult.waitingForApproval(
                        approval.challenge().toJson(), approval.getMessage());
            }
            if (cause instanceof ToolInputRequiredException input) {
                return ReasoningResult.waitingForInput(input.context(), input.getMessage());
            }
            if (cause instanceof ToolRecoveryRequiredException recovery) {
                return new ReasoningResult(RunState.PAUSED,
                        JsonNodeFactory.instance.objectNode().put("kind", "tool.recovery_required")
                                .put("stepId", recovery.stepId())
                                .put("reason", recovery.getMessage()),
                        recovery.getMessage());
            }
            if (cause instanceof ContextPlanningRequiredException planning) {
                return new ReasoningResult(RunState.PAUSED,
                        JsonNodeFactory.instance.objectNode().put("kind", "context.planning_required")
                                .put("reason", planning.getMessage()),
                        planning.getMessage());
            }
            if (cause instanceof BudgetExceededException budget
                    && request.plan().descriptor().onDemandContextPolicy() != null) {
                return new ReasoningResult(RunState.PAUSED,
                        JsonNodeFactory.instance.objectNode().put("kind", "context.budget_exhausted")
                                .put("reason", budget.getMessage()), budget.getMessage());
            }
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        }
    }

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
            OnDemandContextSession onDemand) throws Throwable {
        int attempt = 1;
        while (true) {
            request.control().throwIfCancelled();
            currentAttempt.set(attempt);
            try {
                ChatResponse response = client.prompt()
                        .system(systemPrompt)
                        .messages(messages)
                        .tools(callbacks)
                        .call()
                        .chatResponse();
                return response;
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                if (cause instanceof ToolApprovalRequiredException
                        || cause instanceof ToolInputRequiredException
                        || cause instanceof ToolRecoveryRequiredException
                        || cause instanceof ContextPlanningRequiredException
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
                    if (request.plan().descriptor().onDemandContextPolicy() == null) {
                        systemPrompt = recovered.systemPrompt();
                    }
                    messages = recovered.messages();
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

    private static List<Message> providerMessages(
            String systemPrompt, List<Message> messages) {
        List<Message> combined = new ArrayList<>();
        if (!systemPrompt.isBlank()) combined.add(new SystemMessage(systemPrompt));
        combined.addAll(messages);
        return combined;
    }

    private static List<Message> withoutSystemMessages(List<Message> messages) {
        return messages.stream().filter(message -> !(message instanceof SystemMessage)).toList();
    }

    private static void addProjectionStatistics(
            ObjectNode target, StepContextProjector.Statistics statistics) {
        target.put("contextProjectionVersion", 1);
        target.put("messagesBefore", statistics.messagesBefore());
        target.put("messagesAfter", statistics.messagesAfter());
        target.put("messageCharactersBefore", statistics.charactersBefore());
        target.put("messageCharactersAfter", statistics.charactersAfter());
        target.put("evictedToolExchanges", statistics.evictedToolExchanges());
        target.put("contextCompacted", statistics.compacted());
    }

    private static void closeTools(List<FrameworkTool> runTools) {
        RuntimeException first = null;
        for (int index = runTools.size() - 1; index >= 0; index--) {
            try {
                runTools.get(index).close();
            } catch (Exception failure) {
                if (first == null) first = new IllegalStateException("cannot close run tool", failure);
                else first.addSuppressed(failure);
            }
        }
        if (first != null) throw first;
    }

    private static long token(Integer value) {
        return value == null ? 0L : Math.max(0, value.longValue());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException
                || current.getClass().getName().contains("ToolExecution"))) {
            current = current.getCause();
        }
        return current;
    }

    private record PrimaryUsage(
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCostCny) { }


}
