package com.javaclaw.framework.springai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.framework.core.RunUsageLedger;
import com.javaclaw.framework.core.ReasoningEventSink;
import com.javaclaw.framework.core.StepEvents;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.spi.*;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Unified Spring AI path for router, GEPA, critic, distillation, vision and other helper calls. */
public final class SpringAiModelTaskGateway implements ModelTaskGateway {
    private static final Duration AUDIT_TIMEOUT = Duration.ofSeconds(1);
    private static final CancellableTaskExecutor INLINE_EXECUTOR = new CancellableTaskExecutor() {
        @Override public void execute(Runnable command) { Thread.startVirtualThread(command); }
        @Override public <T> CancellableTask<T> submit(String name, Duration timeout,
                CancellationToken cancellation, java.util.concurrent.Callable<T> action) {
            CompletableFuture<T> completion = new CompletableFuture<>();
            CompletableFuture<Void> termination = new CompletableFuture<>();
            Thread carrier = Thread.ofVirtual().name("javaclaw-inline-model-task").unstarted(() -> {
                try { completion.complete(action.call()); }
                catch (Throwable failure) { completion.completeExceptionally(failure); }
                finally { termination.complete(null); }
            });
            carrier.start();
            return new CancellableTask<>() {
                @Override public CompletionStage<T> completion() { return completion; }
                @Override public CompletionStage<Void> termination() { return termination; }
                @Override public boolean cancel() {
                    carrier.interrupt();
                    return completion.completeExceptionally(new java.util.concurrent.CancellationException());
                }
            };
        }
    };
    private final SpringAiModelRegistry models;
    private final RunUsageLedger usageLedger;
    private final ModelTaskAuditSink audit;
    private final ObjectMapper json;
    private final CancellableTaskExecutor executor;
    private final RunStore runs;
    private final Map<String, ModelTaskResult> cache = new ConcurrentHashMap<>();

    public SpringAiModelTaskGateway(
            SpringAiModelRegistry models,
            RunUsageLedger usageLedger,
            ModelTaskAuditSink audit,
            ObjectMapper json,
            CancellableTaskExecutor executor) {
        this(models, usageLedger, audit, json, executor, null);
    }

    public SpringAiModelTaskGateway(
            SpringAiModelRegistry models,
            RunUsageLedger usageLedger,
            ModelTaskAuditSink audit,
            ObjectMapper json,
            CancellableTaskExecutor executor,
            RunStore runs) {
        this.models = Objects.requireNonNull(models, "models");
        this.usageLedger = Objects.requireNonNull(usageLedger, "usageLedger");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.json = Objects.requireNonNull(json, "json");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.runs = runs;
    }

    @Override
    public CompletionStage<ModelTaskResult> execute(ModelTaskRequest request) {
        return execute(request, executor);
    }

    private CompletableFuture<ModelTaskResult> execute(
            ModelTaskRequest request, CancellableTaskExecutor carrier) {
        request.cancellation().throwIfCancelled();
        if (!activeOwner(request)) throw new IllegalStateException(
                "model task requires an active owner turn: " + request.ownerRunId());
        String cacheKey = cacheKey(request);
        audit.started(request);
        TaskLifecycle lifecycle = new TaskLifecycle(request);
        ModelTaskResult cached = request.cacheAllowed() ? cache.get(cacheKey) : null;
        CompletableFuture<ModelTaskResult> task;
        if (cached != null) {
            ModelTaskResult hit = new ModelTaskResult(cached.output(), cached.model(),
                    0, 0, true, cached.metadata());
            task = CompletableFuture.completedFuture(hit);
        } else {
            Duration timeout = effectiveTimeout(request);
            task = com.javaclaw.framework.core.CancellableTaskStages.submitReadOnly(
                    carrier, "model-task-" + request.purpose(), timeout,
                    request.cancellation(), () -> executeWithRetry(request, lifecycle));
        }
        ModelTaskFuture published = new ModelTaskFuture(request, lifecycle, task);
        task.whenComplete((result, failure) -> {
            if (!published.settling.compareAndSet(false, true)) return;
            Throwable cause = failure == null ? null : unwrap(failure);
            if (cause == null && (!activeOwner(request) || request.cancellation().cancelled()))
                cause = new RunCancelledException();
            if (cause != null) lifecycle.abandon(cause);
            Throwable original = cause;
            boundedAudit(() -> {
                if (original != null) {
                    lifecycle.failAttempt(original);
                    if (activeOwner(request)) audit.failed(request, original);
                } else {
                    if (cached != null && runs != null) {
                        StepId step = StepId.random();
                        var input = json.createObjectNode().put("purpose", request.purpose()).put("cacheHit", true);
                        input.set("input", request.input());
                        Attempt attempt = lifecycle.startAttempt(step, input);
                        var output = json.createObjectNode().put("model", result.model()).put("cacheHit", true);
                        output.set("value", result.output());
                        attempt.complete(output, json.createObjectNode().put("inputTokens", 0).put("outputTokens", 0));
                    }
                    if (activeOwner(request)) audit.completed(request, result);
                }
            }).whenComplete((ignored, auditFailure) -> {
                Throwable finalFailure = original;
                if (auditFailure != null) {
                    Throwable auditCause = unwrap(auditFailure);
                    if (finalFailure == null) finalFailure = auditCause;
                    else if (finalFailure != auditCause) finalFailure.addSuppressed(auditCause);
                }
                if (finalFailure != null) {
                    lifecycle.abandon(finalFailure);
                    published.completeExceptionally(finalFailure);
                } else if (!lifecycle.abandoned.get() && activeOwner(request)
                        && !request.cancellation().cancelled()) {
                    if (request.cacheAllowed()) cache.putIfAbsent(cacheKey, result);
                    published.complete(result);
                } else published.completeExceptionally(new RunCancelledException());
            });
        });
        return published;
    }

