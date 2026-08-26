package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.core.RunUsageLedger;
import com.javaclaw.framework.spi.CancellableTask;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ModelTaskAuditSink;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.framework.spi.ModelUsageCommitException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringAiModelTaskGatewayTest {

    @Test
    void rejectedResponsesAreMeteredBeforeParsingAndRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> calls.getAndIncrement() == 0
                ? response("not-json", 7, 3)
                : response("{\"ok\":true}", 5, 2);
        Fixture fixture = fixture(model, RunBudget.UNBOUNDED);

        ModelTaskResult result = fixture.gateway.execute(request(fixture.runId, 1))
                .toCompletableFuture().get();

        assertEquals(2, calls.get());
        assertEquals(2, fixture.audit.usageCalls.get());
        assertEquals(12, fixture.ledger.snapshot(fixture.runId).inputTokens());
        assertEquals(5, fixture.ledger.snapshot(fixture.runId).outputTokens());
        assertEquals("1", result.metadata().get("attempt"));
    }

    @Test
    void budgetFailureIsPersistedAndNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            return response("{\"ok\":true}", 7, 3);
        };
        Fixture fixture = fixture(model, new RunBudget(
                Duration.ofMinutes(1), 5, 100, 1, BigDecimal.TEN));

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                fixture.gateway.execute(request(fixture.runId, 3))
                        .toCompletableFuture().get());

        assertInstanceOf(BudgetExceededException.class, failure.getCause());
        assertEquals(1, calls.get());
        assertEquals(1, fixture.audit.usageCalls.get());
        assertEquals(7, fixture.ledger.snapshot(fixture.runId).inputTokens());
    }

    @Test
    void failedManagedInferenceAttemptsAreMeteredBeforeRetryAndBudgetStopsFurtherAttempts() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel alwaysFails = prompt -> {
            calls.incrementAndGet();
            throw new ManagedInferenceChatModel.ManagedInferenceModelException(
                    "inference_error", "failed", true,
                    new com.javaclaw.inference.api.InferenceUsage(4, 1),
                    "deliverance:test", null);
        };
        Fixture retrying = fixture(alwaysFails, RunBudget.UNBOUNDED);
        assertThrows(ExecutionException.class, () -> retrying.gateway.execute(
                request(retrying.runId, 1)).toCompletableFuture().get());
        assertEquals(2, calls.get());
        assertEquals(8, retrying.ledger.snapshot(retrying.runId).inputTokens());
        assertEquals(2, retrying.ledger.snapshot(retrying.runId).outputTokens());
        assertEquals(2, retrying.audit.usageCalls.get());

        calls.set(0);
        Fixture budgeted = fixture(alwaysFails, new RunBudget(
                Duration.ofMinutes(1), 3, 100, 1, BigDecimal.TEN));
        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                budgeted.gateway.execute(request(budgeted.runId, 3)).toCompletableFuture().get());
        assertInstanceOf(BudgetExceededException.class, failure.getCause());
        assertEquals(1, calls.get());
        assertEquals(4, budgeted.ledger.snapshot(budgeted.runId).inputTokens());
    }

    @Test
    void genericProviderFailuresCountCallsWithoutInventingTokens() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel alwaysFails = prompt -> {
            calls.incrementAndGet();
            throw new IllegalStateException("provider unavailable");
        };
        Fixture fixture = fixture(alwaysFails, RunBudget.UNBOUNDED);

        assertThrows(ExecutionException.class, () -> fixture.gateway.execute(
                request(fixture.runId, 1)).toCompletableFuture().get());
        assertEquals(2, calls.get());
        assertEquals(2, fixture.audit.usageCalls.get());
        assertEquals(0, fixture.ledger.snapshot(fixture.runId).inputTokens());
        assertEquals(0, fixture.ledger.snapshot(fixture.runId).outputTokens());
        assertEquals(2, fixture.ledger.snapshot(fixture.runId).modelCalls());
    }

    @Test
    void anthropicUsesNormalizedInputForBudgetsButRawInputForExistingPricing() throws Exception {
        ChatModel model = prompt -> new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"ok\":true}"))),
                ChatResponseMetadata.builder().model("qwen-plus")
                        .usage(new DefaultUsage(100, 40, 190,
                                Map.of("cache_read_input_tokens", 30,
                                        "cache_creation_input_tokens", 20),
                                30L, 20L))
                        .build());
        Fixture fixture = fixture(model, RunBudget.UNBOUNDED);

        ModelTaskResult result = fixture.gateway.execute(request(fixture.runId, 0))
                .toCompletableFuture().get();

        BigDecimal expectedCost = BigDecimal.valueOf(
                com.javaclaw.agent.PricingTable.estimateCostCny("qwen-plus", 100, 40));
        assertEquals(150, result.inputTokens());
        assertEquals(150, fixture.ledger.snapshot(fixture.runId).inputTokens());
        assertEquals(100, fixture.audit.pricingInput.get());
        assertEquals(0, expectedCost.compareTo(fixture.audit.estimatedCost.get()));
        assertEquals(0, expectedCost.compareTo(fixture.ledger.snapshot(fixture.runId).cost()));
    }

    @Test
    void usageCommitFailureDoesNotIssueASecondProviderCall() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            return response("{\"ok\":true}", 7, 3);
        };
        SpringAiModelRegistry registry = new SpringAiModelRegistry();
        registry.register("test:model", model);
        registry.route(ModelTier.LIGHT, "test:model");
        RunId runId = new RunId("model-task-commit-failure");
        RunUsageLedger ledger = new RunUsageLedger();
        ledger.open(runId, RunBudget.UNBOUNDED, new RunScope("workspace", "user", "session"));
        ModelTaskAuditSink rejecting = new RecordingAudit() {
            @Override public boolean commitUsage(
                    ModelTaskRequest request, String modelCallId, String model, int attempt,
                    com.javaclaw.framework.api.ModelTokenUsage usage,
                    BigDecimal estimatedCostCny) {
                return false;
            }
        };
        SpringAiModelTaskGateway gateway = new SpringAiModelTaskGateway(
                registry, ledger, rejecting, new ObjectMapper(), new DirectExecutor());

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                gateway.execute(request(runId, 3)).toCompletableFuture().get());

        assertInstanceOf(ModelUsageCommitException.class, failure.getCause());
        assertEquals(1, calls.get());
        assertEquals(0, ledger.snapshot(runId).modelCalls());
    }

    private static Fixture fixture(ChatModel model, RunBudget budget) {
        SpringAiModelRegistry registry = new SpringAiModelRegistry();
        registry.register("test:model", model);
        registry.route(ModelTier.LIGHT, "test:model");
        RunId runId = new RunId("model-task-owner-" + System.nanoTime());
        RunUsageLedger ledger = new RunUsageLedger();
        ledger.open(runId, budget, new RunScope("workspace", "user", "session"));
        RecordingAudit audit = new RecordingAudit();
        SpringAiModelTaskGateway gateway = new SpringAiModelTaskGateway(
                registry, ledger, audit, new ObjectMapper(), new DirectExecutor());
        return new Fixture(gateway, ledger, audit, runId);
    }

    private static ModelTaskRequest request(RunId owner, int retries) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("ok").put("type", "boolean");
        schema.putArray("required").add("ok");
        schema.put("additionalProperties", false);
        return new ModelTaskRequest("test", ModelTier.LIGHT,
                JsonNodeFactory.instance.objectNode().put("value", 1), schema,
                owner, "test", Duration.ofSeconds(5), retries, () -> false, false);
    }

    private static ChatResponse response(String content, int inputTokens, int outputTokens) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))),
                ChatResponseMetadata.builder().model("unknown-test-model")
                        .usage(new DefaultUsage(inputTokens, outputTokens)).build());
    }

    private record Fixture(
            SpringAiModelTaskGateway gateway,
            RunUsageLedger ledger,
            RecordingAudit audit,
            RunId runId) { }

    private static class RecordingAudit implements ModelTaskAuditSink {
        private final AtomicInteger usageCalls = new AtomicInteger();
        private final AtomicLong pricingInput = new AtomicLong();
        private final AtomicReference<BigDecimal> estimatedCost =
                new AtomicReference<>(BigDecimal.ZERO);
        @Override public void started(ModelTaskRequest request) { }
        @Override public void usage(ModelTaskRequest request, String model, int attempt,
                                    long inputTokens, long outputTokens,
                                    BigDecimal estimatedCostCny) {
            usageCalls.incrementAndGet();
        }
        @Override public boolean commitUsage(
                ModelTaskRequest request, String modelCallId, String model, int attempt,
                java.time.Instant occurredAt,
                com.javaclaw.framework.api.ModelTokenUsage usage,
                long pricingInputTokens, BigDecimal estimatedCostCny) {
            pricingInput.set(pricingInputTokens);
            estimatedCost.set(estimatedCostCny);
            return commitUsage(
                    request, modelCallId, model, attempt, usage, estimatedCostCny);
        }
        @Override public void completed(ModelTaskRequest request, ModelTaskResult result) { }
        @Override public void failed(ModelTaskRequest request, Throwable failure) { }
    }

    private static final class DirectExecutor implements CancellableTaskExecutor {
        @Override public void execute(Runnable command) { command.run(); }

        @Override
        public <T> CancellableTask<T> submit(
                String name, Duration timeout, CancellationToken cancellation, Callable<T> task) {
            CompletableFuture<T> completion = new CompletableFuture<>();
            try {
                cancellation.throwIfCancelled();
                completion.complete(task.call());
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
            return new CancellableTask<>() {
                @Override public java.util.concurrent.CompletionStage<T> completion() {
                    return completion;
                }
                @Override public java.util.concurrent.CompletionStage<Void> termination() {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public boolean cancel() { return false; }
            };
        }
    }
}
