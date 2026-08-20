package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiReasoningGatewayIntegrationTest {

    @Test
    void approvedCallExecutesExactlyOnceBeforeTheNextModelRequest() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicReference<Prompt> continuedPrompt = new AtomicReference<>();
        ChatModel model = prompt -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) return toolCallResponse(7, 3);
            assertEquals(1, toolCalls.get(),
                    "the approved invocation must execute before asking the model again");
            continuedPrompt.set(prompt);
            return textResponse("done", 5, 2);
        };

        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls)) {
            var handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());

            ObjectNode approval = JsonNodeFactory.instance.objectNode();
            approval.put("approved", true);
            approval.put("fingerprint", ToolInvocationFingerprint.create(
                    "test_mutate", JsonNodeFactory.instance.objectNode().put("value", 1)));
            var resumed = fixture.engine.resume(
                    handle.id(), new ResumeCommand("tool.approval", approval));
            RunOutcome outcome = resumed.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state());
            assertEquals(2, modelCalls.get());
            assertEquals(1, toolCalls.get());
            List<org.springframework.ai.chat.messages.Message> instructions =
                    continuedPrompt.get().getInstructions();
            assertInstanceOf(AssistantMessage.class,
                    instructions.get(instructions.size() - 2));
            AssistantMessage assistant = (AssistantMessage) instructions.get(
                    instructions.size() - 2);
            assertEquals("test_mutate", assistant.getToolCalls().getFirst().name());
            assertEquals("{\"value\":1}", assistant.getToolCalls().getFirst().arguments());
            ToolResponseMessage response = assertInstanceOf(
                    ToolResponseMessage.class, instructions.getLast());
            assertEquals(assistant.getToolCalls().getFirst().id(),
                    response.getResponses().getFirst().id());

            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")).count());
            assertEquals(12, outcome.output().path("usage").path("inputTokens").asLong());
            assertEquals(5, outcome.output().path("usage").path("outputTokens").asLong());
            assertEquals(12, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertEquals(5, fixture.ledger.snapshot(handle.id()).outputTokens());
        }
    }

    @Test
    void overBudgetToolCallResponseIsMeteredBeforeAnyToolExecutes() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            modelCalls.incrementAndGet();
            return toolCallResponse(7, 3);
        };
        RunBudget budget = new RunBudget(
                Duration.ofMinutes(1), 5, 100, 4, BigDecimal.TEN);

        try (Fixture fixture = new Fixture(model, budget, toolCalls)) {
            var handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.FAILED, outcome.state());
            assertTrue(outcome.error().contains(BudgetExceededException.class.getName()));
            assertEquals(1, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")).count());
            assertEquals(7, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
        }
    }

    @Test
    void clarificationToolSuspendsWithoutRetryingTheModelOrTool() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            modelCalls.incrementAndGet();
            return namedToolCallResponse(
                    "test_clarify", "{\"question\":\"Which target?\"}", 4, 1);
        };

        try (Fixture fixture = new Fixture(
                model, RunBudget.UNBOUNDED, toolCalls, true)) {
            var handle = fixture.engine.start(fixture.request());
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.WAITING_INPUT, snapshot.state());
            assertEquals("clarify_request", snapshot.output().path("kind").asText());
            assertEquals("Which target?",
                    snapshot.output().path("payload").path("question").asText());
            assertEquals(1, modelCalls.get(), "clarification is a control signal, not a retryable failure");
            assertEquals(1, toolCalls.get(), "one model tool call must yield one clarification request");
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.completed")).count());
            assertEquals(0, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.failed")).count());
        }
    }

    @Test
    void failedManagedInferenceUsageIsRecordedBeforeTheRunFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            throw new ManagedInferenceChatModel.ManagedInferenceModelException(
                    "inference_error", "failed", false,
                    new com.javaclaw.inference.api.InferenceUsage(6, 2),
                    "deliverance:test", null);
        };

        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger())) {
            var handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(1, calls.get());
            assertEquals(6, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertEquals(2, fixture.ledger.snapshot(handle.id()).outputTokens());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")
                            && event.payload().path("failed").asBoolean()).count());
        }
    }

    private static ChatResponse toolCallResponse(int inputTokens, int outputTokens) {
        return namedToolCallResponse(
                "test_mutate", "{\"value\":1}", inputTokens, outputTokens);
    }

    private static ChatResponse namedToolCallResponse(
            String toolName, String arguments, int inputTokens, int outputTokens) {
        AssistantMessage output = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "provider-call", "function", toolName, arguments)))
                .build();
        return response(output, inputTokens, outputTokens);
    }

    private static ChatResponse textResponse(
            String text, int inputTokens, int outputTokens) {
        return response(new AssistantMessage(text), inputTokens, outputTokens);
    }

    private static ChatResponse response(
            AssistantMessage output, int inputTokens, int outputTokens) {
        return new ChatResponse(List.of(new Generation(output)),
                ChatResponseMetadata.builder().model("unknown-test-model")
                        .usage(new DefaultUsage(inputTokens, outputTokens)).build());
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final Clock clock = Clock.systemUTC();
        private final ExtensionManager extensions;
        private final JdbcRunStore runs;
        private final RunUsageLedger ledger = new RunUsageLedger();
        private final AgentEngine engine;

        private Fixture(ChatModel model, RunBudget budget, AtomicInteger toolCalls) {
            this(model, budget, toolCalls, false);
        }

        private Fixture(
                ChatModel model,
                RunBudget budget,
                AtomicInteger toolCalls,
                boolean clarificationTool) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:spring-reasoning-" + UUID.randomUUID()
                            + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(dataSource).initialize();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            DataSourceTransactionManager transactions =
                    new DataSourceTransactionManager(dataSource);
            JdbcAgentDefinitionStore definitions = new JdbcAgentDefinitionStore(
                    jdbc, transactions, json, clock);
            runs = new JdbcRunStore(jdbc, transactions, json, clock);
            JdbcExecutionPlanStore plans = new JdbcExecutionPlanStore(jdbc, json, clock);

            AgentDefinitionDraft agent = new AgentDefinitionDraft(
                    "test.agent", "Test", "test:model", Map.of("system", "test"),
                    Map.of(), JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), RunBudget.UNBOUNDED,
                    JsonNodeFactory.instance.objectNode(), Map.of("test.tool", "=1.0.0"));
            definitions.saveAgentDraft("workspace", agent, false);
            definitions.publishAgent("workspace", agent.id());
            RunProfileDraft profile = new RunProfileDraft(
                    "test.profile", "Test", PermissionSet.UNRESTRICTED,
                    budget, Map.of(), JsonNodeFactory.instance.objectNode());
            definitions.saveProfileDraft("workspace", profile, false);
            definitions.publishProfile("workspace", profile.id());

            DirectExecutor executor = new DirectExecutor();
            extensions = new ExtensionManager(new ExtensionContext(
                    clock, Runnable::run,
                    request -> CompletableFuture.failedFuture(
                            new AssertionError("model task not expected"))));
            extensions.publish(List.of(ExtensionArtifact.builtin(
                    new ToolExtension(toolCalls, clarificationTool))));

            SpringAiModelRegistry models = new SpringAiModelRegistry();
            models.register("test:model", toolCapable(model));
            ToolInvocationGateway toolGateway = new DefaultToolInvocationGateway(
                    (tool, arguments, owner) -> clarificationTool
                            ? ToolApprovalDecision.ALLOW
                            : ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL,
                    executor, clock);
            SpringAiReasoningGateway reasoning = new SpringAiReasoningGateway(
                    models, new SpringAiAdvisorRegistry(), toolGateway,
                    ExtensionStateStore.disabled(), ledger,
                    request -> CompletableFuture.failedFuture(
                            new AssertionError("model task not expected")),
                    runs, json, executor, ObservationRegistry.NOOP);
            engine = new AgentEngine(new AgentCompiler(definitions, extensions, json),
                    runs, plans, reasoning, Runnable::run, json, clock, ledger);
        }

        private static ChatModel toolCapable(ChatModel delegate) {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return delegate.call(prompt);
                }

                @Override
                public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().build();
                }
            };
        }

        private RunRequest request() {
            return RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile"))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session"))
                    .input(InputBlock.text("mutate once"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED)
                    .budget(RunBudget.UNBOUNDED)
                    .build();
        }

        @Override
        public void close() {
            engine.close();
            extensions.close();
        }
    }

    private static final class ToolExtension implements AgentFrameworkExtension {
        private final AtomicInteger calls;
        private final boolean clarificationTool;
        private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
                "test.tool", SemanticVersion.parse("1.0.0"), ">=2.0.0 <3.0.0",
                ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());

        private ToolExtension(AtomicInteger calls, boolean clarificationTool) {
            this.calls = calls;
            this.clarificationTool = clarificationTool;
        }

        @Override public ExtensionDescriptor descriptor() { return descriptor; }

        @Override
        public void register(ExtensionRegistrar registrar) {
            if (clarificationTool) {
                registrar.retryPolicy(new RetryPolicy() {
                    @Override public String id() { return "retry-every-failure"; }
                    @Override public int order() { return 0; }
                    @Override public java.util.Optional<RetryDirective> evaluate(RetryContext context) {
                        return java.util.Optional.of(RetryDirective.retryAfter(Duration.ZERO));
                    }
                });
            }
            registrar.tool(context -> new FrameworkTool() {
                @Override
                public ToolDescriptor descriptor() {
                    ObjectNode schema = JsonNodeFactory.instance.objectNode();
                    schema.put("type", "object");
                    String property = clarificationTool ? "question" : "value";
                    schema.putObject("properties").putObject(property).put("type",
                            clarificationTool ? "string" : "integer");
                    schema.putArray("required").add(property);
                    schema.put("additionalProperties", false);
                    return new ToolDescriptor(
                            clarificationTool ? "test_clarify" : "test_mutate",
                            clarificationTool ? "request clarification" : "mutate once",
                            schema, "test",
                            PermissionSet.of(clarificationTool
                                    ? "interaction.request" : "tool.execute"),
                            false);
                }

                @Override
                public com.fasterxml.jackson.databind.JsonNode execute(
                        com.fasterxml.jackson.databind.JsonNode arguments,
                        ToolExecutionContext context) {
                    calls.incrementAndGet();
                    if (clarificationTool) {
                        ObjectNode waiting = JsonNodeFactory.instance.objectNode();
                        waiting.put("kind", "clarify_request");
                        waiting.putObject("payload").put(
                                "question", arguments.path("question").asText());
                        throw new ToolInputRequiredException(waiting, "clarification required");
                    }
                    return JsonNodeFactory.instance.objectNode()
                            .put("observed", arguments.path("value").asInt());
                }
            });
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
