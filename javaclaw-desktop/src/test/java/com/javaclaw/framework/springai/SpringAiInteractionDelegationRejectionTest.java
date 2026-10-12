package com.javaclaw.framework.springai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class SpringAiInteractionDelegationRejectionTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void answerOnlyTaskHidesDelegationWithOrWithoutCatalog(boolean streaming, boolean catalog) throws Exception {
        try (var fixture = new Fixture(streaming, catalog, (owner, prompt) -> {
            assertFalse(hasDelegate(prompt));
            return decision();
        }); var logs = new AggregationLog()) {
            RunHandle run = fixture.start(answerContract());

            assertEquals(RunState.COMPLETED, fixture.await(run).state());
            assertEquals(1, fixture.providers.get());
            assertEquals(0, fixture.delegations.get());
            assertEquals(0, fixture.backendAccess.get());
            assertTrue(fixture.runs.childRuns(run.id()).isEmpty());
            assertFalse(fixture.runs.eventsAfter(run.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.run.failed")));
            assertTrue(logs.events().isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void staleDelegationIsRejectedWithoutFailingTheConversation(boolean streaming, boolean catalog) throws Exception {
        try (var fixture = new Fixture(streaming, catalog, (owner, prompt) -> {
            if (owner.providers.get() == 1) {
                assertTrue(hasDelegate(prompt));
                RunId run = owner.engine.activeTurn(owner.scope).orElseThrow().id();
                // 模拟模型响应期间宿主修订合同，已经提供的回调必须在执行时再次验证。
                assertTrue(owner.runs.append(run, Set.of(RunState.RUNNING), RunState.RUNNING,
                        new RunEventDraft("core.task.contract_revised", 3, "framework.core", null, null,
                                owner.json.valueToTree(answerContract())), null, null).isPresent());
                return response(InteractionExecutionPolicy.DELEGATE_TOOL,
                        "{\"goal\":\"查看应用页面\",\"mode\":\"DESKTOP\"}");
            }
            assertEquals(2, owner.providers.get());
            assertFalse(hasDelegate(prompt));
            JsonNode rejection = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                    .filter(result -> result.name().equals(InteractionExecutionPolicy.DELEGATE_TOOL))
                    .map(result -> readJson(owner.json, result.responseData())).findFirst().orElseThrow();
            assertEquals("FAILED", rejection.path("status").asText());
            assertEquals("NO_INTERACTION_CRITERIA", rejection.path("errorCode").asText());
            assertFalse(rejection.path("data").path("dispatchAttempted").asBoolean(true));
            assertTrue(rejection.path("evidenceRefs").isEmpty());
            return decision();
        }); var logs = new AggregationLog()) {
            RunHandle run = fixture.start(interactionContract());

            assertEquals(RunState.COMPLETED, fixture.await(run).state());
            assertEquals(2, fixture.providers.get());
            assertEquals(1, fixture.delegations.get());
            assertEquals(0, fixture.backendAccess.get());
            assertTrue(fixture.runs.childRuns(run.id()).isEmpty());
            var events = fixture.runs.eventsAfter(run.id(), 0);
            var started = events.stream().filter(event -> event.type().equals("core.tool.started"))
                    .findFirst().orElseThrow().payload();
            assertTrue(started.path("trustedInteractionDelegate").asBoolean());
            assertEquals(1, events.stream().filter(event -> event.type().equals("core.task.contract_revised")).count());
            assertFalse(events.stream().anyMatch(event -> Set.of("core.run.failed", "core.tool.failed",
                    "core.model.retrying", "core.tool.suspended").contains(event.type())));
            var completed = events.stream().filter(event -> event.type().equals("core.tool.completed"))
                    .findFirst().orElseThrow().payload();
            assertEquals("FAILED", completed.path("status").asText());
            var receipt = events.stream().filter(event -> event.type().equals("core.tool.receipt"))
                    .findFirst().orElseThrow().payload();
            assertEquals("FAILED", receipt.path("status").asText());
            assertEquals("NOT_SENT", receipt.path("metadata").path("delivery").asText());
            assertEquals("NONE", receipt.path("metadata").path("effect").asText());
            assertTrue(logs.events().isEmpty(), "前置拒绝不得进入流式聚合器的异常通道");
        }
    }

    private static boolean hasDelegate(Prompt prompt) {
        return ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
                .anyMatch(tool -> tool.getToolDefinition().name().equals(InteractionExecutionPolicy.DELEGATE_TOOL));
    }

    private static JsonNode readJson(ObjectMapper json, String value) {
        try { return json.readTree(value); }
        catch (Exception invalid) { throw new AssertionError(invalid); }
    }

    private static TaskContractV3 answerContract() {
        return new TaskContractV3(3, "解释页面的含义", List.of(), false, true, "definition");
    }

    private static TaskContractV3 interactionContract() {
        return new TaskContractV3(3, "查看应用页面", List.of(new TaskCriterionV3("view", "观察页面",
                "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "com.example.test",
                EffectReceiptV1.Status.OBSERVED, "页面")), true, true, "definition");
    }

    private static ChatResponse decision() {
        return response(HarnessDecisionToolCallback.NAME,
                "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"这是页面说明\","
                        + "\"evidenceRefs\":[],\"unmetCriterionIds\":[]}");
    }

    private static ChatResponse response(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + name, "function", name, arguments))).build())));
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final RunScope scope = new RunScope("workspace", "user", UUID.randomUUID().toString());
        final RunBudget budget = new RunBudget(Duration.ofMinutes(2), 1_000_000, 1_000_000, 10,
                new BigDecimal("1000"));
        final AtomicInteger providers = new AtomicInteger(), delegations = new AtomicInteger(), backendAccess = new AtomicInteger();
        final JdbcRunStore runs;
        final ExtensionManager extensions;
        final AgentEngine engine;

        Fixture(boolean streaming, boolean catalog, BiFunction<Fixture, Prompt, ChatResponse> replies) {
            Clock clock = Clock.systemUTC();
            var source = new DriverManagerDataSource("jdbc:h2:mem:delegation-rejection-"
                    + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(source).initialize();
            var jdbc = new JdbcTemplate(source);
            var transactions = new DataSourceTransactionManager(source);
            var definitions = new JdbcAgentDefinitionStore(jdbc, transactions, json, clock);
            runs = new JdbcRunStore(jdbc, transactions, json, clock);
            var plans = new JdbcExecutionPlanStore(jdbc, json, clock);
            Map<CapabilityId, JsonNode> capabilities = new LinkedHashMap<>();
            capabilities.put(new CapabilityId("interaction.run"), NODES.objectNode().put("enabled", true));
            if (catalog) capabilities.put(new CapabilityId("context.compaction"), NODES.objectNode().put("enabled", true));
            Map<String, String> ranges = new LinkedHashMap<>();
            capabilities.keySet().forEach(id -> ranges.put(id.value(), "=2.0.0"));
            definitions.saveAgentDraft("workspace", new AgentDefinitionDraft("parent", "Parent", "test:model",
                    Map.of("system", "test"), capabilities, NODES.objectNode(), NODES.objectNode(), budget,
                    NODES.objectNode(), ranges), false);
            definitions.publishAgent("workspace", "parent");
            definitions.saveProfileDraft("workspace", new RunProfileDraft("chat", "Chat", PermissionSet.UNRESTRICTED,
                    budget, Map.of(), NODES.objectNode()), false);
            definitions.publishProfile("workspace", "chat");
            ModelTaskGateway tasks = request -> CompletableFuture.failedFuture(new AssertionError("不应重新规划冻结合同"));
            RunUsageLedger ledger = new RunUsageLedger();
            var coordinator = new InteractionDelegateCoordinator(() -> {
                backendAccess.incrementAndGet();
                throw new AssertionError("前置拒绝不得访问交互执行器");
            }, runs, json, ledger, (parent, child) -> fail("不得创建子任务"));
            var interaction = new InteractionTools(new InteractionDelegateGateway() {
                @Override public InteractionResult delegate(ToolContext parent, ToolExecutionContext execution, InteractionTask task) {
                    delegations.incrementAndGet();
                    return coordinator.delegate(parent, execution, task);
                }
                @Override public JsonNode selectMode(ToolContext child, ToolExecutionContext execution, InteractionMode mode) {
                    throw new AssertionError("不得切换交互模式");
                }
            }, json);
            extensions = new ExtensionManager(new ExtensionContext(clock, Runnable::run, tasks));
            List<ExtensionArtifact> artifacts = new ArrayList<>();
            artifacts.add(ExtensionArtifact.builtin(new BuiltinCapabilityExtension("interaction.run", "Interaction", "Test",
                    NODES.objectNode(), NODES.objectNode(), List.of(), registrar -> registrar.toolProvider(interaction))));
            if (catalog) artifacts.add(ExtensionArtifact.builtin(new BuiltinCapabilityExtension("context.compaction",
                    "Compaction", "Test", NODES.objectNode(), NODES.objectNode(), List.of(), null)));
            extensions.publish(artifacts);
            ChatModel model = streaming ? new ReplyStreamingChatModel() {
                @Override public ChatResponse call(Prompt prompt) { throw new AssertionError("应使用流式传输"); }
                @Override public Flux<ChatResponse> stream(Prompt prompt, DecisionReplyStream reply) {
                    providers.incrementAndGet();
                    return Flux.just(replies.apply(Fixture.this, prompt));
                }
                @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            } : new ChatModel() {
                @Override public ChatResponse call(Prompt prompt) {
                    providers.incrementAndGet();
                    return replies.apply(Fixture.this, prompt);
                }
                @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            };
            var models = new SpringAiModelRegistry();
            models.register("test:model", model);
            var executor = new DirectExecutor();
            var tools = new DefaultToolInvocationGateway((tool, arguments, owner) -> ToolApprovalDecision.ALLOW, executor, clock, json);
            var reasoning = new SpringAiReasoningGateway(models, new SpringAiAdvisorRegistry(), tools,
                    ExtensionStateStore.disabled(), ledger, tasks, runs, json, executor, ObservationRegistry.NOOP);
            engine = new AgentEngine(new AgentCompiler(definitions, extensions, json), runs, plans,
                    reasoning, Runnable::run, json, clock, ledger, tasks);
        }

        RunHandle start(TaskContractV3 contract) {
            return engine.start(RunRequest.builder().agent(AgentDefinitionRef.latest("parent"))
                    .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat()).scope(scope)
                    .input(InputBlock.text(contract.originalRequest())).permissionCeiling(PermissionSet.UNRESTRICTED)
                    .budget(budget).attributes(Map.of(TaskContractCompiler.ATTRIBUTE, json.valueToTree(contract))).build());
        }

        RunOutcome await(RunHandle run) throws Exception {
            RunSnapshot snapshot = engine.get(run.id());
            assertFalse(Set.of(RunState.PAUSED, RunState.WAITING_INPUT, RunState.WAITING_APPROVAL)
                    .contains(snapshot.state()), () -> snapshot + "; events=" + runs.eventsAfter(run.id(), 0));
            return run.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        @Override public void close() { engine.close(); extensions.close(); }
    }

    private static final class DirectExecutor implements CancellableTaskExecutor {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public <T> CancellableTask<T> submit(String name, Duration timeout, CancellationToken cancellation, Callable<T> task) {
            CompletableFuture<T> result = new CompletableFuture<>();
            try { cancellation.throwIfCancelled(); result.complete(task.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            return new CancellableTask<>() {
                @Override public java.util.concurrent.CompletionStage<T> completion() { return result; }
                @Override public java.util.concurrent.CompletionStage<Void> termination() { return CompletableFuture.completedFuture(null); }
                @Override public boolean cancel() { return false; }
            };
        }
    }

    private static final class AggregationLog implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(MessageAggregator.class);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        AggregationLog() { appender.start(); logger.addAppender(appender); }
        List<ILoggingEvent> events() { return List.copyOf(appender.list); }
        @Override public void close() { logger.detachAppender(appender); appender.stop(); }
    }
}
