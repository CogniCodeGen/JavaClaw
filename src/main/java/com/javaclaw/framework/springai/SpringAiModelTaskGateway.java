package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.ModelTokenUsage;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.framework.core.RunUsageLedger;
import com.javaclaw.framework.core.RunUsageAccountRestorer;
import com.javaclaw.framework.spi.*;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Unified Spring AI path for router, GEPA, critic, distillation, vision and other helper calls. */
public final class SpringAiModelTaskGateway implements ModelTaskGateway {
    private final SpringAiModelRegistry models;
    private final RunUsageLedger usageLedger;
    private final ModelTaskAuditSink audit;
    private final ObjectMapper json;
    private final CancellableTaskExecutor executor;
    private final RunStore runs;
    private final RunUsageAccountRestorer usageAccounts;
    private final Clock clock;
    private final JsonSchemaValidator schemas = new JsonSchemaValidator();
    private final Map<String, ModelTaskResult> cache = new ConcurrentHashMap<>();

    public SpringAiModelTaskGateway(
            SpringAiModelRegistry models,
            RunUsageLedger usageLedger,
            ModelTaskAuditSink audit,
            ObjectMapper json,
            CancellableTaskExecutor executor) {
        this(models, usageLedger, audit, json, executor, null, null, Clock.systemUTC());
    }

    public SpringAiModelTaskGateway(
            SpringAiModelRegistry models,
            RunUsageLedger usageLedger,
            ModelTaskAuditSink audit,
            ObjectMapper json,
            CancellableTaskExecutor executor,
            RunStore runs) {
        this(models, usageLedger, audit, json, executor, runs, null, Clock.systemUTC());
    }

    public SpringAiModelTaskGateway(
            SpringAiModelRegistry models,
            RunUsageLedger usageLedger,
            ModelTaskAuditSink audit,
            ObjectMapper json,
            CancellableTaskExecutor executor,
            RunStore runs,
            RunUsageAccountRestorer usageAccounts) {
        this(models, usageLedger, audit, json, executor, runs, usageAccounts, Clock.systemUTC());
    }

