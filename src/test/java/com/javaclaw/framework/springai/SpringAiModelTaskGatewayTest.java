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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiModelTaskGatewayTest {

    @Test
    void rejectsReasoningPreambleEvenWhenAValidJsonObjectFollows() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var schema = json.readTree(OnDemandContextPlanner.STAGE_TWO_V2_SCHEMA);

        String output = """
                We need to choose exact context IDs and retrieved tool candidate IDs.
                The best match for observing the desktop session is t0.
                historyIds and sourceIds should be empty. Return JSON only. Final must conform.

                {
                  "historyIds": [],
                  "sourceIds": [],
                  "toolAction": "direct",
                  "toolIds": ["t0"]
                }
                """;
        assertThrows(IllegalStateException.class, () ->
                SpringAiModelTaskGateway.validatedOutput(json, schema, output));
        var parsed = SpringAiModelTaskGateway.validatedOutput(json, schema,
                "{\"historyIds\":[],\"sourceIds\":[],\"toolAction\":\"direct\",\"toolIds\":[\"t0\"]}");
        assertEquals("direct", parsed.path("toolAction").asText());
        assertEquals(ModelTaskOutputException.Reason.INVALID_JSON,
                assertThrows(ModelTaskOutputException.class, () ->
                SpringAiModelTaskGateway.validatedOutput(json, schema,
                        output + "Trailing explanation")).reason());
        assertEquals(ModelTaskOutputException.Reason.INVALID_JSON,
                assertThrows(ModelTaskOutputException.class, () ->
                SpringAiModelTaskGateway.validatedOutput(json, schema,
                        "Earlier candidate:\n{\"historyIds\":[],\"sourceIds\":[],"
                                + "\"toolAction\":\"none\",\"toolIds\":[]}\nFinal:\n"
                                + "{\"historyIds\":[],\"sourceIds\":[],"
                                + "\"toolAction\":\"direct\",\"toolIds\":[\"t0\"]}"))
                .reason());
        assertEquals(ModelTaskOutputException.Reason.INVALID_JSON,
                assertThrows(ModelTaskOutputException.class, () ->
                SpringAiModelTaskGateway.validatedOutput(json, schema,
                        "Explanation\n{\"historyIds\":[],\"sourceIds\":[],"
                                + "\"toolAction\":123,\"toolIds\":[\"t0\"]}"))
                .reason());
    }

    @Test
    void completeJsonFencesPreserveTheParsedTree() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var schema = request(RunId.random(), 0).outputSchema();
        var expected = json.readTree("{\"ok\":true}");
        for (String output : List.of(
                "```json\n{\"ok\":true}\n```",
                "```\n{\"ok\":true}\n```",
                "``` json \n{\"ok\":true}\n```",
                " \t\r\n```JsOn\r\n  {\"ok\":true}  \r\n```\r\n \t")) {
            assertEquals(expected, SpringAiModelTaskGateway.validatedOutput(json, schema, output));
        }
    }

    @Test
    void realRefineV2ShapeInAJsonFenceMatchesTheBareJsonTree() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var schema = json.readTree(OnDemandContextPlanner.STAGE_TWO_V2_SCHEMA);
        // Mirrors a complete refine_v2 response; the exchange identifier is anonymized.
        String bare = """
                {
                  "historyIds": ["exchange:test"],
                  "sourceIds": [],
                  "toolAction": "direct",
                  "toolIds": ["t0"]
                }
                """;
        String fenced = "```json\n" + bare + "```";

        assertEquals(json.readTree(bare),
                SpringAiModelTaskGateway.validatedOutput(json, schema, fenced));
    }

    @Test
    void singleExactToolCallWrappersPreserveTheStructuredPlanningValue() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var schema = json.readTree(OnDemandContextPlanner.STAGE_TWO_V2_SCHEMA);
        String bare = "{\"historyIds\":[\"exchange:test\"],\"sourceIds\":[],"
                + "\"toolAction\":\"direct\",\"toolIds\":[\"t5\"]}";
        for (String output : List.of(
                "<tool_call>\n\n\n\n" + bare,
                "<tool_call>" + bare + "</tool_call>",
                " \t\r\n<tool_call>\r\n " + bare + " \r\n</tool_call> \r\n\t")) {
            assertEquals(json.readTree(bare),
                    SpringAiModelTaskGateway.validatedOutput(json, schema, output));
        }
        assertEquals("literal </tool_call>", SpringAiModelTaskGateway.validatedOutput(json,
                json.readTree("{\"type\":\"string\"}"),
                "<tool_call>\"literal </tool_call>\"</tool_call>").asText());
    }

    @Test
    void toolCallWrappersRejectAmbiguousForeignAndPartialFormats() {
        ObjectMapper json = new ObjectMapper();
        var schema = request(RunId.random(), 0).outputSchema();
        String bare = "{\"ok\":true}";
        for (String output : List.of(
                "<tool_call><tool_call>" + bare,
                "<tool_call>" + bare + "</tool_call><tool_call>" + bare + "</tool_call>",
                "<tool_call>" + bare + "</tool_call></tool_call>",
                "<tool_call>" + bare + bare,
                "<tool_call>" + bare + "Explanation",
                "<tool_call>" + bare + "</tool_call>Explanation",
                "<tool_call>Explanation " + bare,
                "<tool_call>" + bare + "</tool_call",
                "<tool_call>" + bare + "</tool_response>",
                "<tool_call>", "<tool_call></tool_call>",
                "<tool_call name=\"planner\">" + bare + "</tool_call>",
                "<tool_call >" + bare, "<TOOL_CALL>" + bare,
                "<html><body>" + bare + "</body></html>",
                "<think>reasoning</think>" + bare,
                "<tool_call><html>" + bare + "</html></tool_call>",
                "Before <tool_call>" + bare + "</tool_call>",
                "<tool_call>```json\n" + bare + "\n```</tool_call>",
                "```json\n<tool_call>" + bare + "</tool_call>\n```",
                "<tool_call>{\"ok\":false,\"ok\":true}</tool_call>")) {
            assertEquals(ModelTaskOutputException.Reason.INVALID_JSON,
                    assertThrows(ModelTaskOutputException.class, () ->
                            SpringAiModelTaskGateway.validatedOutput(json, schema, output)).reason());
        }
    }

    @Test
    void wrappedJsonStillRequiresTheSchemaAndToolArgumentsAreNeverExtracted() {
        ObjectMapper json = new ObjectMapper();
        var schema = request(RunId.random(), 0).outputSchema();
        for (String output : List.of(
                "<tool_call>{\"ok\":42}",
                "<tool_call>{\"ok\":true,\"extra\":1}</tool_call>",
                "<tool_call>{\"name\":\"desktop_session_click\","
                        + "\"arguments\":{\"ok\":true}}</tool_call>")) {
            assertEquals(ModelTaskOutputException.Reason.SCHEMA_MISMATCH,
                    assertThrows(ModelTaskOutputException.class, () ->
                            SpringAiModelTaskGateway.validatedOutput(json, schema, output)).reason());
        }
    }

    @Test
    void nativeToolCallsAreRejectedEvenWithValidJsonAndTheirUsageIsMetered() {
        ChatModel model = prompt -> new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("{\"ok\":true}")
                .toolCalls(List.of(new AssistantMessage.ToolCall("unexpected", "function",
                        "desktop_session_click", "{}"))).build())),
                ChatResponseMetadata.builder().model("unknown-test-model")
                        .usage(new DefaultUsage(7, 3)).build());
        Fixture fixture = fixture(model, RunBudget.UNBOUNDED);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> fixture.gateway.execute(request(fixture.runId, 0)).toCompletableFuture().get());

        ModelTaskOutputException rejected = assertInstanceOf(ModelTaskOutputException.class, failure.getCause());
        assertEquals(ModelTaskOutputException.Reason.SCHEMA_MISMATCH, rejected.reason());
        assertTrue(rejected.getMessage().contains("native tool calls"));
        assertEquals(1, fixture.audit.usageCalls.get());
        assertEquals(1, fixture.audit.failedCalls.get());
        assertEquals(0, fixture.audit.completedCalls.get());
        assertEquals(7, fixture.ledger.snapshot(fixture.runId).inputTokens());
        assertEquals(3, fixture.ledger.snapshot(fixture.runId).outputTokens());
    }

    @Test
    void explanationsMultipleValuesAndMalformedFencesAreRejected() {
        ObjectMapper json = new ObjectMapper();
        var schema = request(RunId.random(), 0).outputSchema();
        for (String output : List.of(
                "before\n```json\n{\"ok\":true}\n```",
                "```json\n{\"ok\":true}\n```\nafter",
                "```json\n{\"ok\":true}\n``` after",
                "```json\n{\"ok\":true}\n```\n```json\n{\"ok\":false}\n```",
                "```json\n{\"ok\":true}\n",
                "```javascript\n{\"ok\":true}\n```",
                "```json extra\n{\"ok\":true}\n```",
                "{\"ok\":true}\n{\"ok\":false}",
                "```json\n{\"ok\":true}\n{\"ok\":false}\n```",
                "preamble\n" + "x".repeat(16_385) + "\n{\"ok\":true}",
                "{\"ok\":false,\"ok\":true}",
                "```json\n{\"ok\":false,\"ok\":true}\n```")) {
            assertEquals(ModelTaskOutputException.Reason.INVALID_JSON,
                    assertThrows(ModelTaskOutputException.class, () ->
                            SpringAiModelTaskGateway.validatedOutput(json, schema, output)).reason());
        }
    }

    @Test
    void fencedJsonStillMustMatchTheRequestedSchema() {
        ObjectMapper json = new ObjectMapper();
        var schema = request(RunId.random(), 0).outputSchema();
        for (String output : List.of(
                "```json\n{\"ok\":42}\n```",
                "```\n{\"ok\":true,\"extra\":1}\n```")) {
            assertEquals(ModelTaskOutputException.Reason.SCHEMA_MISMATCH,
                    assertThrows(ModelTaskOutputException.class, () ->
                            SpringAiModelTaskGateway.validatedOutput(json, schema, output)).reason());
        }
    }

    @Test
    void everyPhysicalAttemptPersistsItsExactInputAndRawResponseAndTerminalOwnersAreRejected() throws Exception {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:model-steps-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new com.javaclaw.platform.data.SchemaInitializer(dataSource).initialize();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var runs = new com.javaclaw.framework.store.JdbcRunStore(new org.springframework.jdbc.core.JdbcTemplate(dataSource),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource), mapper, java.time.Clock.systemUTC());
        RunId owner = RunId.random();
        var runRequest = com.javaclaw.framework.api.RunRequest.builder()
                .agent(com.javaclaw.framework.api.AgentDefinitionRef.latest("test"))
                .profile(com.javaclaw.framework.api.RunProfileRef.latest("test"))
                .source(com.javaclaw.framework.api.InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session"))
                .input(com.javaclaw.framework.api.InputBlock.text("original input")).build();
        var empty = JsonNodeFactory.instance.objectNode();
        runs.create(owner, runRequest, "test-plan", new com.javaclaw.framework.spi.RunEventDraft(
                "core.run.created", 1, "test", null, null, empty));
        runs.append(owner, java.util.Set.of(com.javaclaw.framework.api.RunState.CREATED),
                com.javaclaw.framework.api.RunState.RUNNING, new com.javaclaw.framework.spi.RunEventDraft(
                        "core.run.started", 1, "test", null, null, empty), null, null);
        var ledger = new RunUsageLedger();
        ledger.open(owner, RunBudget.UNBOUNDED, runRequest.scope());
        AtomicInteger calls = new AtomicInteger();
        SpringAiModelRegistry models = new SpringAiModelRegistry();
        models.register("test:model", prompt -> calls.getAndIncrement() == 0
                ? response("not-json", 7, 3) : response("<tool_call>\n{\"ok\":true}", 5, 2));
        models.route("workspace", ModelTier.LIGHT, "test:model");
        var gateway = new SpringAiModelTaskGateway(models, ledger,
                new com.javaclaw.framework.core.RunEventModelTaskAuditSink(runs), mapper, new DirectExecutor(), runs);
        assertEquals(true, gateway.execute(request(owner, 1)).toCompletableFuture().get().output().path("ok").asBoolean());
        var steps = new com.javaclaw.framework.core.RunStepQuery(runs).steps(owner);
        assertEquals(2, steps.size());
        assertEquals("not-json", steps.getFirst().output().path("message").path("text").asText());
        assertEquals("<tool_call>\n{\"ok\":true}", steps.getLast().output().path("message").path("text").asText());
        assertEquals(7, steps.getFirst().usage().path("inputTokens").asLong());
        assertEquals("session", steps.getFirst().threadId());
        assertEquals(2, steps.getFirst().input().path("messages").size());
        runs.append(owner, java.util.Set.of(com.javaclaw.framework.api.RunState.RUNNING),
                com.javaclaw.framework.api.RunState.COMPLETED, new com.javaclaw.framework.spi.RunEventDraft(
                        "core.run.completed", 1, "test", null, null, empty), empty, null);
        assertThrows(IllegalStateException.class, () -> gateway.execute(request(owner, 0)));
        assertEquals(2, calls.get());
    }

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
            return response("{\"ok\":true}", 107, 3);
        };
        Fixture fixture = fixture(model, new RunBudget(
                Duration.ofMinutes(1), 100, 100, 1, BigDecimal.TEN));

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                fixture.gateway.execute(request(fixture.runId, 3))
                        .toCompletableFuture().get());

        assertInstanceOf(BudgetExceededException.class, failure.getCause());
        assertEquals(1, calls.get());
        assertEquals(1, fixture.audit.usageCalls.get());
        assertEquals(107, fixture.ledger.snapshot(fixture.runId).inputTokens());
    }

    @Test
    void nextModelTaskIsNotSentWhenConservativePromptExceedsRemainingInputBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            return response("{\"ok\":true}", 992, 1);
        };
        Fixture fixture = fixture(model, new RunBudget(
                Duration.ofMinutes(1), 1_000, 100, 1, BigDecimal.TEN));

        fixture.gateway.execute(request(fixture.runId, 0)).toCompletableFuture().get();
        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                fixture.gateway.execute(request(fixture.runId, 0))
                        .toCompletableFuture().get());

        BudgetExceededException exceeded = assertInstanceOf(BudgetExceededException.class,
                failure.getCause());
        assertEquals(BudgetExceededException.Kind.MODEL_INPUT_TOKENS, exceeded.kind());
        assertTrue(exceeded.getMessage().contains("remaining=8"));
        assertEquals(1, calls.get());
        assertEquals(1, fixture.audit.usageCalls.get());
        assertEquals(992, fixture.ledger.snapshot(fixture.runId).inputTokens());
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
        ChatModel overBudgetFailure = prompt -> {
            calls.incrementAndGet();
            throw new ManagedInferenceChatModel.ManagedInferenceModelException(
                    "inference_error", "failed", true,
                    new com.javaclaw.inference.api.InferenceUsage(104, 1),
                    "deliverance:test", null);
        };
        Fixture budgeted = fixture(overBudgetFailure, new RunBudget(
                Duration.ofMinutes(1), 100, 100, 1, BigDecimal.TEN));
        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                budgeted.gateway.execute(request(budgeted.runId, 3)).toCompletableFuture().get());
        assertInstanceOf(BudgetExceededException.class, failure.getCause());
        assertEquals(1, calls.get());
        assertEquals(104, budgeted.ledger.snapshot(budgeted.runId).inputTokens());
    }

    @Test
    void inlineTimeoutReturnsWhenProviderIgnoresInterruptAndDoesNotRetryLateResponse() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ChatModel provider = prompt -> {
            calls.incrementAndGet();
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { interrupted.countDown(); }
            }
            return response("not-json", 7, 3);
        };
        Fixture fixture = fixture(provider, RunBudget.UNBOUNDED);
        ModelTaskRequest original = request(fixture.runId, 2);
        ModelTaskRequest shortDeadline = new ModelTaskRequest(
                original.purpose(), original.tier(), original.input(), original.mediaInputs(), original.outputSchema(),
                original.ownerRunId(), original.budgetAccount(), Duration.ofMillis(300),
                original.maxRetries(), original.cancellation(), false);

        try {
            IllegalStateException failure = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> assertThrows(IllegalStateException.class,
                            () -> fixture.gateway.executeInline(shortDeadline)));
            assertTrue(failure.getMessage().contains("timed out"));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(1, fixture.audit.failedCalls.get());
            assertEquals(0, fixture.audit.completedCalls.get());
        } finally {
            release.countDown();
        }
        assertTrue(fixture.audit.usageRecorded.await(3, TimeUnit.SECONDS));
        assertEquals(1, calls.get(), "timed-out task must not start another physical attempt");
        assertEquals(7, fixture.ledger.snapshot(fixture.runId).inputTokens());
        assertEquals(1, fixture.audit.failedCalls.get());
        assertEquals(0, fixture.audit.completedCalls.get());
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
                JsonNodeFactory.instance.objectNode().put("value", 1), List.of(), schema,
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

    private static final class RecordingAudit implements ModelTaskAuditSink {
        private final AtomicInteger usageCalls = new AtomicInteger();
        private final AtomicInteger completedCalls = new AtomicInteger();
        private final AtomicInteger failedCalls = new AtomicInteger();
        private final CountDownLatch usageRecorded = new CountDownLatch(1);
        @Override public void started(ModelTaskRequest request) { }
        @Override public void usage(ModelTaskRequest request, String model, int attempt,
                                    long inputTokens, long outputTokens,
                                    BigDecimal estimatedCostCny) {
            usageCalls.incrementAndGet();
            usageRecorded.countDown();
        }
        @Override public void completed(ModelTaskRequest request, ModelTaskResult result) {
            completedCalls.incrementAndGet();
        }
        @Override public void failed(ModelTaskRequest request, Throwable failure) {
            failedCalls.incrementAndGet();
        }
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
