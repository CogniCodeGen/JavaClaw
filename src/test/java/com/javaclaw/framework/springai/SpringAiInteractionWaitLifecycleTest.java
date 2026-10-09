package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.builtin.BuiltinCapabilityExtension;
import com.javaclaw.framework.builtin.InteractionTools;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SpringAiInteractionWaitLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parentWaitsAndResumesTheReservedDelegationExactlyOnce(boolean streaming) throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.systemUTC();
        var source = new DriverManagerDataSource("jdbc:h2:mem:interaction-stream-wait-"
                + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(source).initialize();
        var jdbc = new JdbcTemplate(source);
        var transactions = new DataSourceTransactionManager(source);
        var definitions = new JdbcAgentDefinitionStore(jdbc, transactions, json, clock);
        var runs = new JdbcRunStore(jdbc, transactions, json, clock);
        var plans = new JdbcExecutionPlanStore(jdbc, json, clock);
        RunBudget budget = new RunBudget(Duration.ofMinutes(2), 1_000_000, 1_000_000, 10, new BigDecimal("1000"));
        Map<CapabilityId, JsonNode> capabilities = Map.of(new CapabilityId("interaction.run"),
                JsonNodeFactory.instance.objectNode().put("enabled", true));
        for (String agent : List.of("wait-parent", InteractionExecutionPolicy.AGENT_ID)) {
            definitions.saveAgentDraft("workspace", new AgentDefinitionDraft(agent, agent, "test:model",
                    Map.of("system", "test"), capabilities, JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), budget, JsonNodeFactory.instance.objectNode(),
                    Map.of("interaction.run", "=2.0.0")), false);
            definitions.publishAgent("workspace", agent);
        }
        for (String profile : List.of("chat", InteractionExecutionPolicy.PROFILE_ID)) {
            definitions.saveProfileDraft("workspace", new RunProfileDraft(profile, profile,
                    PermissionSet.UNRESTRICTED, budget, Map.of(), JsonNodeFactory.instance.objectNode()), false);
            definitions.publishProfile("workspace", profile);
        }
        ModelTaskGateway tasks = request -> CompletableFuture.failedFuture(new AssertionError("unexpected planning"));
        RunUsageLedger ledger = new RunUsageLedger();
        AtomicReference<AgentEngine> engineRef = new AtomicReference<>();
        AtomicInteger delegations = new AtomicInteger(), providers = new AtomicInteger(), streams = new AtomicInteger();
        var coordinator = new InteractionDelegateCoordinator(engineRef::get, runs, json, ledger, (parent, child) -> { });
        var interaction = new InteractionTools(new InteractionDelegateGateway() {
            @Override public InteractionResult delegate(ToolContext parent, ToolExecutionContext execution,
                    InteractionTask task) {
                delegations.incrementAndGet();
                return coordinator.delegate(parent, execution, task);
            }
            @Override public JsonNode selectMode(ToolContext child, ToolExecutionContext execution, InteractionMode mode) {
                return coordinator.selectMode(child, execution, mode);
            }
        }, json);
        ChatModel responses = prompt -> switch (providers.incrementAndGet()) {
            case 1 -> response("interaction_delegate", "{\"mode\":\"DESKTOP\",\"goal\":\"查看应用页面\","
                    + "\"necessaryData\":{},\"constraints\":[]}");
            case 2 -> {
                assertTrue(prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                        .anyMatch(value -> value.name().equals("interaction_delegate")),
                        "the settled reserved tool must be restored to the provider conversation");
                yield response(HarnessDecisionToolCallback.NAME, "{\"decision\":\"BLOCKED\","
                        + "\"userMessage\":\"交互子任务未完成验证\",\"evidenceRefs\":[],\"unmetCriterionIds\":[\"view\"]}");
            }
            default -> throw new AssertionError("unexpected parent provider call");
        };
        ChatModel model = streaming ? new ReplyStreamingChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError("sync transport used"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt, DecisionReplyStream reply) {
                streams.incrementAndGet();
                return Flux.just(responses.call(prompt));
            }
            @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        } : new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return responses.call(prompt); }
            @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        };
        var models = new SpringAiModelRegistry();
        models.register("test:model", model);
        var executor = new DirectExecutor();
        var tools = new DefaultToolInvocationGateway((tool, arguments, owner) -> ToolApprovalDecision.ALLOW, executor, clock);
        CompletableFuture<ReasoningResult> childResult = new CompletableFuture<>();
        try (var extensions = new ExtensionManager(new ExtensionContext(clock, Runnable::run, tasks))) {
            extensions.publish(List.of(ExtensionArtifact.builtin(new BuiltinCapabilityExtension("interaction.run",
                    "Interaction", "Interaction wait test", JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), List.of(), registrar -> registrar.toolProvider(interaction)))));
            var gateway = new SpringAiReasoningGateway(models, new SpringAiAdvisorRegistry(), tools,
                    ExtensionStateStore.disabled(), ledger, tasks, runs, json, executor, ObservationRegistry.NOOP);
            ReasoningGateway reasoning = request -> InteractionExecutionPolicy.isInteraction(request.runRequest())
                    ? childResult : gateway.execute(request);
            try (var engine = new AgentEngine(new AgentCompiler(definitions, extensions, json), runs, plans,
                    reasoning, Runnable::run, json, clock, ledger, tasks)) {
                engineRef.set(engine);
                var contract = new TaskContractV3(3, "查看应用页面", List.of(new TaskCriterionV3("view", "观察页面",
                        "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "com.example.test",
                        EffectReceiptV1.Status.OBSERVED, "页面")), true, true, "definition");
                var request = RunRequest.builder().agent(AgentDefinitionRef.latest("wait-parent"))
                        .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                        .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("查看应用页面"))
                        .permissionCeiling(PermissionSet.UNRESTRICTED).budget(budget)
                        .attributes(Map.of(TaskContractCompiler.ATTRIBUTE, json.valueToTree(contract))).build();
                RunHandle handle = engine.start(request);
                assertEquals(RunState.WAITING_CHILD, engine.get(handle.id()).state());
                assertEquals(1, providers.get());
                assertEquals(1, delegations.get());
                assertEquals(1, runs.childRuns(handle.id()).size());
                assertFalse(handle.completion().toCompletableFuture().isDone());
                engine.resume(handle.id(), new ResumeCommand("input.continue", JsonNodeFactory.instance.objectNode()));
                assertEquals(RunState.WAITING_CHILD, engine.get(handle.id()).state());
                assertEquals(1, providers.get(), "reattaching a live wait does not call the model");
                assertEquals(1, delegations.get(), "reattaching cannot invoke the reserved delegation again");
                childResult.complete(ReasoningResult.completed(JsonNodeFactory.instance.objectNode().put("text", "未提供观察证据")));
                assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
                assertEquals(2, providers.get());
                assertEquals(streaming ? 2 : 0, streams.get());
                assertEquals(1, delegations.get(), "journal recovery consumes the host result without another delegation");
                var events = runs.eventsAfter(handle.id(), 0);
                assertEquals(1, events.stream().filter(event -> event.type().equals("core.tool.suspended")).count());
                assertEquals(1, events.stream().filter(event -> event.type().equals("core.interaction.child_completed")).count());
                assertFalse(events.stream().anyMatch(event -> Set.of("core.run.failed", "core.tool.failed",
                        "core.model.retrying").contains(event.type())));
            }
        }
    }

    private static ChatResponse response(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + name, "function", name, arguments))).build())));
    }

    private static final class DirectExecutor implements CancellableTaskExecutor {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public <T> CancellableTask<T> submit(String name, Duration timeout,
                CancellationToken cancellation, Callable<T> task) {
            CompletableFuture<T> result = new CompletableFuture<>();
            try { cancellation.throwIfCancelled(); result.complete(task.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            return new CancellableTask<>() {
                @Override public java.util.concurrent.CompletionStage<T> completion() { return result; }
                @Override public java.util.concurrent.CompletionStage<Void> termination() {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public boolean cancel() { return false; }
            };
        }
    }
}