    public SpringAiModelTaskGateway(
            SpringAiModelRegistry models,
            RunUsageLedger usageLedger,
            ModelTaskAuditSink audit,
            ObjectMapper json,
            CancellableTaskExecutor executor,
            RunStore runs,
            RunUsageAccountRestorer usageAccounts,
            Clock clock) {
        this.models = Objects.requireNonNull(models, "models");
        this.usageLedger = Objects.requireNonNull(usageLedger, "usageLedger");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.json = Objects.requireNonNull(json, "json");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.runs = runs;
        this.usageAccounts = usageAccounts;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<ModelTaskResult> execute(ModelTaskRequest request) {
        request.cancellation().throwIfCancelled();
        validateOwnerState(request);
        String cacheKey = cacheKey(request);
        if (request.cacheAllowed()) {
            ModelTaskResult cached = cache.get(cacheKey);
            if (cached != null) {
                ModelTaskResult hit = new ModelTaskResult(cached.output(), cached.model(),
                        0, 0, true, cached.metadata());
                audit.completed(request, hit);
                return CompletableFuture.completedFuture(hit);
            }
        }
        if (request.attribution() == ModelTaskAttribution.BACKGROUND
                && usageAccounts != null) {
            // Fail before issuing a paid provider request if its durable owner cannot be restored.
            usageAccounts.ensureAccount(request.ownerRunId());
        }
        audit.started(request);
        String taskId = java.util.UUID.randomUUID().toString();
        Duration timeout = effectiveTimeout(request);
        CompletableFuture<ModelTaskResult> task =
                com.javaclaw.framework.core.CancellableTaskStages.submit(
                        executor, "model-task-" + request.purpose(), timeout,
                        request.cancellation(), () -> executeWithRetry(request, taskId));
        task.whenComplete((result, failure) -> {
            if (failure != null) audit.failed(request, unwrap(failure));
            else {
                if (request.cacheAllowed()) cache.putIfAbsent(cacheKey, result);
                audit.completed(request, result);
            }
        });
        return task;
    }

    private ModelTaskResult executeWithRetry(ModelTaskRequest request, String taskId) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= request.maxRetries(); attempt++) {
            request.cancellation().throwIfCancelled();
            try {
                return call(request, attempt, taskId);
            } catch (RuntimeException failure) {
                last = failure;
                if (failure instanceof com.javaclaw.framework.api.BudgetExceededException
                        || failure instanceof ModelUsageCommitException
                        || failure instanceof RunCancelledException) throw failure;
                if (attempt == request.maxRetries()) throw failure;
            }
        }
        throw Objects.requireNonNull(last);
    }

    private ModelTaskResult call(ModelTaskRequest request, int attempt, String taskId) {
        String modelCallId = "task:" + taskId + ":" + attempt;
        String workspaceId = workspaceId(request);
        String modelPolicy = workspaceId == null
                ? models.policyFor(request.tier())
                : models.policyFor(workspaceId, request.tier());
        String system = """
                You are a bounded internal model task. Return JSON only. Do not call tools.
                The response must conform to this JSON Schema:
                """ + request.outputSchema();
        UserMessage user = buildUserMessage(request);
        org.springframework.ai.chat.model.ChatModel taskModel = workspaceId == null
                ? models.require(request.tier())
                : models.require(workspaceId, request.tier());
        ChatResponse response;
        try {
            response = taskModel.call(new Prompt(List.of(
                    new SystemMessage(system), user)));
        } catch (ManagedInferenceChatModel.ManagedInferenceModelException failure) {
            BigDecimal estimatedCost = BigDecimal.valueOf(
                    com.javaclaw.agent.PricingTable.estimateCostCny(failure.model(),
                            failure.usage().promptTokens(), failure.usage().completionTokens()));
            try {
                recordUsage(request, modelCallId, failure.model(), attempt, new ModelTokenUsage(
                        failure.usage().promptTokens(), 0, 0,
                        failure.usage().completionTokens(), 0, 1),
                        failure.usage().promptTokens(), estimatedCost);
            } catch (RuntimeException meteringFailure) {
                meteringFailure.addSuppressed(failure);
                throw meteringFailure;
            }
            throw failure;
        } catch (RuntimeException failure) {
            try {
                recordUsage(request, modelCallId, modelPolicy, attempt,
                        new ModelTokenUsage(0, 0, 0, 0, 0, 1), 0, BigDecimal.ZERO);
            } catch (RuntimeException meteringFailure) {
                meteringFailure.addSuppressed(failure);
                throw meteringFailure;
            }
            throw failure;
        }
        if (response == null) {
            throw new IllegalStateException("model task returned no result");
        }
        Usage responseUsage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        ExtractedModelUsage extracted = ModelTokenUsageExtractor.extractDetailed(responseUsage, json);
        ModelTokenUsage usage = extracted.usage();
        long inputTokens = usage.inputTokens();
        long outputTokens = usage.outputTokens();
        String actualModel = response.getMetadata() == null || response.getMetadata().getModel() == null
                ? modelPolicy : response.getMetadata().getModel();
        BigDecimal estimatedCost = BigDecimal.valueOf(
                com.javaclaw.agent.PricingTable.estimateCostCny(
                        actualModel, extracted.pricingInputTokens(), outputTokens));
        recordUsage(request, modelCallId, actualModel, attempt, usage,
                extracted.pricingInputTokens(), estimatedCost);
        if (response.getResult() == null) {
            throw new IllegalStateException("model task returned no result");
        }
        String content = response.getResult().getOutput().getText();
        JsonNode output = parseJson(content);
        var validation = schemas.validate(request.outputSchema(), output, "/output");
        if (!validation.isEmpty()) {
            throw new IllegalStateException("model task output schema mismatch: " + validation);
        }
        return new ModelTaskResult(output, actualModel, inputTokens, outputTokens, false,
                Map.of("purpose", request.purpose(), "attempt", Integer.toString(attempt)));
    }

    private String workspaceId(ModelTaskRequest request) {
        if (runs == null) return null;
        return runs.find(request.ownerRunId())
                .map(stored -> stored.request().scope().workspaceId())
                .orElseThrow(() -> new IllegalStateException(
                        "owner run not found for model task: " + request.ownerRunId()));
    }

    private void validateOwnerState(ModelTaskRequest request) {
        if (runs == null || request.attribution() == ModelTaskAttribution.BACKGROUND) return;
        runs.find(request.ownerRunId()).ifPresent(stored -> {
            if (stored.snapshot().state().terminal()) {
                throw new IllegalStateException(
                        "inline model task cannot start after its owner Run completed: "
                                + request.ownerRunId());
            }
        });
    }

    private static Duration effectiveTimeout(ModelTaskRequest request) {
        Duration ownerRemaining = request.cancellation().remaining();
        request.cancellation().throwIfCancelled();
        Duration timeout = request.timeout().compareTo(ownerRemaining) <= 0
                ? request.timeout() : ownerRemaining;
        if (timeout.isZero() || timeout.isNegative()) {
            request.cancellation().throwIfCancelled();
            return Duration.ofNanos(1);
        }
        return timeout;
    }

    private void recordUsage(
            ModelTaskRequest request,
            String modelCallId,
            String model,
            int attempt,
            ModelTokenUsage usage,
            long pricingInputTokens,
            BigDecimal estimatedCost) {
        Instant occurredAt = clock.instant();
        boolean committed;
        try {
            committed = audit.commitUsage(
                    request, modelCallId, model, attempt, occurredAt,
                    usage, pricingInputTokens, estimatedCost);
        } catch (RuntimeException auditFailure) {
            throw new ModelUsageCommitException(
                    "could not commit model-task usage for " + modelCallId, auditFailure);
        }
        if (!committed) {
            throw new ModelUsageCommitException(
                    "owner Run did not accept model-task usage for " + modelCallId);
        }
        usageLedger.recordOnce(request.ownerRunId(), modelCallId, occurredAt,
                usage, pricingInputTokens, estimatedCost);
    }

    private JsonNode parseJson(String content) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int closing = value.lastIndexOf("```");
            if (firstNewline >= 0 && closing > firstNewline) {
                value = value.substring(firstNewline + 1, closing).trim();
            }
        }
        try {
            return json.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException("model task did not return JSON", failure);
        }
    }

    private static String cacheKey(ModelTaskRequest request) {
        // Model-task inputs may contain workspace/user data. Keep reuse run-local unless a future
        // cache SPI can prove a broader tenant-safe scope.
        return request.ownerRunId().value() + "\n" + request.purpose() + "\n"
                + request.tier() + "\n"
                + request.input() + "\n" + request.mediaInputs().hashCode()
                + "\n" + request.outputSchema();
    }

    private static UserMessage buildUserMessage(ModelTaskRequest request) {
        StringBuilder text = new StringBuilder("Purpose: ").append(request.purpose())
                .append("\nInput:\n").append(request.input());
        List<Media> media = new ArrayList<>();
        for (InputBlock block : request.mediaInputs()) {
            if (!block.type().equals("core.image") && !block.type().equals("core.audio")) {
                throw new IllegalArgumentException(
                        "model task media must be core.image or core.audio: " + block.type());
            }
            JsonNode data = block.data();
            String name = data.path("name").asText("media");
            String mediaType = data.path("mediaType").asText("application/octet-stream");
            Media.Builder builder = Media.builder().name(name)
                    .mimeType(MimeTypeUtils.parseMimeType(mediaType));
            String inline = data.path("base64").asText("");
            String uri = data.path("uri").asText("");
            if (!inline.isBlank()) builder.data(Base64.getDecoder().decode(inline));
            else if (!uri.isBlank()) builder.data(URI.create(uri));
            else throw new IllegalArgumentException("model task media has no base64 or uri: " + name);
            media.add(builder.build());
            text.append("\n[Media: ").append(name).append("; ").append(mediaType).append(']');
        }
        UserMessage.Builder builder = UserMessage.builder().text(text.toString());
        if (!media.isEmpty()) builder.media(media);
        return builder.build();
    }

    private static long token(Integer value) {
        return value == null ? 0L : Math.max(0, value.longValue());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
