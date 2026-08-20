package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.spi.ExtensionStateStore;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ToolContext;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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
            ChatModel rawModel = models.require(request.plan().descriptor().modelPolicyRef());
            ChatModel model = new MeteredChatModel(rawModel, response -> meter(
                    request, response, currentAttempt.get(), responseIndex.incrementAndGet()),
                    failure -> meterFailure(request, failure, currentAttempt.get(),
                            responseIndex.incrementAndGet()));
            List<Advisor> customAdvisors = advisorRegistry.create(
                    request.plan().descriptor().advisors(), request.plan().advisorFactories(),
                    new com.javaclaw.framework.spi.AdvisorRuntimeContext(
                            request.runId(), request.runRequest(),
                            extensionState.view(request.runId()), modelTasks, request.control()));

            // ChatClient 2.0 installs its recursive ToolCallingAdvisor. We never add another one.
            ChatClient client = ChatClient.builder(model, observations, null, null)
                    .defaultAdvisors(customAdvisors)
                    .build();
            List<FrameworkTool> runTools = createTools(request);
            List<ToolCallback> callbacks = createToolCallbacks(request, runTools);
            String systemPrompt = buildSystemPrompt(request);
            List<Message> messages = new ArrayList<>(buildMessages(request));

            ObjectNode started = JsonNodeFactory.instance.objectNode();
            started.put("modelPolicy", request.plan().descriptor().modelPolicyRef());
            started.put("toolCount", callbacks.size());
            started.put("toolCallingAdvisorCount", 1);
            request.events().emit("core.model.started", 1, "framework.springai", started);

            if (request.approvedToolInvocation() != null) {
                appendApprovedToolContinuation(
                        request, runTools, messages, request.approvedToolInvocation());
            }

            ChatResponse response = null;
            Throwable callFailure = null;
            try {
                response = callWithRetry(
                        client, systemPrompt, messages, callbacks, request, currentAttempt);
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
                List<com.javaclaw.framework.api.RunEventEnvelope> runEvents =
                        runStore.eventsAfter(request.runId(), 0);
                for (var policy : request.plan().evaluationPolicies()) {
                    JsonNode assessment = policy.evaluate(runEvents, resultObject, modelTasks);
                    assessments.set(policy.id(), assessment);
                    request.events().emit(policy.id() + ".assessment", 1,
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
            AtomicInteger currentAttempt) throws Throwable {
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
                        || cause instanceof BudgetExceededException
                        || cause instanceof com.javaclaw.framework.spi.RunCancelledException
                        || attempt >= 8) throw failure;
                com.javaclaw.framework.spi.RetryDirective directive = null;
                String policyId = null;
                var context = new com.javaclaw.framework.spi.RetryContext(
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
                throw new com.javaclaw.framework.spi.RunCancelledException();
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

    private List<ToolCallback> createToolCallbacks(
            ReasoningRequest request, List<FrameworkTool> runTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (FrameworkTool tool : runTools) {
            callbacks.add(new SpringAiToolCallback(tool, request, tools, json));
        }
        long uniqueNames = callbacks.stream().map(callback ->
                callback.getToolDefinition().name()).distinct().count();
        if (uniqueNames != callbacks.size()) {
            throw new IllegalStateException("duplicate tool names in execution plan");
        }
        return List.copyOf(callbacks);
    }

    private void appendApprovedToolContinuation(
            ReasoningRequest request,
            List<FrameworkTool> runTools,
            List<Message> messages,
            ApprovedToolInvocation approved) {
        var challenge = approved.challenge();
        FrameworkTool selected = runTools.stream()
                .filter(tool -> tool.descriptor().name().equals(challenge.tool()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "approved tool is absent from the locked execution plan: "
                                + challenge.tool()));
        String actualFingerprint = ToolInvocationFingerprint.create(
                selected.descriptor().name(), challenge.arguments());
        if (!actualFingerprint.equals(challenge.fingerprint())) {
            throw new IllegalStateException(
                    "approved tool arguments no longer match their fingerprint");
        }
        String callId = "call_" + challenge.fingerprint().substring(
                0, Math.min(24, challenge.fingerprint().length()));
        ToolInvocationResult result;
        try {
            result = SpringAiToolCallback.invoke(
                    selected, challenge.arguments(), request, tools, callId);
        } catch (ToolApprovalRequiredException repeatedChallenge) {
            throw new IllegalStateException(
                    "an exact approved tool invocation requested approval again",
                    repeatedChallenge);
        } finally {
            request.control().discardToolApprovalGrant(challenge.fingerprint());
        }
        try {
            String arguments = json.writeValueAsString(challenge.arguments());
            String response = json.writeValueAsString(result.output());
            messages.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            callId, "function", challenge.tool(), arguments)))
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            callId, challenge.tool(), response)))
                    .build());
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot encode approved tool continuation", failure);
        }
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

    private String buildSystemPrompt(ReasoningRequest request) {
        StringBuilder prompt = new StringBuilder();
        request.plan().descriptor().promptSections().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> prompt.append("## ").append(entry.getKey()).append('\n')
                        .append(entry.getValue()).append("\n\n"));
        JsonNode invocationPrompt = request.runRequest().attributes().get("framework.systemPrompt");
        if (invocationPrompt != null && invocationPrompt.isTextual()
                && !invocationPrompt.asText().isBlank()) {
            prompt.append("## Invocation profile\n")
                    .append(invocationPrompt.asText()).append("\n\n");
        }
        var state = extensionState.view(request.runId());
        for (var contributor : request.plan().promptContributors()) {
            String contribution = contributor.contribute(request.runRequest(), state);
            if (contribution != null && !contribution.isBlank()) {
                prompt.append(contribution).append("\n\n");
            }
        }
        String query = inputText(request);
        for (var retriever : request.plan().retrievers()) {
            List<JsonNode> documents = retriever.retrieve(query, request.runRequest());
            if (!documents.isEmpty()) {
                prompt.append("## Retrieved context\n").append(documents).append("\n\n");
            }
        }
        for (var provider : request.plan().contextProviders()) {
            List<JsonNode> values = provider.provide(request.runRequest(), state);
            if (!values.isEmpty()) {
                prompt.append("## Runtime context\n").append(values).append("\n\n");
            }
        }
        return prompt.toString();
    }

    private List<Message> buildMessages(ReasoningRequest request) {
        List<Message> messages = new ArrayList<>();
        for (InputBlock block : request.runRequest().inputs()) {
            if (!block.type().equals("core.message")) continue;
            String text = block.data().path("text").asText("");
            if (text.isBlank()) continue;
            if (block.data().path("role").asText("").equals("assistant")) {
                messages.add(new AssistantMessage(text));
            } else {
                messages.add(new UserMessage(text));
            }
        }
        messages.add(buildCurrentUserMessage(request));
        return List.copyOf(messages);
    }

    private UserMessage buildCurrentUserMessage(ReasoningRequest request) {
        StringBuilder text = new StringBuilder(inputText(request));
        List<Media> media = new ArrayList<>();
        for (InputBlock block : request.runRequest().inputs()) {
            if (!block.type().equals("core.file") && !block.type().equals("core.image")) continue;
            JsonNode data = block.data();
            String name = data.path("name").asText("attachment");
            String uri = data.path("uri").asText("");
            String mediaType = data.path("mediaType").asText("application/octet-stream");
            text.append("\n[Attachment: ").append(name).append("; ").append(mediaType).append(']');
            if (!uri.isBlank() && (mediaType.startsWith("image/") || mediaType.startsWith("audio/"))) {
                try {
                    media.add(Media.builder().name(name)
                            .mimeType(MimeTypeUtils.parseMimeType(mediaType))
                            .data(URI.create(uri)).build());
                } catch (RuntimeException ignored) {
                    // The textual attachment descriptor remains available to the model.
                }
            }
        }
        if (request.resumeCommand() != null
                && !request.resumeCommand().type().equals("tool.approval")) {
            text.append("\n\nResume command (").append(request.resumeCommand().type())
                    .append("): ").append(request.resumeCommand().payload());
        }
        UserMessage.Builder builder = UserMessage.builder().text(text.toString());
        if (!media.isEmpty()) builder.media(media);
        return builder.build();
    }

    private static String inputText(ReasoningRequest request) {
        return request.runRequest().inputs().stream()
                .filter(block -> block.type().equals("core.text"))
                .map(block -> block.data().path("text").asText())
                .collect(java.util.stream.Collectors.joining("\n"));
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

    /** Captures every provider response before advisors can execute tools or retry. */
    private static final class MeteredChatModel implements ChatModel {
        private final ChatModel delegate;
        private final java.util.function.Consumer<ChatResponse> meter;
        private final java.util.function.Consumer<ManagedInferenceChatModel.ManagedInferenceModelException>
                failureMeter;

        private MeteredChatModel(
                ChatModel delegate,
                java.util.function.Consumer<ChatResponse> meter,
                java.util.function.Consumer<ManagedInferenceChatModel.ManagedInferenceModelException>
                        failureMeter) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.meter = Objects.requireNonNull(meter, "meter");
            this.failureMeter = Objects.requireNonNull(failureMeter, "failureMeter");
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            try {
                ChatResponse response = delegate.call(prompt);
                meter.accept(response);
                return response;
            } catch (ManagedInferenceChatModel.ManagedInferenceModelException failure) {
                try {
                    failureMeter.accept(failure);
                } catch (RuntimeException meteringFailure) {
                    meteringFailure.addSuppressed(failure);
                    throw meteringFailure;
                }
                throw failure;
            }
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return delegate.stream(prompt);
        }
    }
}