    @Override
    public ModelTaskResult executeInline(ModelTaskRequest request) {
        // 直接使用独立虚拟承载线程，避免主模型已持有 IO 配额时再申请同一配额。
        try { return execute(request, INLINE_EXECUTOR).join(); }
        catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof TimeoutException) throw new IllegalStateException(
                    "inline model task timed out: " + request.purpose(), cause);
            if (cause instanceof java.util.concurrent.CancellationException) throw new RunCancelledException();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("inline model task failed", cause);
        }
    }

    private ModelTaskResult executeWithRetry(ModelTaskRequest request, TaskLifecycle lifecycle) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= request.maxRetries(); attempt++) {
            lifecycle.requireActive();
            try {
                return call(request, attempt, lifecycle);
            } catch (RuntimeException failure) {
                last = failure;
                if (lifecycle.abandoned.get()
                        || failure instanceof com.javaclaw.framework.api.BudgetExceededException
                        || failure instanceof com.javaclaw.framework.api.TurnPausedException
                        || failure instanceof RunCancelledException) throw failure;
                if (attempt == request.maxRetries()) throw failure;
            }
        }
        throw Objects.requireNonNull(last);
    }

    private ModelTaskResult call(ModelTaskRequest request, int attempt, TaskLifecycle lifecycle) {
        // 逻辑超时不能释放此租约；提供方忽略中断时，同预算的后续物理请求仍须等待。
        try (var admitted = usageLedger.beginModelCall(request.ownerRunId())) {
            lifecycle.lease.set(admitted);
            if (lifecycle.abandoned.get()) admitted.markAbandoned();
            lifecycle.requireActive();
            return callAdmitted(request, attempt, admitted, lifecycle);
        } finally {
            lifecycle.lease.set(null);
        }
    }

    private ModelTaskResult callAdmitted(
            ModelTaskRequest request, int attempt, RunUsageLedger.ModelCall admitted, TaskLifecycle lifecycle) {
        String workspaceId = workspaceId(request);
        String modelPolicy = workspaceId == null
                ? models.policyFor(request.tier())
                : models.policyFor(workspaceId, request.tier());
        String system = """
                You are a bounded internal model task. Return one JSON value only.
                Omit Markdown code fences, explanations and reasoning. Do not call tools.
                The response must conform to this JSON Schema:
                """ + request.outputSchema();
        UserMessage user = buildUserMessage(request);
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), user));
        admitted.requireInputCapacity(ModelInputBudgetPreflight.approximatePromptFloor(prompt));
        StepId step = StepId.random();
        var stepInput = json.createObjectNode().put("purpose", request.purpose())
                .put("attempt", attempt).put("modelPolicy", modelPolicy)
                .put("inputHash", DeferredContextDigest.sha256(request.input().toString()));
        stepInput.set("messages", StepMessageCodec.messages(prompt.getInstructions()));
        stepInput.set("outputSchema", request.outputSchema());
        Attempt activeAttempt = lifecycle.startAttempt(step, stepInput);
        ChatResponse response;
        try {
            response = (workspaceId == null
                    ? models.require(request.tier())
                    : models.require(workspaceId, request.tier())).call(prompt);
            if (response == null) throw new IllegalStateException("model task returned no result");
        } catch (ManagedInferenceChatModel.ManagedInferenceModelException failure) {
            try { activeAttempt.fail(failure, StepMessageCodec.failureUsage(failure)); }
            catch (RuntimeException journalFailure) { failure.addSuppressed(journalFailure); }
            BigDecimal estimatedCost = BigDecimal.valueOf(
                    com.javaclaw.agent.PricingTable.estimateCostCny(failure.model(),
                            failure.usage().promptTokens(), failure.usage().completionTokens()));
            // 审计数据库失败不能跳过已经发生的提供方收费。
            try { activeAttempt.physicalUsage(StepMessageCodec.failureUsage(failure)); }
            catch (RuntimeException journalFailure) { failure.addSuppressed(journalFailure); }
            try {
                recordUsage(request, failure.model(), attempt, failure.usage().promptTokens(),
                        failure.usage().completionTokens(), estimatedCost);
            } catch (RuntimeException meteringFailure) {
                meteringFailure.addSuppressed(failure);
                throw meteringFailure;
            }
            throw failure;
        } catch (RuntimeException failure) {
            try { activeAttempt.fail(failure, null); }
            catch (RuntimeException journalFailure) { failure.addSuppressed(journalFailure); }
            throw failure;
        }
        if (response == null) {
            throw new IllegalStateException("model task returned no result");
        }
        Usage responseUsage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        long inputTokens = token(responseUsage == null ? null : responseUsage.getPromptTokens());
        long outputTokens = token(responseUsage == null ? null : responseUsage.getCompletionTokens());
        String actualModel = response.getMetadata() == null || response.getMetadata().getModel() == null
                ? modelPolicy : response.getMetadata().getModel();
        BigDecimal estimatedCost = BigDecimal.valueOf(
                com.javaclaw.agent.PricingTable.estimateCostCny(
                        actualModel, inputTokens, outputTokens));
        RuntimeException journalFailure = null;
        try {
            activeAttempt.complete(StepMessageCodec.response(response), StepMessageCodec.usage(response));
            activeAttempt.physicalUsage(StepMessageCodec.usage(response));
        }
        catch (RuntimeException failure) { journalFailure = failure; }
        try { recordUsage(request, actualModel, attempt, inputTokens, outputTokens, estimatedCost); }
        catch (RuntimeException failure) {
            if (journalFailure != null) failure.addSuppressed(journalFailure);
            throw failure;
        }
        if (journalFailure != null) throw journalFailure;
        lifecycle.requireActive();
        if (response.getResult() == null) {
            throw new IllegalStateException("model task returned no result");
        }
        if (!response.getResult().getOutput().getToolCalls().isEmpty()) {
            throw new ModelTaskOutputException(ModelTaskOutputException.Reason.SCHEMA_MISMATCH,
                    "model task returned native tool calls instead of a structured value", null);
        }
        String content = response.getResult().getOutput().getText();
        JsonNode output = validatedOutput(json, request.outputSchema(), content);
        return new ModelTaskResult(output, actualModel, inputTokens, outputTokens, false,
                Map.of("purpose", request.purpose(), "attempt", Integer.toString(attempt)));
    }

    private boolean activeOwner(ModelTaskRequest request) {
        return runs == null || runs.find(request.ownerRunId())
                .map(owner -> owner.snapshot().state() == com.javaclaw.framework.api.RunState.RUNNING)
                .orElse(false);
    }

    private boolean usageOwnerOpen(ModelTaskRequest request) {
        // PAUSED 保留可恢复的收费证据；真正结束的 run 只保留物理 ledger/observer 计账。
        return runs == null || runs.find(request.ownerRunId())
                .map(owner -> !owner.snapshot().state().terminal()).orElse(false);
    }

    /** 审计失败或忽略中断时也不能把公共结果重新绑到物理工作上。 */
    private static CompletableFuture<Void> boundedAudit(Runnable action) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Thread carrier = Thread.ofVirtual().name("javaclaw-model-task-audit").unstarted(() -> {
            try { action.run(); completed.complete(null); }
            catch (Throwable failure) { completed.completeExceptionally(failure); }
        });
        completed.orTimeout(AUDIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)
                .whenComplete((ignored, failure) -> {
                    if (failure instanceof TimeoutException) carrier.interrupt();
                });
        carrier.start();
        return completed;
    }

    private final class ModelTaskFuture extends CompletableFuture<ModelTaskResult> {
        private final AtomicBoolean settling = new AtomicBoolean();
        private final ModelTaskRequest request;
        private final TaskLifecycle lifecycle;
        private final CompletableFuture<ModelTaskResult> task;
        ModelTaskFuture(ModelTaskRequest request, TaskLifecycle lifecycle,
                        CompletableFuture<ModelTaskResult> task) {
            this.request = request;
            this.lifecycle = lifecycle;
            this.task = task;
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (!settling.compareAndSet(false, true)) return false;
            var failure = new java.util.concurrent.CancellationException("只读模型任务已取消");
            lifecycle.abandon(failure);
            boolean cancelled = super.cancel(false);
            Thread.startVirtualThread(() -> task.cancel(true));
            boundedAudit(() -> {
                lifecycle.failAttempt(failure);
                if (activeOwner(request)) audit.failed(request, failure);
            });
            return cancelled;
        }
    }

    private final class TaskLifecycle {
        private final ModelTaskRequest request;
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicReference<Attempt> active = new AtomicReference<>();
        private final AtomicReference<RunUsageLedger.ModelCall> lease = new AtomicReference<>();
        TaskLifecycle(ModelTaskRequest request) { this.request = request; }
        void abandon(Throwable failure) {
            abandoned.set(true);
            RunUsageLedger.ModelCall current = lease.get();
            if (current != null) current.markAbandoned();
        }
        void requireActive() {
            request.cancellation().throwIfCancelled();
            if (abandoned.get() || !activeOwner(request)) throw new RunCancelledException();
        }
        Attempt startAttempt(StepId step, JsonNode input) {
            requireActive();
            Attempt attempt = new Attempt(this, step);
            active.set(attempt);
            try {
                StepEvents.started(attempt.events, step, AgentStep.Kind.MODEL_TASK, input, null);
                attempt.started.complete(null);
            } catch (Throwable failure) {
                attempt.started.completeExceptionally(failure);
                throw failure;
            }
            requireActive();
            return attempt;
        }
        void failAttempt(Throwable failure) {
            Attempt attempt = active.get();
            if (attempt != null) attempt.fail(failure, null);
        }
    }

    private final class Attempt {
        private final TaskLifecycle lifecycle;
        private final StepId step;
        private final AtomicBoolean settled = new AtomicBoolean();
        private final CompletableFuture<Void> started = new CompletableFuture<>();
        private final ReasoningEventSink events;
        Attempt(TaskLifecycle lifecycle, StepId step) {
            this.lifecycle = lifecycle;
            this.step = step;
            // append 的 RUNNING 前置条件保证晚到模型事件不能写入已经结束的 run。
            events = (type, version, producer, payload) -> {
                if (runs == null) return;
                var stored = runs.find(lifecycle.request.ownerRunId()).orElseThrow();
                var appended = runs.append(lifecycle.request.ownerRunId(),
                        java.util.Set.of(com.javaclaw.framework.api.RunState.RUNNING),
                        com.javaclaw.framework.api.RunState.RUNNING,
                        new RunEventDraft(type, version, producer,
                                stored.request().linkage().correlationId(), null, payload), null, null);
                if (appended.isEmpty()) throw new RunCancelledException();
            };
        }
        void complete(JsonNode output, JsonNode usage) {
            if (lifecycle.abandoned.get() || !activeOwner(lifecycle.request)
                    || !settled.compareAndSet(false, true)) return;
            StepEvents.completed(events, step, output, usage);
        }
        void fail(Throwable failure, JsonNode usage) {
            if (!settled.compareAndSet(false, true)) return;
            started.join();
            if (activeOwner(lifecycle.request)) StepEvents.failed(events, step, failure, usage);
        }
        void physicalUsage(JsonNode usage) {
            if (runs == null || !lifecycle.abandoned.get()) return;
            var payload = json.createObjectNode().put("stepId", step.value()).put("physicalCallSettled", true);
            payload.set("usage", usage);
            // 复用按 stepId 覆盖计量的恢复协议；失败步骤仍保持 FAILED，不补写 COMPLETED。
            java.util.function.Supplier<Void> persist = () -> {
                for (int attempt = 0; attempt < 3; attempt++) {
                    var stored = runs.find(lifecycle.request.ownerRunId()).orElseThrow();
                    if (stored.snapshot().state().terminal()) return null;
                    var appended = runs.append(lifecycle.request.ownerRunId(),
                            java.util.Set.of(stored.snapshot().state()), stored.snapshot().state(),
                            new RunEventDraft("core.step.usage", 1, "framework.core",
                                    stored.request().linkage().correlationId(), null, payload), null, null);
                    if (appended.isPresent()) return null;
                }
                throw new IllegalStateException("晚到模型费用未能持久化：所属任务状态持续变化");
            };
            try { runs.withRunAcceptanceLock(lifecycle.request.ownerRunId(), persist); }
            catch (UnsupportedOperationException unsupportedFence) {
                // 兼容无原子 fence 的存储端口；只使用当前状态的 CAS，不改变任务状态。
                persist.get();
            }
        }
    }

    private String workspaceId(ModelTaskRequest request) {
        if (runs == null) return null;
        return runs.find(request.ownerRunId())
                .map(stored -> stored.request().scope().workspaceId())
                .orElseThrow(() -> new IllegalStateException(
                        "owner run not found for model task: " + request.ownerRunId()));
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
            String model,
            int attempt,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCost) {
        RuntimeException ledgerFailure = null;
        try {
            usageLedger.record(
                    request.ownerRunId(), inputTokens, outputTokens, estimatedCost);
        } catch (RuntimeException failure) {
            ledgerFailure = failure;
        }
        try {
            if (usageOwnerOpen(request)) audit.usage(request, model, attempt, inputTokens, outputTokens, estimatedCost);
        } catch (RuntimeException auditFailure) {
            if (ledgerFailure == null) throw auditFailure;
            ledgerFailure.addSuppressed(auditFailure);
        }
        if (ledgerFailure != null) throw ledgerFailure;
    }

    /** Apply the same parse and schema checks when recovering a completed provider step. */
    static JsonNode validatedOutput(ObjectMapper json, JsonNode schema, String content) {
        String value = StructuredModelOutputNormalizer.normalize(content);
        JsonNode output;
        try {
            output = parseSingleJsonValue(json, value);
        } catch (Exception failure) {
            throw new ModelTaskOutputException(ModelTaskOutputException.Reason.INVALID_JSON,
                    "model task did not return JSON", failure);
        }
        var validation = new JsonSchemaValidator().validate(schema, output, "/output");
        if (!validation.isEmpty()) {
            throw new ModelTaskOutputException(ModelTaskOutputException.Reason.SCHEMA_MISMATCH,
                    "model task output schema mismatch: " + validation, null);
        }
        return output;
    }

    /** Recognize whole response wrappers only; never search prose for a JSON fragment. */
    private static final class StructuredModelOutputNormalizer {
        private static final Pattern JSON_CODE_BLOCK = Pattern.compile(
                "\\A```[\\t ]*(?:json[\\t ]*)?\\R(.*?)\\R```[\\t ]*\\z",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final String TOOL_CALL_OPEN = "<tool_call>";
        private static final String TOOL_CALL_CLOSE = "</tool_call>";

        private static String normalize(String content) {
            String value = content == null ? "" : content.trim();
            if (value.startsWith(TOOL_CALL_OPEN)) {
                String body = value.substring(TOOL_CALL_OPEN.length()).trim();
                if (body.endsWith(TOOL_CALL_CLOSE)) {
                    body = body.substring(0, body.length() - TOOL_CALL_CLOSE.length()).trim();
                }
                // The caller still requires one complete JSON value and its schema.
                return body;
            }
            var fenced = JSON_CODE_BLOCK.matcher(value);
            return fenced.matches() ? fenced.group(1).trim() : value;
        }
    }

    private static JsonNode parseSingleJsonValue(ObjectMapper json, String value) throws IOException {
        try (JsonParser parser = json.getFactory().createParser(value)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode output = json.readTree(parser);
            if (output == null || parser.nextToken() != null) {
                throw new IOException("model task returned no JSON value or multiple values");
            }
            return output;
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
