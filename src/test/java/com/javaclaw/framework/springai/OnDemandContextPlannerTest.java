package com.javaclaw.framework.springai;

import com.fasterxml.jackson.core.JsonParseException;
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
import com.javaclaw.framework.core.TaskContractCompiler;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.lang.reflect.Constructor;
import java.io.IOException;
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
    void completionRepairIsAnExplicitBoundedPlannerInputRatherThanOrdinaryHistory() {
        Fixture fixture = new Fixture("长任务".repeat(2_000));
        fixture.policy = new OnDemandContextPolicy(2, 3, 32, 1_000, 12_000, 8);
        var repair = TaskRepairContext.fromEvent(TaskRepairContextTest.event(
                fixture.runId.value(), "framework.springai", 2, "model"),
                fixture.runId.value(), "model");

        ObjectNode input = fixture.planner(List.of()).firstInput(List.of(repair), List.of());

        assertTrue(input.toString().length() <= 1_000);
        assertTrue(input.path("taskRepairFeedback").asText().contains("显示文档预览页"));
    }

    @Test
    void resolvedGoalAndCurrentHumanContinuationBothReachPlanningAndTheMainPrompt() {
        Fixture fixture = new Fixture("Please carry on; access is enabled now");
        fixture.runRequest = fixture.runRequest.withAttribute(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE,
                NODES.textNode("Open the calendar and inspect tomorrow's events"));

        ObjectNode input = fixture.planner(List.of()).firstInput(List.of(), List.of());
        assertTrue(input.path("task").asText().contains("inspect tomorrow's events"));
        assertEquals("Please carry on; access is enabled now", input.path("latestUserInput").asText());
        String mainPrompt = SpringAiPromptFactory.originalTaskMessage(new ReasoningRequest(
                fixture.runId, null, fixture.runRequest, null, null, null)).getText();
        assertTrue(mainPrompt.contains("inspect tomorrow's events"));
        assertTrue(mainPrompt.contains("Please carry on; access is enabled now"));
        assertTrue(mainPrompt.contains("takes precedence for new goals, cancellation"));
        assertTrue(mainPrompt.contains("not proof of current permissions"));
    }

    @Test
    void explicitGoalPrecedesResolvedGoalWhileCurrentStopRemainsVisible() {
        Fixture fixture = new Fixture("Stop; do not open any application");
        fixture.runRequest = fixture.runRequest
                .withAttribute(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE, NODES.textNode("stale guessed task"))
                .withAttribute(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE, NODES.textNode("host-defined original goal"));

        ObjectNode input = fixture.planner(List.of()).firstInput(List.of(), List.of());
        assertTrue(input.path("task").asText().contains("host-defined original goal"));
        assertFalse(input.path("task").asText().contains("stale guessed task"));
        assertEquals("Stop; do not open any application", input.path("latestUserInput").asText());
    }

    @Test
    void freshRuntimeStateIsRetainedBeforeOptionalHistoryWithinThePlannerBudget() {
        Fixture fixture = new Fixture("inspect the application" + "x".repeat(3_000));
        fixture.policy = new OnDemandContextPolicy(2, 3, 32, 1_000, 12_000, 8);
        ObjectNode runtime = NODES.objectNode().put("capability", "desktop")
                .put("computerAppAccessEnabled", true);
        List<OnDemandHistoryCatalog.HistoryCandidate> history = List.of(
                new OnDemandHistoryCatalog.HistoryCandidate("old", List.of(),
                        "old assistant denial".repeat(100)));

        ObjectNode input = fixture.planner(List.of()).firstInput(List.of(), history, "", List.of(runtime));

        assertTrue(input.toString().length() <= 1_000);
        assertEquals(runtime, input.path("runtimeContext").get(0));
        assertTrue(input.path("instruction").asText().contains("fresh host context"));
        assertTrue(input.path("instruction").asText().contains("not an action receipt"));
        assertTrue(input.path("history").isEmpty());
    }

    @Test
    void minimumBudgetRetainsResolvedGoalContinuationAndFreshStateWithoutPausing() {
        Fixture fixture = new Fixture("Please carry on");
        fixture.policy = new OnDemandContextPolicy(2, 3, 32, 1_000, 12_000, 8);
        fixture.runRequest = fixture.runRequest.withAttribute(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE,
                NODES.textNode("Open the calendar and inspect tomorrow's events"));
        ObjectNode runtime = NODES.objectNode().put("capability", "desktop")
                .put("settingEnabled", true).put("systemStatus", "NOT_CHECKED");
        runtime.put("guidance", "x".repeat(350 - runtime.toString().length() - 14));
        assertEquals(350, runtime.toString().length());

        ObjectNode input = fixture.planner(List.of()).firstInput(List.of(), List.of(), "", List.of(runtime));

        assertTrue(input.toString().length() <= 1_000);
        assertEquals("Open the calendar and inspect tomorrow's events", input.path("task").asText());
        assertEquals("Please carry on", input.path("latestUserInput").asText());
        assertEquals(runtime, input.path("runtimeContext").get(0));
        assertTrue(input.path("runtimeContext").get(0).path("settingEnabled").asBoolean());
        assertEquals("NOT_CHECKED", input.path("runtimeContext").get(0).path("systemStatus").asText());
    }

    @Test
    void runtimeStateTargetsAndRepairFitMinimumBudgetWithoutLosingHumanIntent() {
        for (String current : List.of("Please carry on", "Stop. Do not open any application.")) {
            Fixture fixture = new Fixture(current);
            fixture.policy = new OnDemandContextPolicy(2, 3, 32, 1_000, 12_000, 8);
            String goal = "Open the calendar and inspect tomorrow's events";
            fixture.runRequest = fixture.runRequest.withAttribute(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE,
                    NODES.textNode(goal));
            ObjectNode runtime = NODES.objectNode().put("capability", "desktop")
                    .put("settingEnabled", true).put("systemStatus", "NOT_CHECKED");
            runtime.put("instruction", "x".repeat(350 - runtime.toString().length() - 17));
            assertEquals(350, runtime.toString().length());
            var repair = TaskRepairContext.fromEvent(TaskRepairContextTest.event(
                    fixture.runId.value(), "framework.springai", 2, "model"),
                    fixture.runId.value(), "model");

            ObjectNode input = fixture.planner(List.of()).firstInput(List.of(repair), List.of(),
                    "Calendar: Tomorrow tab", List.of(runtime));

            assertTrue(input.toString().length() <= 1_000);
            assertEquals(goal, input.path("task").asText());
            assertEquals(current, input.path("latestUserInput").asText());
            assertTrue(input.path("runtimeContext").get(0).path("settingEnabled").asBoolean());
            assertEquals("NOT_CHECKED", input.path("runtimeContext").get(0).path("systemStatus").asText());
            assertFalse(input.path("runtimeContext").get(0).has("instruction"));
            assertTrue(input.path("taskRepairFeedback").asText().contains("显示文档预览页"));
            assertFalse(input.path("observedDesktopTargets").asText().isBlank());
        }
    }

    @Test
    void latestToolEvidenceKeepsHeaderAndVisualTailWithoutAssistantReasoning() {
        var call = AssistantMessage.builder().content("hidden assistant reasoning".repeat(400))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "observe", "{}")))
                .build();
        var response = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call", "observe",
                        "observationId=frame-1\n" + "AXGroup blank\n".repeat(300)
                                + "visual target: document preview tab; current view: messages"))).build();

        String summary = OnDemandContextPlanner.latestExchangeSummary(List.of(call, response), 1_200);

        assertTrue(summary.length() <= 1_200);
        assertTrue(summary.contains("observationId=frame-1"));
        assertTrue(summary.contains("document preview tab"));
        assertTrue(summary.contains("Untrusted tool result data"));
        assertFalse(summary.contains("hidden assistant reasoning"));
        assertFalse(summary.contains("AssistantMessage"));
    }

    @Test
    void 恢复输入参与规划且低预算按实际Json长度收缩() {
        Fixture fixture = new Fixture("旧任务" + "\"\\".repeat(2_000));
        fixture.resume("请根据上海明天的天气重新选择资料和工具", 1_000);
        List<OnDemandHistoryCatalog.HistoryCandidate> history = List.of(
                new OnDemandHistoryCatalog.HistoryCandidate("old", List.of(), "旧摘要".repeat(200)));

        ObjectNode input = fixture.planner(List.of(source("memory", "记忆".repeat(100))))
                .firstInput(List.<Message>of(), history);

        assertTrue(input.toString().length() <= 1_000);
        assertEquals("请根据上海明天的天气重新选择资料和工具", input.path("latestUserInput").asText());
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
    void 来源目录占用大部分预算时保留来源ID和非空任务摘要() {
        Fixture fixture = new Fixture("原始任务" + "甲".repeat(5_000));
        fixture.policy = new OnDemandContextPolicy(2, 3, 32, 1_000, 12_000, 8);
        String sourceId = "s".repeat(590);

        ObjectNode input = fixture.planner(List.of(source(sourceId, "描述".repeat(100))))
                .firstInput(List.of(), List.of());

        assertTrue(input.toString().length() <= 1_000);
        assertEquals(sourceId, input.path("sources").get(0).path("id").asText());
        assertTrue(input.path("task").asText().startsWith("原始任务"));
        assertTrue(input.path("task").asText().length() < 2_400);
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
    void completedSelectionReplaysAcrossRuntimeRefreshAndBudgetProjectionChangesOnly() {
        Fixture fixture = new Fixture("Please carry on");
        fixture.policy = new OnDemandContextPolicy(2, 3, 32, 1_000, 12_000, 8);
        fixture.runRequest = fixture.runRequest.withAttribute(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE,
                NODES.textNode("Open the calendar and inspect tomorrow's events. " + "item ".repeat(60)));
        List<DeferredContextSource> sources = List.of(source("memory", "Memory"));
        OnDemandContextPlanner original = fixture.planner(sources);
        ObjectNode beforeState = NODES.objectNode().put("settingEnabled", false)
                .put("systemStatus", "NOT_CHECKED");
        ObjectNode before = original.firstInput(List.of(), List.of(), "", List.of(beforeState));
        JsonNode selection = original.stage("select_v2", "runtime-refresh", before,
                OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA);
        OnDemandContextSelectionInputs originalInputs = new OnDemandContextSelectionInputs(
                fixture.policy, original, sources);
        ObjectNode beforeRefinement = originalInputs.secondInputV2(selection, List.of(), List.of(),
                new OnDemandToolSelection.Snapshot(List.of(), null), "", before);
        JsonNode refinement = original.stage("refine_v2", "runtime-refresh", beforeRefinement,
                OnDemandContextPlanner.STAGE_TWO_V2_SCHEMA);
        ArrayNode rejected = NODES.arrayNode();
        rejected.addObject().put("source", "unknown").put("query", "old query");
        JsonNode repair = original.repairSearches("runtime-refresh", before, rejected);
        int calls = fixture.modelCalls.get();

        OnDemandContextPlanner resumed = fixture.planner(sources);
        ObjectNode currentState = NODES.objectNode().put("settingEnabled", true)
                .put("systemStatus", "NOT_CHECKED").put("detail", "x".repeat(208));
        ObjectNode current = resumed.firstInput(List.of(), List.of(), "", List.of(currentState));
        assertFalse(before.path("task").equals(current.path("task")),
                "runtime length changed the bounded projection but not the actual human goal");
        assertFalse(current.path("task").asText().isBlank());
        assertEquals(selection, resumed.stage("select_v2", "runtime-refresh", current,
                OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        assertEquals(before, current, "later planning must reuse the original bounded snapshot");
        OnDemandContextSelectionInputs resumedInputs = new OnDemandContextSelectionInputs(
                fixture.policy, resumed, sources);
        ObjectNode currentRefinement = resumedInputs.secondInputV2(selection, List.of(), List.of(),
                new OnDemandToolSelection.Snapshot(List.of(), null), "", current);
        assertEquals(beforeRefinement, currentRefinement);
        assertEquals(refinement, resumed.stage("refine_v2", "runtime-refresh", currentRefinement,
                OnDemandContextPlanner.STAGE_TWO_V2_SCHEMA));
        assertEquals(repair, resumed.repairSearches("runtime-refresh", current, rejected));
        assertEquals(calls, fixture.modelCalls.get());

        ObjectNode changedGoal = current.deepCopy().put("task", "different task");
        assertThrows(ContextPlanningRequiredException.class,
                () -> resumed.stage("select_v2", "runtime-refresh", changedGoal,
                        OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        OnDemandContextPlanner changedAuthorization = fixture.planner(List.of(source("other", "Other")));
        ObjectNode changedSources = changedAuthorization.firstInput(List.of(), List.of(), "", List.of(currentState));
        assertThrows(ContextPlanningRequiredException.class,
                () -> changedAuthorization.stage("select_v2", "runtime-refresh", changedSources,
                        OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        assertEquals(calls, fixture.modelCalls.get());
    }

    @Test
    void fullGoalChangesCannotHideBehindIdenticalBoundedPlannerInputs() {
        Fixture fixture = new Fixture("Please carry on");
        fixture.policy = new OnDemandContextPolicy(2, 3, 32, 1_000, 12_000, 8);
        String prefix = "Open the calendar " + "a".repeat(2_000);
        String suffix = "b".repeat(2_000) + " inspect tomorrow";
        fixture.runRequest = fixture.runRequest.withAttribute(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE,
                NODES.textNode(prefix + "old middle" + suffix));
        OnDemandContextPlanner original = fixture.planner(List.of());
        ObjectNode before = original.firstInput(List.of(), List.of());
        original.stage("select_v2", "same-bounded-input", before,
                OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA);
        fixture.runRequest = fixture.runRequest.withAttribute(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE,
                NODES.textNode(prefix + "new middle" + suffix));
        OnDemandContextPlanner changed = fixture.planner(List.of());
        ObjectNode current = changed.firstInput(List.of(), List.of());
        assertEquals(before, current, "the changed goal text lies inside the omitted middle");

        assertThrows(ContextPlanningRequiredException.class,
                () -> changed.stage("select_v2", "same-bounded-input", current,
                        OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        assertEquals(1, fixture.modelCalls.get());
    }

    @Test
    void runningSelectionRecoveryMatchesTheActualPersistedModelTaskInputAfterRuntimeRefresh() throws Exception {
        Fixture fixture = new Fixture("Open the calendar");
        OnDemandContextPlanner original = fixture.planner(List.of());
        ObjectNode before = original.firstInput(List.of(), List.of(), "", List.of(
                NODES.objectNode().put("settingEnabled", false)));
        String key = "pending-runtime-refresh";
        StepId planning = StepId.tool(fixture.runId, "context/plan/" + key + "/select_v2");
        StepEvents.started(fixture.events, planning, AgentStep.Kind.ORCHESTRATION,
                NODES.objectNode().put("phase", "select_v2").put("key", key)
                        .set("plannerInput", before), null);
        ObjectMapper json = new ObjectMapper();
        JsonNode schema = json.readTree(OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA);
        StepId modelTask = StepId.random();
        String inputHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(before.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ObjectNode taskInput = NODES.objectNode().put("purpose", "context.on_demand.select_v2")
                .put("inputHash", inputHash);
        taskInput.set("outputSchema", schema);
        StepEvents.started(fixture.events, modelTask, AgentStep.Kind.MODEL_TASK, taskInput, null);
        ObjectNode chosen = NODES.objectNode();
        chosen.putArray("searches");
        chosen.putArray("historyIds");
        chosen.putObject("toolIntent").put("query", "").putArray("groups");
        ObjectNode taskOutput = NODES.objectNode();
        taskOutput.putObject("message").put("role", "assistant").put("text", chosen.toString())
                .putArray("toolCalls");
        StepEvents.completed(fixture.events, modelTask, taskOutput, null);

        OnDemandContextPlanner resumed = fixture.planner(List.of());
        ObjectNode current = resumed.firstInput(List.of(), List.of(), "", List.of(
                NODES.objectNode().put("settingEnabled", true)));
        assertEquals(chosen, resumed.stage("select_v2", key, current,
                OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        assertEquals(0, fixture.modelCalls.get());
        assertEquals(AgentStep.State.COMPLETED,
                new RunStepQuery(fixture.store).step(fixture.runId, planning).orElseThrow().state());
    }

    @Test
    void runningRefinementRecoversLegacyIdDescriptionsButKeepsValidationConstraintsStrict() throws Exception {
        for (String difference : List.of("descriptions-only", "maxItems", "description-property")) {
            Fixture fixture = new Fixture("inspect the report");
            String key = "legacy-refinement";
            ObjectNode input = NODES.objectNode().put("task", "inspect the report");
            StepId planning = StepId.tool(fixture.runId, "context/plan/" + key + "/refine_v2");
            StepEvents.started(fixture.events, planning, AgentStep.Kind.ORCHESTRATION,
                    NODES.objectNode().put("phase", "refine_v2").put("key", key)
                            .set("plannerInput", input), null);
            ObjectNode legacySchema = (ObjectNode) new ObjectMapper().readTree(
                    OnDemandContextPlanner.STAGE_TWO_V2_SCHEMA);
            for (String field : List.of("historyIds", "sourceIds", "toolIds")) {
                ((ObjectNode) legacySchema.path("properties").path(field)).remove("description");
            }
            if (difference.equals("maxItems")) {
                ((ObjectNode) legacySchema.path("properties").path("sourceIds")).put("maxItems", 0);
            } else if (difference.equals("description-property")) {
                ((ObjectNode) legacySchema.path("properties")).putObject("description").put("type", "string");
            }
            StepId task = StepId.random();
            String inputHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            ObjectNode taskInput = NODES.objectNode().put("purpose", "context.on_demand.refine_v2")
                    .put("inputHash", inputHash);
            taskInput.set("outputSchema", legacySchema);
            StepEvents.started(fixture.events, task, AgentStep.Kind.MODEL_TASK, taskInput, null);
            ObjectNode selected = NODES.objectNode().put("toolAction", "none");
            selected.putArray("sourceIds");
            selected.putArray("historyIds");
            selected.putArray("toolIds");
            ObjectNode taskOutput = NODES.objectNode();
            taskOutput.putObject("message").put("role", "assistant").put("text", selected.toString())
                    .putArray("toolCalls");
            StepEvents.completed(fixture.events, task, taskOutput, null);
            OnDemandContextPlanner resumed = fixture.planner(List.of());

            if (difference.equals("descriptions-only")) {
                assertEquals(selected, resumed.stage("refine_v2", key, input,
                        OnDemandContextPlanner.STAGE_TWO_V2_SCHEMA));
                assertEquals(AgentStep.State.COMPLETED,
                        new RunStepQuery(fixture.store).step(fixture.runId, planning).orElseThrow().state());
            } else {
                assertThrows(ContextPlanningRequiredException.class,
                        () -> resumed.stage("refine_v2", key, input,
                                OnDemandContextPlanner.STAGE_TWO_V2_SCHEMA));
                assertEquals(AgentStep.State.RUNNING,
                        new RunStepQuery(fixture.store).step(fixture.runId, planning).orElseThrow().state());
            }
            assertEquals(0, fixture.modelCalls.get());
        }
    }

    @Test
    void 失败的规划在同一运行恢复后重试并在成功后重放() {
        Fixture fixture = new Fixture("打开文档管理器查看最近文件");
        fixture.modelFailuresRemaining.set(1);
        String key = "select-after-resume";
        ObjectNode input = fixture.planner(List.of()).firstInput(List.of(), List.of());

        ContextPlanningRequiredException firstFailure = assertThrows(
                ContextPlanningRequiredException.class,
                () -> fixture.planner(List.of()).stage("select_v2", key, input,
                        OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        assertTrue(firstFailure.getMessage().contains("select_v2"));
        assertEquals(1, fixture.modelCalls.get());

        ObjectNode changedInput = input.deepCopy().put("task", "different task");
        ContextPlanningRequiredException mismatch = assertThrows(
                ContextPlanningRequiredException.class,
                () -> fixture.planner(List.of()).stage("select_v2", key, changedInput,
                        OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        assertTrue(mismatch.getMessage().contains("input"));
        assertEquals(1, fixture.modelCalls.get());

        fixture.events.emit("core.run.resumed", 1, "framework.core",
                NODES.objectNode().put("commandType", "continue"));
        JsonNode recovered = fixture.planner(List.of()).stage("select_v2", key, input,
                OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA);
        assertEquals(2, fixture.modelCalls.get());
        List<AgentStep> planningSteps = new RunStepQuery(fixture.store).steps(fixture.runId)
                .stream().filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION).toList();
        assertEquals(2, planningSteps.size());
        assertEquals(AgentStep.State.FAILED, planningSteps.get(0).state());
        assertEquals(AgentStep.State.COMPLETED, planningSteps.get(1).state());

        assertEquals(recovered, fixture.planner(List.of()).stage("select_v2", key, input,
                OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        assertThrows(ContextPlanningRequiredException.class,
                () -> fixture.planner(List.of()).stage("select_v2", key, changedInput,
                        OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));
        assertEquals(2, fixture.modelCalls.get());
    }

    @Test
    void 轻量模型传输失败说明连接问题并保留可恢复的失败步骤() {
        Fixture fixture = new Fixture("打开文档管理器查看最近文件");
        fixture.modelFailuresRemaining.set(1);
        fixture.modelFailure = new IllegalStateException("Request failed",
                new IOException("Connection refused"));
        ObjectNode input = fixture.planner(List.of()).firstInput(List.of(), List.of());

        ContextPlanningRequiredException failure = assertThrows(
                ContextPlanningRequiredException.class,
                () -> fixture.planner(List.of()).stage("select_v2", "offline-service", input,
                        OnDemandContextPlanner.STAGE_ONE_V2_SCHEMA));

        assertEquals("无法连接轻量模型服务。请检查所选模型的服务是否运行及网络连接，然后重试本轮。",
                failure.getMessage());
        assertEquals(1, fixture.modelCalls.get());
        List<AgentStep> steps = new RunStepQuery(fixture.store).steps(fixture.runId);
        assertEquals(1, steps.size());
        assertEquals(AgentStep.State.FAILED, steps.getFirst().state());
    }

    @Test
    void 轻量模型输出非Json时不误报连接失败() {
        for (IOException cause : List.of(
                new JsonParseException(null, "Unrecognized token 'We'"),
                new IOException("model task has no standalone final JSON object"))) {
            Fixture fixture = new Fixture("打开文档管理器查看最近文件");
            fixture.modelFailuresRemaining.set(1);
            fixture.modelFailure = new ModelTaskOutputException(
                    ModelTaskOutputException.Reason.INVALID_JSON,
                    "model task did not return JSON", cause);
            ObjectNode input = fixture.planner(List.of()).firstInput(List.of(), List.of());

            ContextPlanningRequiredException failure = assertThrows(
                    ContextPlanningRequiredException.class,
                    () -> fixture.planner(List.of()).stage("refine_v2", "invalid-json", input,
                            OnDemandContextPlanner.STAGE_TWO_V2_SCHEMA));

            assertEquals("轻量模型已响应，但规划结果格式不符合要求。请重试本轮；若反复发生，请更换轻量模型。",
                    failure.getMessage());
            assertEquals(1, fixture.modelCalls.get());
            assertEquals(AgentStep.State.FAILED,
                    new RunStepQuery(fixture.store).steps(fixture.runId).getFirst().state());
        }
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

    @Test
    void emptyContextDirectoryClearsInvalidSourceIdsWithoutChangingToolsOrHistory() {
        Fixture fixture = new Fixture("inspect the application");
        OnDemandContextSelectionInputs inputs = new OnDemandContextSelectionInputs(
                fixture.policy, fixture.planner(List.of()), List.of());
        ObjectNode refinement = contextDirectory();
        for (JsonNode invalid : List.of(
                NODES.arrayNode().add("core.tool.completed:run:launch"),
                NODES.arrayNode().add("t0").add("t0"),
                NODES.textNode("desktop-session"), NODES.numberNode(4), NODES.nullNode())) {
            ObjectNode decision = contextDecision(invalid);
            ObjectNode original = decision.deepCopy();

            JsonNode corrected = inputs.repairContextSelectionIfNeeded(decision, refinement, "empty-context");

            assertTrue(corrected.path("sourceIds").isArray());
            assertTrue(corrected.path("sourceIds").isEmpty());
            assertEquals(original.path("toolIds"), corrected.path("toolIds"));
            assertEquals(original.path("toolAction"), corrected.path("toolAction"));
            assertEquals(original.path("historyIds"), corrected.path("historyIds"));
            assertEquals(original, decision);
        }
        assertEquals(0, fixture.modelCalls.get());
    }

    @Test
    void nonemptyContextRepairUsesOnlyAdvertisedBodyIdsAndReplaysDurably() {
        Fixture fixture = new Fixture("read the report");
        fixture.contextRepairOutput = NODES.objectNode().set("sourceIds", NODES.arrayNode().add("docs:one"));
        ObjectNode refinement = contextDirectory("docs:one");
        refinement.putArray("toolCandidates").addObject().put("id", "t0");
        refinement.putArray("history").addObject().put("id", "h0");
        refinement.put("latest", "evidenceRefs=[core.tool.completed:run:launch]");
        ObjectNode decision = contextDecision(NODES.arrayNode().add("core.tool.completed:run:launch"));
        List<DeferredContextSource> sources = List.of(source("docs", "Reports"), source("hidden", "Hidden"));
        OnDemandContextPlanner planner = fixture.planner(sources);
        OnDemandContextSelectionInputs inputs = new OnDemandContextSelectionInputs(fixture.policy, planner, sources);

        JsonNode corrected = inputs.repairContextSelectionIfNeeded(decision, refinement, "body-repair");

        assertEquals(NODES.arrayNode().add("docs:one"), corrected.path("sourceIds"));
        assertEquals(decision.path("toolIds"), corrected.path("toolIds"));
        assertEquals(decision.path("historyIds"), corrected.path("historyIds"));
        assertEquals(decision.path("toolAction"), corrected.path("toolAction"));
        assertEquals(NODES.arrayNode().add("docs:one"), fixture.lastModelInput.path("allowedSourceIds"));
        JsonNode idsSchema = fixture.lastModelSchema.path("properties").path("sourceIds");
        assertEquals(NODES.arrayNode().add("docs:one"), idsSchema.path("items").path("enum"));
        assertTrue(idsSchema.path("uniqueItems").asBoolean());
        assertEquals(fixture.policy.fetches(), idsSchema.path("maxItems").asInt());
        assertEquals(1, fixture.lastModelSchema.path("properties").size());
        assertTrue(fixture.lastModelSchema.path("properties").has("sourceIds"));
        assertEquals(1, fixture.modelCalls.get());

        OnDemandContextSelectionInputs restarted = new OnDemandContextSelectionInputs(
                fixture.policy, fixture.planner(sources), sources);
        assertEquals(corrected, restarted.repairContextSelectionIfNeeded(decision, refinement, "body-repair"));
        assertEquals(1, fixture.modelCalls.get());
        assertEquals(NODES.arrayNode().add("core.tool.completed:run:launch"), decision.path("sourceIds"));
    }

    @Test
    void contextRepairRejectsASecondInvalidSelectionWithoutAnotherPlannerCall() {
        List<JsonNode> invalid = List.of(
                NODES.arrayNode().add("t0"), NODES.arrayNode().add("core.tool.completed:run:launch"),
                NODES.arrayNode().add("docs:one").add("docs:one"),
                NODES.arrayNode().add("docs:one").add("docs:two"),
                NODES.textNode("docs:one"), NODES.arrayNode().add(1));
        for (JsonNode value : invalid) {
            Fixture fixture = new Fixture("read one report");
            fixture.policy = new OnDemandContextPolicy(2, 1, 32, 1_000, 12_000, 8);
            fixture.contextRepairOutput = NODES.objectNode().set("sourceIds", value);
            OnDemandContextSelectionInputs inputs = new OnDemandContextSelectionInputs(
                    fixture.policy, fixture.planner(List.of()), List.of());
            ObjectNode decision = contextDecision(NODES.arrayNode().add("unknown"));
            ObjectNode refinement = contextDirectory("docs:one", "docs:two");

            assertThrows(ContextPlanningRequiredException.class,
                    () -> inputs.repairContextSelectionIfNeeded(decision, refinement, "bad-repair"));
            assertEquals(1, fixture.modelCalls.get());
            assertThrows(ContextPlanningRequiredException.class,
                    () -> inputs.repairContextSelectionIfNeeded(decision, refinement, "bad-repair"));
            assertEquals(1, fixture.modelCalls.get());
            assertEquals(NODES.arrayNode().add("unknown"), decision.path("sourceIds"));
        }
    }

    @Test
    void contextRepairHandlesInvalidTypesDuplicatesAndFetchLimitBeforeMerging() {
        for (JsonNode value : List.of(NODES.textNode("docs:one"), NODES.arrayNode().add(1),
                NODES.arrayNode().add("docs:one").add("docs:one"),
                NODES.arrayNode().add("docs:one").add("docs:two"))) {
            Fixture fixture = new Fixture("read one report");
            fixture.policy = new OnDemandContextPolicy(2, 1, 32, 1_000, 12_000, 8);
            ObjectNode decision = contextDecision(value);
            OnDemandContextSelectionInputs inputs = new OnDemandContextSelectionInputs(
                    fixture.policy, fixture.planner(List.of()), List.of());

            JsonNode repaired = inputs.repairContextSelectionIfNeeded(decision,
                    contextDirectory("docs:one", "docs:two"), "invalid-original");

            assertTrue(repaired.path("sourceIds").isEmpty());
            assertEquals(1, fixture.modelCalls.get());
            assertEquals(decision.path("toolIds"), repaired.path("toolIds"));
            assertEquals(decision.path("historyIds"), repaired.path("historyIds"));
        }
    }

    @Test
    void contextRepairFitsSmallBudgetsAndNeverTruncatesTheAllowedIdDirectory() {
        Fixture fixture = new Fixture("read the report");
        fixture.policy = new OnDemandContextPolicy(2, 3, 32, 1_000, 12_000, 8);
        OnDemandContextSelectionInputs inputs = new OnDemandContextSelectionInputs(
                fixture.policy, fixture.planner(List.of()), List.of());
        ObjectNode refinement = contextDirectory("docs:one", "docs:two");
        ((ObjectNode) refinement.path("candidates").get(0)).put("summary", "x".repeat(3_000));
        refinement.put("task", "read the report".repeat(300));
        ObjectNode decision = contextDecision(NODES.arrayNode().add("\"\\".repeat(3_000)));

        inputs.repairContextSelectionIfNeeded(decision, refinement, "bounded-repair");

        assertTrue(fixture.lastModelInput.toString().length() <= 1_000);
        assertEquals(NODES.arrayNode().add("docs:one").add("docs:two"),
                fixture.lastModelInput.path("allowedSourceIds"));
        assertFalse(fixture.lastModelInput.has("candidateSummaries"));
        assertEquals(NODES.arrayNode().add("docs:one").add("docs:two"),
                fixture.lastModelSchema.path("properties").path("sourceIds").path("items").path("enum"));
        int calls = fixture.modelCalls.get();
        assertThrows(ContextPlanningRequiredException.class,
                () -> inputs.repairContextSelectionIfNeeded(decision,
                        contextDirectory("a".repeat(550), "b".repeat(550)), "oversize-directory"));
        assertEquals(calls, fixture.modelCalls.get());
    }

    @Test
    void validContextSelectionsNeedNoRepair() {
        Fixture fixture = new Fixture("read the report");
        OnDemandContextSelectionInputs inputs = new OnDemandContextSelectionInputs(
                fixture.policy, fixture.planner(List.of()), List.of());
        ObjectNode decision = contextDecision(NODES.arrayNode().add("docs:one"));

        assertEquals(decision, inputs.repairContextSelectionIfNeeded(decision,
                contextDirectory("docs:one"), "valid-context"));
        assertEquals(0, fixture.modelCalls.get());
    }

    private static ObjectNode contextDirectory(String... ids) {
        ObjectNode input = NODES.objectNode().put("task", "inspect the selected report");
        ArrayNode candidates = input.putArray("candidates");
        for (String id : ids) candidates.addObject().put("id", id).put("summary", "report summary");
        return input;
    }

    private static ObjectNode contextDecision(JsonNode sourceIds) {
        ObjectNode decision = NODES.objectNode().put("toolAction", "direct");
        decision.putArray("toolIds").add("t0");
        decision.putArray("historyIds").add("h0");
        decision.set("sourceIds", sourceIds.deepCopy());
        return decision;
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
        private RunRequest runRequest;
        private final Store store;
        private final ReasoningEventSink events;
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicInteger modelFailuresRemaining = new AtomicInteger();
        private Throwable modelFailure = new IllegalStateException("Request failed");
        private ResumeCommand resume;
        private OnDemandContextPolicy policy = OnDemandContextPolicy.DEFAULT;
        private JsonNode lastModelInput;
        private JsonNode lastModelSchema;
        private ObjectNode contextRepairOutput = NODES.objectNode().set("sourceIds", NODES.arrayNode());

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
                lastModelSchema = request.outputSchema();
                if (modelFailuresRemaining.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                    return CompletableFuture.failedFuture(modelFailure);
                }
                ObjectNode output = NODES.objectNode();
                if (request.purpose().equals("context.on_demand.repair_refine_sources_v2")) {
                    output = contextRepairOutput.deepCopy();
                } else if (request.purpose().equals("context.on_demand.refine_v2")) {
                    output.putArray("historyIds");
                    output.putArray("sourceIds");
                    output.put("toolAction", "none");
                    output.putArray("toolIds");
                } else {
                    output.putArray("searches").addObject()
                            .put("source", "memory").put("query", "new query");
                    if (request.purpose().equals("context.on_demand.select_v2")) {
                        output.putArray("historyIds");
                        output.putObject("toolIntent").put("query", "").putArray("groups");
                    }
                }
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
