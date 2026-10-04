package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinition;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.ManagedTurn;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfile;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.ToolCallOutcome;
import com.javaclaw.framework.api.ToolCallRequest;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.AgentDefinitionResolver;
import com.javaclaw.framework.spi.AgentFrameworkExtension;
import com.javaclaw.framework.spi.CancellableTask;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.ExtensionDescriptor;
import com.javaclaw.framework.spi.ExtensionRegistrar;
import com.javaclaw.framework.spi.ExtensionScope;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.HotUpdateCompatibility;
import com.javaclaw.framework.spi.RunCancelledException;
import com.javaclaw.framework.spi.SemanticVersion;
import com.javaclaw.framework.spi.ToolApprovalDecision;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultToolClientBudgetTest {
    @Test
    void 直接工具调用同时消耗引擎控制预算并约束后续模型调用() {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<RunControl> ownerControl = new AtomicReference<>();
            CompletableFuture<ReasoningResult> reasoning = new CompletableFuture<>();
            try (AgentEngine engine = fixture.engine(request -> {
                ownerControl.set(request.control());
                return reasoning;
            })) {
                var turn = engine.start(fixture.owner("shared-control", budget(30, 1)));
                fixture.invoke(fixture.client(engine), turn.id(), "direct", 1, budget(10, 1));
                assertEquals(1, ownerControl.get().toolCallCount());
                assertThrows(BudgetExceededException.class,
                        () -> ownerControl.get().recordToolCall("model-next"));
                reasoning.complete(new ReasoningResult(RunState.COMPLETED,
                        JsonNodeFactory.instance.objectNode(), ""));
                assertEquals(fixture.created.get(), fixture.closed.get());
            } finally {
                reasoning.complete(new ReasoningResult(RunState.PAUSED, null, "测试退出"));
            }
        }
    }

    @Test
    void 同一Turn的多个单调用节点共享总预算且回放不重复扣减() {
        try (Fixture fixture = new Fixture();
             AgentEngine engine = fixture.engine();
             ManagedTurn turn = fixture.begin(engine, "continuous", budget(30, 2))) {
            DefaultToolClient client = fixture.client(engine);
            ToolCallRequest first = fixture.call(turn.id(), "node-1", 1, budget(10, 1));
            ToolCallOutcome original = client.invoke(first).toCompletableFuture().join();
            ToolCallOutcome replayed = fixture.client(engine).invoke(first).toCompletableFuture().join();
            assertEquals(original.output(), replayed.output());
            fixture.invoke(client, turn.id(), "node-2", 2, budget(10, 1));

            BudgetExceededException failure = assertInstanceOf(BudgetExceededException.class,
                    failure(() -> fixture.invoke(client, turn.id(), "node-3", 3, budget(10, 1))));
            assertEquals(BudgetExceededException.Kind.TOOL_CALLS, failure.kind());
            assertEquals("3", failure.actual());
            assertEquals("2", failure.limit());
            assertEquals(2, fixture.executions.get());
            assertEquals(2, fixture.started(turn.id()));
            assertEquals(fixture.created.get(), fixture.closed.get());
        }
    }

    @Test
    void 节点局部调用预算仍然有效() {
        try (Fixture fixture = new Fixture();
             AgentEngine engine = fixture.engine();
             ManagedTurn turn = fixture.begin(engine, "local", budget(30, 5))) {
            DefaultToolClient client = fixture.client(engine);
            BudgetExceededException failure = assertInstanceOf(BudgetExceededException.class,
                    failure(() -> fixture.invoke(client, turn.id(), "denied", 1, budget(10, 0))));
            assertEquals("0", failure.limit());
            assertEquals(0, fixture.started(turn.id()));
            fixture.invoke(client, turn.id(), "allowed", 2, budget(10, 1));
            assertEquals(1, fixture.executions.get());
            assertEquals(fixture.created.get(), fixture.closed.get());
        }
    }

    @Test
    void 节点截止时间同时受局部超时和Turn原始截止时间约束() {
        try (Fixture fixture = new Fixture();
             AgentEngine engine = fixture.engine();
             ManagedTurn turn = fixture.begin(engine, "deadline", budget(30, 5))) {
            Instant opened = fixture.clock.instant();
            DefaultToolClient client = fixture.client(engine);
            fixture.invoke(client, turn.id(), "short", 1, budget(5, 1));
            fixture.clock.advance(Duration.ofSeconds(2));
            fixture.invoke(client, turn.id(), "long", 2, budget(60, 1));
            assertEquals(List.of(opened.plusSeconds(5), opened.plusSeconds(30)), fixture.deadlines);

            fixture.clock.advance(Duration.ofSeconds(28));
            assertInstanceOf(RunCancelledException.class,
                    failure(() -> fixture.invoke(client, turn.id(), "expired", 3, budget(60, 1))));
            assertEquals(2, fixture.executions.get());
            assertEquals(2, fixture.started(turn.id()));
            assertEquals(fixture.created.get(), fixture.closed.get());
        }
    }

    @Test
    void 引擎恢复后继续沿用累计调用数和原始截止时间() {
        try (Fixture fixture = new Fixture()) {
            RunRequest request = fixture.owner("recovery", budget(30, 2));
            RunId id;
            Instant deadline;
            try (AgentEngine engine = fixture.engine(); ManagedTurn turn = engine.beginTurn(request)) {
                turn.ready().toCompletableFuture().join();
                id = turn.id();
                deadline = engine.deadline(id).orElseThrow();
                fixture.invoke(fixture.client(engine), id, "before", 1, budget(60, 1));
            }
            fixture.clock.advance(Duration.ofSeconds(10));

            try (AgentEngine engine = fixture.engine(); ManagedTurn turn = engine.beginTurn(request)) {
                turn.ready().toCompletableFuture().join();
                assertEquals(id, turn.id());
                assertEquals(deadline, engine.deadline(id).orElseThrow());
                DefaultToolClient client = fixture.client(engine);
                fixture.invoke(client, id, "after", 2, budget(60, 1));
                assertInstanceOf(BudgetExceededException.class,
                        failure(() -> fixture.invoke(client, id, "over-budget", 3, budget(60, 1))));
                fixture.clock.advance(Duration.ofSeconds(20));
                assertInstanceOf(RunCancelledException.class,
                        failure(() -> fixture.invoke(client, id, "expired", 4, budget(60, 1))));
                assertEquals(List.of(deadline, deadline), fixture.deadlines);
                assertEquals(2, fixture.executions.get());
                assertEquals(2, fixture.started(id));
                assertEquals(fixture.created.get(), fixture.closed.get());
            }
        }
    }

    @Test
    void 并发节点不能同时消费所属Turn的最后一次调用() throws Exception {
        try (Fixture fixture = new Fixture();
             AgentEngine engine = fixture.engine();
             ManagedTurn turn = fixture.begin(engine, "parallel", budget(30, 1));
             var callers = Executors.newFixedThreadPool(2)) {
            DefaultToolClient client = fixture.client(engine);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Throwable> first = () -> concurrentInvoke(start, fixture, client, turn.id(), 1);
            Callable<Throwable> second = () -> concurrentInvoke(start, fixture, client, turn.id(), 2);
            var one = callers.submit(first);
            var two = callers.submit(second);
            start.countDown();
            Throwable a = one.get(5, TimeUnit.SECONDS);
            Throwable b = two.get(5, TimeUnit.SECONDS);
            assertTrue((a == null) != (b == null));
            assertInstanceOf(BudgetExceededException.class, a == null ? b : a);
            assertEquals(1, fixture.executions.get());
            assertEquals(1, fixture.started(turn.id()));
            assertEquals(fixture.created.get(), fixture.closed.get());
        }
    }

    @Test
    void 下一Turn不继承上一个Turn的调用计数或局部控制状态() {
        try (Fixture fixture = new Fixture(); AgentEngine engine = fixture.engine()) {
            DefaultToolClient client = fixture.client(engine);
            RunId previous;
            try (ManagedTurn turn = fixture.begin(engine, "first", budget(30, 1))) {
                previous = turn.id();
                fixture.invoke(client, previous, "node-1", 1, budget(10, 1));
                turn.complete(JsonNodeFactory.instance.objectNode());
            }
            try (ManagedTurn turn = fixture.begin(engine, "next", budget(30, 1))) {
                fixture.invoke(client, turn.id(), "node-1", 1, budget(10, 1));
                assertEquals(1, fixture.started(previous));
                assertEquals(1, fixture.started(turn.id()));
                assertEquals(2, fixture.executions.get());
            }
            assertEquals(fixture.created.get(), fixture.closed.get());
        }
    }

    private static Throwable concurrentInvoke(CountDownLatch start, Fixture fixture,
                                              DefaultToolClient client, RunId id, int value) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            fixture.invoke(client, id, "node-" + value, value, budget(10, 1));
            return null;
        } catch (CompletionException failure) {
            return failure.getCause();
        }
    }

    private static Throwable failure(Runnable action) {
        return assertThrows(CompletionException.class, action::run).getCause();
    }

    private static RunBudget budget(int seconds, int calls) {
        return new RunBudget(Duration.ofSeconds(seconds), 1000, 1000, calls, BigDecimal.TEN);
    }

    private static final class Fixture implements AutoCloseable {
        private final MutableClock clock = new MutableClock();
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final List<Instant> deadlines = new CopyOnWriteArrayList<>();
        private final RunScope scope = new RunScope("workspace", "user", "workflow-thread");
        private final ExtensionManager extensions;
        private final AgentCompiler compiler;
        private final JdbcRunStore runs;
        private final JdbcExecutionPlanStore plans;

        private Fixture() {
            var source = new DriverManagerDataSource(
                    "jdbc:h2:mem:tool-budget-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(source).initialize();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            runs = new JdbcRunStore(jdbc, new DataSourceTransactionManager(source), json, clock);
            plans = new JdbcExecutionPlanStore(jdbc, json, clock);
            extensions = new ExtensionManager(new ExtensionContext(clock, Runnable::run,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("不应调用模型任务"))));
            extensions.publish(List.of(ExtensionArtifact.builtin(new BudgetToolExtension(this))));
            compiler = new AgentCompiler(new AgentDefinitionResolver() {
                @Override public AgentDefinition resolveAgent(String workspace, AgentDefinitionRef ref) {
                    return new AgentDefinition("system.default", 1, "预算测试", "test:model", Map.of(), Map.of(),
                            JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                            RunBudget.UNBOUNDED, JsonNodeFactory.instance.objectNode(),
                            Map.of("test.budget-tool", "=1.0.0"), "agent-checksum");
                }

                @Override public RunProfile resolveProfile(String workspace, RunProfileRef ref) {
                    return new RunProfile("chat", 1, "预算测试", PermissionSet.UNRESTRICTED,
                            RunBudget.UNBOUNDED, Map.of(), JsonNodeFactory.instance.objectNode(), "profile-checksum");
                }
            }, extensions, json);
        }

        private AgentEngine engine() {
            return engine(ignored -> CompletableFuture.failedFuture(new AssertionError("托管 Turn 不应调用模型")));
        }

        private AgentEngine engine(ReasoningGateway reasoning) {
            return new AgentEngine(compiler, runs, plans, reasoning,
                    Runnable::run, json, clock, new RunUsageLedger());
        }

        private RunRequest owner(String key, RunBudget budget) {
            return RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                    .profile(RunProfileRef.latest("chat")).source(InvocationSource.workflow("graph"))
                    .scope(scope).input(InputBlock.text("执行工作流工具节点"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED).budget(budget).idempotencyKey(key).build();
        }

        private ManagedTurn begin(AgentEngine engine, String key, RunBudget budget) {
            ManagedTurn turn = engine.beginTurn(owner(key, budget));
            turn.ready().toCompletableFuture().join();
            return turn;
        }

        private DefaultToolClient client(AgentEngine engine) {
            return new DefaultToolClient(new DefaultToolInvocationGateway(
                    (tool, arguments, request) -> ToolApprovalDecision.ALLOW, DIRECT_EXECUTOR, clock),
                    compiler, clock, (challenge, request) -> CompletableFuture.failedFuture(
                            new AssertionError("不应请求审批")), runs, engine);
        }

        private ToolCallRequest call(RunId id, String invocation, int value, RunBudget budget) {
            return new ToolCallRequest(scope, InvocationSource.workflow("graph"), "budget_tool",
                    JsonNodeFactory.instance.objectNode().put("value", value), PermissionSet.UNRESTRICTED,
                    budget, "graph", () -> false, Set.of("test"), id, invocation);
        }

        private ToolCallOutcome invoke(DefaultToolClient client, RunId id, String invocation,
                                       int value, RunBudget budget) {
            return client.invoke(call(id, invocation, value, budget)).toCompletableFuture().join();
        }

        private long started(RunId id) {
            return runs.eventsAfter(id, 0).stream().filter(event -> event.type().equals("core.tool.started")
                    && event.producer().equals("framework.core")).count();
        }

        @Override public void close() { extensions.close(); }
    }

    private static final class BudgetToolExtension implements AgentFrameworkExtension {
        private final Fixture fixture;

        private BudgetToolExtension(Fixture fixture) { this.fixture = fixture; }

        @Override public ExtensionDescriptor descriptor() {
            return new ExtensionDescriptor("test.budget-tool", SemanticVersion.parse("1.0.0"),
                    ">=2.0.0 <3.0.0", ">=2.0.0 <3.0.0", List.of(), Set.of(),
                    ExtensionScope.PLAN_SCOPED, HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());
        }

        @Override public void register(ExtensionRegistrar registrar) {
            registrar.tool(context -> {
                fixture.created.incrementAndGet();
                return new FrameworkTool() {
                    @Override public ToolDescriptor descriptor() {
                        return new ToolDescriptor("budget_tool", "预算测试工具",
                                JsonNodeFactory.instance.objectNode().put("type", "object"),
                                "test", PermissionSet.of("tool.read"), true);
                    }

                    @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
                        fixture.executions.incrementAndGet();
                        fixture.deadlines.add(context.deadline());
                        return arguments.deepCopy();
                    }

                    @Override public void close() { fixture.closed.incrementAndGet(); }
                };
            });
        }
    }

    private static final CancellableTaskExecutor DIRECT_EXECUTOR = new CancellableTaskExecutor() {
        @Override public void execute(Runnable command) { command.run(); }

        @Override public <T> CancellableTask<T> submit(String name, Duration timeout,
                                                      CancellationToken cancellation, Callable<T> task) {
            CompletableFuture<T> result = new CompletableFuture<>();
            try {
                cancellation.throwIfCancelled();
                result.complete(task.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
            return new CancellableTask<>() {
                @Override public CompletionStage<T> completion() { return result; }
                @Override public CompletionStage<Void> termination() {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public boolean cancel() { return false; }
            };
        }
    };

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-01T00:00:00Z"));
        private void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
