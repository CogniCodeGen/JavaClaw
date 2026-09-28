package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunSnapshot;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.OnDemandContextPolicy;
import com.javaclaw.framework.core.ReasoningEventSink;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunControl;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.StepEvents;
import com.javaclaw.framework.spi.CreateRunResult;
import com.javaclaw.framework.spi.DeferredContextCandidate;
import com.javaclaw.framework.spi.DeferredContextSource;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnDemandContextPlannerTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Test
    void 恢复输入参与规划且低预算按实际Json长度收缩() {
        Fixture fixture = new Fixture("旧任务" + "\"\\".repeat(2_000));
        fixture.resume("请根据上海明天的天气重新选择资料和工具", 1_000);
        List<OnDemandHistoryCatalog.HistoryCandidate> history = List.of(
                new OnDemandHistoryCatalog.HistoryCandidate("old", List.of(), "旧摘要".repeat(200)));

        ObjectNode input = fixture.planner(List.of(source("memory", "记忆".repeat(100))))
                .firstInput(List.<Message>of(), history);

        assertTrue(input.toString().length() <= 1_000);
        assertTrue(input.path("latestUserInput").asText().contains("上海明天"));
        assertEquals("memory", input.path("sources").get(0).path("id").asText());
        assertTrue(input.path("task").asText().length() < 2_400);
    }

    @Test
    void 原始长任务在合法的一千字符预算内仍可规划() {
        Fixture fixture = new Fixture("原始任务" + "\"\\".repeat(2_000));
        fixture.policy = new OnDemandContextPolicy(2, 3, 32, 1_000, 12_000, 8);

        ObjectNode input = fixture.planner(List.of(source("memory", "记忆")))
                .firstInput(List.of(), List.of());

        assertTrue(input.toString().length() <= 1_000);
        assertEquals("memory", input.path("sources").get(0).path("id").asText());
        assertFalse(input.has("latestUserInput"));
    }

    @Test
    void 来源目录占用大部分预算时任务摘要可缩至很短() {
        Fixture fixture = new Fixture("原始任务" + "甲".repeat(5_000));
        fixture.policy = new OnDemandContextPolicy(2, 3, 32, 1_000, 12_000, 8);
        String sourceId = "s".repeat(590);

        ObjectNode input = fixture.planner(List.of(source(sourceId, "描述".repeat(100))))
                .firstInput(List.of(), List.of());

        assertTrue(input.toString().length() <= 1_000);
        assertEquals(sourceId, input.path("sources").get(0).path("id").asText());
        assertTrue(input.path("task").asText().length() < 120);
    }

    @Test
    void 搜索修复优先使用新输入且不超过预算() {
        Fixture fixture = new Fixture("旧任务" + "甲".repeat(2_000));
        fixture.resume("这次请查杭州周六亲子活动", 1_000);
        List<DeferredContextSource> sources = List.of(source("memory", "记忆"));
        OnDemandContextPlanner planner = fixture.planner(sources);
        ObjectNode first = planner.firstInput(List.of(), List.of());
        ArrayNode rejected = NODES.arrayNode();
        rejected.addObject().put("source", "unknown").put("query", "上海旧活动");

        planner.repairSearches("repair-turn", first, rejected);

        JsonNode received = fixture.lastModelInput;
        assertTrue(received.toString().length() <= 1_000);
        assertTrue(received.path("latestUserInput").asText().contains("杭州周六"));
        assertEquals("memory", received.path("allowedSources").get(0).asText());
    }

    @Test
    void 已持久化当前规划和修复按输入核对后重放() {
        Fixture fixture = new Fixture("原始任务");
        String key = "same-step";
        List<DeferredContextSource> sources = List.of(source("memory", "记忆"));
        OnDemandContextPlanner original = fixture.planner(sources);
        ObjectNode first = original.firstInput(List.of(), List.of());
        ObjectNode chosen = NODES.objectNode();
        chosen.putArray("searches");
        chosen.putArray("historyIds");
        chosen.putObject("toolIntent").put("query", "").putArray("groups");
        StepId id = StepId.tool(fixture.runId, "context/plan/" + key + "/select_v2");
        ObjectNode stepInput = NODES.objectNode().put("phase", "select_v2").put("key", key);
        stepInput.set("plannerInput", first);
        StepEvents.started(fixture.events, id, AgentStep.Kind.ORCHESTRATION, stepInput, null);
        StepEvents.completed(fixture.events, id,
                NODES.objectNode().set("selection", chosen), null);
        ArrayNode rejected = NODES.arrayNode();
        rejected.addObject().put("source", "unknown").put("query", "old query");
        JsonNode repaired = original.repairSearches(key, first, rejected);
        int callsBeforeResume = fixture.modelCalls.get();

        OnDemandContextPlanner resumed = fixture.planner(sources);
        ObjectNode replayInput = resumed.firstInput(List.of(), List.of());

        assertEquals(first, replayInput);
        assertFalse(replayInput.has("latestUserInput"));
        assertEquals(chosen, resumed.stage("select_v2", key, replayInput,
                OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        assertEquals(repaired, resumed.repairSearches(key, replayInput, rejected));
        assertEquals(callsBeforeResume, fixture.modelCalls.get());
    }

    @Test
    void 输入后工具审批复用持久化用户消息与规划() {
        Fixture fixture = new Fixture("原始任务");
        fixture.resume("请按上海的日期选择资料", 8_000);
        ResumeCommand inputCommand = fixture.resume;
        ObjectNode resumed = NODES.objectNode().put("commandType", "input");
        resumed.set("command", inputCommand.payload());
        fixture.events.emit("core.run.resumed", 1, "framework.core", resumed);
        String key = "approved-read";
        OnDemandContextPlanner original = fixture.planner(List.of());
        ObjectNode first = original.firstInput(List.of(), List.of());
        ObjectNode chosen = NODES.objectNode();
        chosen.putArray("searches");
        chosen.putArray("historyIds");
        chosen.putObject("toolIntent").put("query", "").putArray("groups");
        StepId id = StepId.tool(fixture.runId, "context/plan/" + key + "/select_v2");
        ObjectNode stepInput = NODES.objectNode().put("phase", "select_v2").put("key", key);
        stepInput.set("plannerInput", first);
        StepEvents.started(fixture.events, id, AgentStep.Kind.ORCHESTRATION, stepInput, null);
        StepEvents.completed(fixture.events, id,
                NODES.objectNode().set("selection", chosen), null);
        fixture.resume = new ResumeCommand("tool.approval",
                NODES.objectNode().put("fingerprint", "read").put("approved", true));
        OnDemandContextPlanner approved = fixture.planner(List.of());

        assertEquals(SpringAiPromptFactory.resumeCommandMessage(inputCommand).getText(),
                approved.pendingInputResumeMessage().getText());
        ObjectNode replayInput = approved.firstInput(List.of(), List.of());
        assertEquals(first, replayInput);
        assertEquals(chosen, approved.stage("select_v2", key, replayInput,
                OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        assertEquals(0, fixture.modelCalls.get());

        StepEvents.started(fixture.events, StepId.random(), AgentStep.Kind.MODEL,
                NODES.objectNode(), null);
        assertNull(approved.pendingInputResumeMessage());
    }

    @Test
    void 必需来源目录无法容纳时明确暂停() {
        Fixture fixture = new Fixture("task");
        fixture.resume("上海", 1_000);
        List<DeferredContextSource> sources = List.of(
                source("a".repeat(550), "a"), source("b".repeat(550), "b"));

        RuntimeException failure = assertThrows(ContextPlanningRequiredException.class,
                () -> fixture.planner(sources).firstInput(List.of(), List.of()));

        assertTrue(failure.getMessage().contains("source directory"));
    }

    private static DeferredContextSource source(String id, String description) {
        return new DeferredContextSource() {
            @Override public String id() { return id; }
            @Override public String description() { return description; }
            @Override public PermissionSet requiredPermissions() { return PermissionSet.NONE; }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) { return List.of(); }
            @Override public String fetch(RunRequest request, String candidateId, String version) {
                return "";
            }
        };
    }

    private static final class Fixture {
        private final RunId runId = RunId.random();
        private final RunRequest runRequest;
        private final Store store;
        private final ReasoningEventSink events;
        private final AtomicInteger modelCalls = new AtomicInteger();
        private ResumeCommand resume;
        private OnDemandContextPolicy policy = OnDemandContextPolicy.DEFAULT;
        private JsonNode lastModelInput;

        private Fixture(String task) {
            runRequest = RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test"))
                    .profile(RunProfileRef.latest("chat"))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "thread"))
                    .input(InputBlock.text(task)).build();
            store = new Store(runId, runRequest);
            events = store::record;
        }

        private void resume(String text, int budget) {
            resume = new ResumeCommand("input", NODES.objectNode().put("text", text));
            policy = new OnDemandContextPolicy(2, 3, 32, budget, 12_000, 8);
        }

        private OnDemandContextPlanner planner(List<DeferredContextSource> sources) {
            ModelTaskGateway gateway = request -> {
                modelCalls.incrementAndGet();
                lastModelInput = request.input();
                ObjectNode output = NODES.objectNode();
                output.putArray("searches").addObject()
                        .put("source", "memory").put("query", "new query");
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        output, "test", 1, 1, false, Map.of()));
            };
            ReasoningRequest reasoning = new ReasoningRequest(runId, null,
                    runRequest, resume, control(), events);
            return new OnDemandContextPlanner(reasoning, policy, null, gateway,
                    store, new RunStepQuery(store), new ObjectMapper(), sources);
        }

        private static RunControl control() {
            try {
                Constructor<RunControl> constructor = RunControl.class.getDeclaredConstructor(
                        RunBudget.class, Clock.class);
                constructor.setAccessible(true);
                return constructor.newInstance(new RunBudget(Duration.ofHours(1),
                        100_000, 100_000, 20, BigDecimal.TEN), Clock.systemUTC());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class Store implements RunStore {
        private final RunId runId;
        private final RunRequest request;
        private final List<RunEventEnvelope> events = new ArrayList<>();

        private Store(RunId runId, RunRequest request) {
            this.runId = runId;
            this.request = request;
        }

        private void record(String type, int version, String producer, JsonNode payload) {
            events.add(new RunEventEnvelope(runId.value(), events.size() + 1,
                    Instant.now(), type, version, producer, null, null, payload));
        }

        @Override public Optional<StoredRun> find(RunId id) {
            if (!runId.equals(id)) return Optional.empty();
            RunSnapshot snapshot = new RunSnapshot(id, RunState.RUNNING, "plan", events.size(),
                    Instant.now(), Instant.now(), null, null, 0);
            return Optional.of(new StoredRun(snapshot, request));
        }

        @Override public List<RunEventEnvelope> eventsAfter(RunId id, long afterSequence) {
            return events.stream().filter(event -> event.sequence() > afterSequence).toList();
        }

        @Override public CreateRunResult create(RunId id, RunRequest request,
                String executionPlanId, RunEventDraft event) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<StoredRun> findByIdempotencyKey(String workspaceId, String key) {
            return Optional.empty();
        }

        @Override public List<StoredRun> nonTerminalRuns() { return List.of(); }

        @Override public Optional<RunEventEnvelope> append(RunId id, Set<RunState> expectedStates,
                RunState nextState, RunEventDraft event, JsonNode output, String error) {
            throw new UnsupportedOperationException();
        }
    }
}
