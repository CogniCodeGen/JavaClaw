package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionDraft;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileDraft;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskContractInputIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String GOAL = "生成一个 JavaFX 贪吃蛇项目";
    private static final String DIRECTORY = "target/task-contract-input-fixture";

    @Test
    void 缺少保存目录直接提问且两种补充命令均恢复原任务() throws Exception {
        for (String command : List.of("input", "user.input")) {
            Fixture fixture = new Fixture();
            List<String> calls = new ArrayList<>();
            AtomicInteger reasoningCalls = new AtomicInteger();
            ModelTaskGateway planner = task -> {
                calls.add(task.purpose());
                assertEquals("task.contract.plan.v3", task.purpose());
                if (calls.size() == 1) return answer(needsDirectory());
                assertEquals(DIRECTORY, task.input().path("currentUserInput").asText());
                assertTrue(task.input().path("humanHistory").toString().contains(GOAL));
                return answer(resolvedProject());
            };
            try (ExtensionManager extensions = fixture.extensions();
                 AgentEngine engine = fixture.engine(extensions, planner, reasoningCalls)) {
                var handle = engine.start(fixture.request());
                var waiting = engine.get(handle.id());
                assertEquals(RunState.WAITING_INPUT, waiting.state(), waiting.error());
                assertEquals("task.contract.needs_input", waiting.output().path("kind").asText());
                assertTrue(waiting.output().path("text").asText().contains("项目保存目录"));
                assertEquals(1, calls.size());
                assertEquals(0, reasoningCalls.get());
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertTrue(engine.taskResult(handle.id()).isEmpty());
                assertTrue(fixture.events(handle.id()).stream().noneMatch(type ->
                        type.equals("core.task.stop") || type.equals("core.task.outcome")
                                || type.equals("core.tool.started")));

                var resumed = engine.resume(handle.id(), input(command, DIRECTORY));

                assertEquals(handle.id(), resumed.id());
                assertEquals(2, calls.size());
                assertEquals(1, reasoningCalls.get());
                assertEquals(RunState.WAITING_INPUT, engine.get(handle.id()).state());
                assertEquals("fixture.reasoning", engine.get(handle.id()).output().path("kind").asText());
                assertEquals(1, fixture.events(handle.id()).stream()
                        .filter(type -> type.equals("core.task.contract_revised")).count());
                assertEquals(TaskContractV3.IntentStatus.RESOLVED, fixture.contract(handle.id()).intentStatus());
            }
        }
    }

    @Test
    void 连续补充只规划一次且保留较早聊天答案() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger planningCalls = new AtomicInteger();
        AtomicInteger reasoningCalls = new AtomicInteger();
        ModelTaskGateway planner = task -> {
            int call = planningCalls.incrementAndGet();
            assertEquals("task.contract.plan.v3", task.purpose());
            if (call == 1) return answer(needsDirectory());
            if (call == 2) {
                var output = needsDirectory();
                output.putArray("reasonCodes").add("MISSING_BUILD_TOOL");
                output.putArray("unresolvedInputs").add("请选择 Maven 或 Gradle");
                return answer(output);
            }
            assertEquals("Maven", task.input().path("currentUserInput").asText());
            assertTrue(task.input().path("humanHistory").toString().contains(DIRECTORY));
            return answer(resolvedProject());
        };
        try (ExtensionManager extensions = fixture.extensions();
             AgentEngine engine = fixture.engine(extensions, planner, reasoningCalls)) {
            var handle = engine.start(fixture.request());
            engine.resume(handle.id(), input("input", DIRECTORY));
            assertTrue(engine.get(handle.id()).output().path("text").asText().contains("Maven"));
            assertEquals(0, reasoningCalls.get());
            engine.resume(handle.id(), input("user.input", "Maven"));
            assertEquals(3, planningCalls.get());
            assertEquals(1, reasoningCalls.get());
            assertEquals(2, fixture.events(handle.id()).stream()
                    .filter(type -> type.equals("core.task.contract_revised")).count());
        }
    }

    @Test
    void 等待输入可在重启后恢复且保留结构化状态() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger planningCalls = new AtomicInteger();
        AtomicInteger reasoningCalls = new AtomicInteger();
        ModelTaskGateway planner = task -> answer(planningCalls.incrementAndGet() == 1
                ? needsDirectory() : resolvedProject());
        try (ExtensionManager extensions = fixture.extensions()) {
            RunId id;
            try (AgentEngine first = fixture.engine(extensions, planner, reasoningCalls)) {
                id = first.start(fixture.request()).id();
                assertEquals(RunState.WAITING_INPUT, first.get(id).state());
                assertEquals(TaskContractV3.IntentStatus.NEEDS_HUMAN, fixture.contract(id).intentStatus());
            }
            try (AgentEngine restored = fixture.engine(extensions, planner, reasoningCalls)) {
                assertEquals(1, planningCalls.get());
                assertEquals(RunState.WAITING_INPUT, restored.get(id).state());
                restored.resume(id, input("input", DIRECTORY));
                assertEquals(2, planningCalls.get());
                assertEquals(1, reasoningCalls.get());
            }
        }
    }

    @Test
    void 修复调用返回明确缺失输入也直接提问() throws Exception {
        Fixture fixture = new Fixture();
        List<String> calls = new ArrayList<>();
        AtomicInteger reasoningCalls = new AtomicInteger();
        ModelTaskGateway planner = task -> {
            calls.add(task.purpose());
            return answer(calls.size() == 1 ? JSON.createObjectNode() : needsDirectory());
        };
        try (ExtensionManager extensions = fixture.extensions();
             AgentEngine engine = fixture.engine(extensions, planner, reasoningCalls)) {
            var handle = engine.start(fixture.request());
            assertEquals(List.of("task.contract.plan.v3", "task.contract.repair.v3"), calls);
            assertEquals(RunState.WAITING_INPUT, engine.get(handle.id()).state());
            assertEquals("model-repair", fixture.contract(handle.id()).source());
            assertFalse(fixture.contract(handle.id()).reasonCodes().contains("PLAN_REPAIR_EXHAUSTED"));
            assertEquals(0, reasoningCalls.get());
        }
    }

    private static ObjectNode needsDirectory() {
        var output = JSON.createObjectNode().put("originalRequest", GOAL)
                .put("applicable", true).put("intentStatus", "NEEDS_HUMAN");
        output.putArray("reasonCodes").add("MISSING_TARGET_LOCATION");
        output.putArray("unresolvedInputs").add("请提供项目保存目录");
        output.putArray("criteria");
        return output;
    }

    private static ObjectNode resolvedProject() {
        var output = JSON.createObjectNode().put("originalRequest", GOAL + "，保存到 " + DIRECTORY)
                .put("applicable", true).put("intentStatus", "RESOLVED");
        output.putArray("criteria").addObject().put("id", "project").put("description", "创建项目源码")
                .put("capabilityId", "file.write").put("targetType", "FILE")
                .put("target", DIRECTORY + "/src/main/java/Snake.java").put("requiredEvidence", "VERIFIED")
                .put("requiredSubject", "");
        return output;
    }

    private static CompletableFuture<ModelTaskResult> answer(ObjectNode output) {
        return CompletableFuture.completedFuture(new ModelTaskResult(output, "fixture", 0, 0, false, Map.of()));
    }

    private static ResumeCommand input(String type, String text) {
        return new ResumeCommand(type, JSON.createObjectNode().put("text", text));
    }

    private static final class Fixture {
        private final Clock clock = Clock.systemUTC();
        private final JdbcAgentDefinitionStore definitions;
        private final JdbcRunStore runs;
        private final JdbcExecutionPlanStore plans;

        private Fixture() {
            var data = new DriverManagerDataSource("jdbc:h2:mem:input-" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(data).initialize();
            var jdbc = new JdbcTemplate(data);
            var transactions = new DataSourceTransactionManager(data);
            definitions = new JdbcAgentDefinitionStore(jdbc, transactions, JSON, clock);
            runs = new JdbcRunStore(jdbc, transactions, JSON, clock);
            plans = new JdbcExecutionPlanStore(jdbc, JSON, clock);
            definitions.saveAgentDraft("workspace", new AgentDefinitionDraft("input.agent", "Input Agent",
                    "fixture:model", Map.of("system", "test"), Map.of(), JSON.createObjectNode(),
                    JSON.createObjectNode(), RunBudget.UNBOUNDED, JSON.createObjectNode(), Map.of()), false);
            definitions.publishAgent("workspace", "input.agent");
            definitions.saveProfileDraft("workspace", new RunProfileDraft("input.profile", "Input Profile",
                    PermissionSet.UNRESTRICTED, RunBudget.UNBOUNDED, Map.of(), JSON.createObjectNode()), false);
            definitions.publishProfile("workspace", "input.profile");
        }

        private RunRequest request() {
            return RunRequest.builder().agent(AgentDefinitionRef.latest("input.agent"))
                    .profile(RunProfileRef.latest("input.profile")).source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", UUID.randomUUID().toString()))
                    .input(InputBlock.text(GOAL)).build();
        }

        private ExtensionManager extensions() {
            return new ExtensionManager(new ExtensionContext(clock, Runnable::run,
                    task -> CompletableFuture.failedFuture(new AssertionError("unexpected extension model call"))));
        }

        private AgentEngine engine(ExtensionManager extensions, ModelTaskGateway planner,
                AtomicInteger calls) {
            return new AgentEngine(new AgentCompiler(definitions, extensions, JSON), runs, plans,
                    task -> {
                        calls.incrementAndGet();
                        assertTrue(task.runRequest().attributes().get(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE)
                                .asText().contains(DIRECTORY));
                        return CompletableFuture.completedFuture(ReasoningResult.waitingForInput(
                                JSON.createObjectNode().put("kind", "fixture.reasoning"), "fixture"));
                    }, Runnable::run, JSON, clock, new RunUsageLedger(), planner);
        }

        private List<String> events(RunId id) {
            return runs.eventsAfter(id, 0).stream().map(event -> event.type()).toList();
        }

        private TaskContractV3 contract(RunId id) {
            return TaskResultEvaluator.latestContractV3(runs.eventsAfter(id, 0), JSON).orElseThrow();
        }
    }
}
