package com.javaclaw.builtin.contracts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowScheduleContractsCoverageTest {
    private static final Instant NOW = BuiltinContractsFixtures.NOW;

    @Test
    void workflowAcceptsEverySafeNodeKindAndBothConditionBranches() {
        List<WorkflowContracts.Node> nodes = List.of(
                node("start", WorkflowContracts.NodeKind.START, Optional.empty(), WorkflowContracts.NodeConfig.empty()),
                node("turn", WorkflowContracts.NodeKind.TURN, Optional.of("执行"), WorkflowContracts.NodeConfig.empty()),
                node("tool", WorkflowContracts.NodeKind.TOOL, Optional.empty(), toolConfig()),
                node("condition", WorkflowContracts.NodeKind.CONDITION, Optional.empty(), conditionConfig()),
                node("set", WorkflowContracts.NodeKind.TRANSFORM, Optional.empty(), transformSetConfig()),
                node("copy", WorkflowContracts.NodeKind.TRANSFORM, Optional.empty(), transformCopyConfig()),
                node("remove", WorkflowContracts.NodeKind.TRANSFORM, Optional.empty(), transformRemoveConfig()),
                node("input", WorkflowContracts.NodeKind.USER_INPUT, Optional.empty(), inputConfig()),
                node("output", WorkflowContracts.NodeKind.OUTPUT, Optional.empty(), outputConfig()),
                node("end", WorkflowContracts.NodeKind.END, Optional.empty(), WorkflowContracts.NodeConfig.empty()));
        List<WorkflowContracts.Edge> edges = List.of(
                edge("start", "turn"),
                edge("turn", "tool"),
                edge("tool", "condition"),
                new WorkflowContracts.Edge("condition", "set", Optional.of("true")),
                new WorkflowContracts.Edge("condition", "copy", Optional.of("false")),
                edge("set", "remove"),
                edge("copy", "remove"),
                edge("remove", "input"),
                edge("input", "output"),
                edge("output", "end"));

        WorkflowContracts.Definition definition =
                new WorkflowContracts.Definition("flow", 1, "发布", nodes, edges, 50, NOW);

        assertEquals("TRUE", definition.edges().get(3).branch().orElseThrow());
        assertEquals(
                WorkflowContracts.TransformOperation.COPY,
                definition.nodes().get(5).config().transform().orElseThrow().operation());
    }

    @Test
    void workflowRejectsUnsafeConfigurationShapesAndInvalidInputWaits() {
        assertConditionOperators();
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowContracts.ConditionConfig(
                        "state", WorkflowContracts.ConditionOperator.EXISTS, Optional.of("unexpected")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowContracts.TransformConfig(
                        WorkflowContracts.TransformOperation.SET, "target", Optional.of("source"), Optional.of("x")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowContracts.TransformConfig(
                        WorkflowContracts.TransformOperation.COPY, "target", Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowContracts.TransformConfig(
                        WorkflowContracts.TransformOperation.REMOVE, "target", Optional.empty(), Optional.of("x")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowContracts.UserInputConfig(
                        "问题", BuiltinContractsFixtures.payload(), "answer", Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowContracts.UserInputConfig(
                        "问题", BuiltinContractsFixtures.payload(), "answer", Duration.ofDays(2)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowContracts.NodeConfig(
                        toolConfig().tool(),
                        conditionConfig().condition(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> node(
                        "turn",
                        WorkflowContracts.NodeKind.TURN,
                        Optional.empty(),
                        WorkflowContracts.NodeConfig.empty()));
    }

    @Test
    void workflowGraphRejectsDuplicateUnknownUnreachableAndMalformedEdges() {
        WorkflowContracts.Node start =
                node("start", WorkflowContracts.NodeKind.START, Optional.empty(), WorkflowContracts.NodeConfig.empty());
        WorkflowContracts.Node end =
                node("end", WorkflowContracts.NodeKind.END, Optional.empty(), WorkflowContracts.NodeConfig.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> definition(List.of(start, start, end), List.of(edge("start", "end"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> definition(List.of(start, end), List.of(edge("start", "missing"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> definition(List.of(start, end), List.of(edge("start", "end"), edge("start", "end"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> definition(
                        List.of(start, end), List.of(new WorkflowContracts.Edge("start", "end", Optional.of("TRUE")))));
        assertThrows(
                IllegalArgumentException.class,
                () -> definition(List.of(start, end), List.of(edge("start", "end"), edge("end", "start"))));
        WorkflowContracts.Node extra =
                node("extra", WorkflowContracts.NodeKind.END, Optional.empty(), WorkflowContracts.NodeConfig.empty());
        assertThrows(
                IllegalArgumentException.class,
                () -> definition(List.of(start, end, extra), List.of(edge("start", "end"))));
    }

    @Test
    void workflowCheckpointRequiresPairedInputIdentityAndNonNegativeVisits() {
        TurnId turnId = TurnId.parse(UUID.randomUUID().toString());
        WorkflowContracts.Checkpoint checkpoint = new WorkflowContracts.Checkpoint(
                "input",
                Map.of("answer", "yes"),
                Map.of("input", 1),
                Optional.of("request"),
                Optional.of(turnId),
                List.of("yes"));

        assertEquals(turnId, checkpoint.pendingInputTurnId().orElseThrow());
        assertEquals("request", new WorkflowContracts.ContinueInput("job", "request").requestId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowContracts.Checkpoint(
                        "input", Map.of(), Map.of(), Optional.of("request"), Optional.empty(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowContracts.Checkpoint(
                        "input", Map.of(), Map.of("input", -1), Optional.empty(), Optional.empty(), List.of()));
    }

    @Test
    void workflowManagementCopiesRowsAndEnforcesIdentityCountsAndTextBudget() {
        WorkflowManagementContracts.NodeRow start = row("start", WorkflowContracts.NodeKind.START, "");
        WorkflowManagementContracts.NodeRow end = row("end", WorkflowContracts.NodeKind.END, "");
        WorkflowManagementContracts.ToolArgumentRow argument = new WorkflowManagementContracts.ToolArgumentRow(
                "argument", "tool", "path", WorkflowManagementContracts.ScalarKind.STRING, "/tmp/file");
        WorkflowManagementContracts.InputFieldRow input = new WorkflowManagementContracts.InputFieldRow(
                "field", "input", "confirmed", WorkflowManagementContracts.InputKind.BOOLEAN, true);
        WorkflowManagementContracts.EdgeRow edge = new WorkflowManagementContracts.EdgeRow("edge", "start", "end", "");
        ArrayList<WorkflowManagementContracts.NodeRow> nodes = new ArrayList<>(List.of(start, end));
        WorkflowManagementContracts.SaveRequest request = new WorkflowManagementContracts.SaveRequest(
                "flow", "Workflow", nodes, List.of(argument), List.of(input), List.of(edge), 100);
        nodes.clear();

        assertEquals(2, request.nodes().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowManagementContracts.SaveRequest(
                        "flow", "Workflow", List.of(start), List.of(), List.of(), List.of(edge), 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowManagementContracts.SaveRequest(
                        "flow", "Workflow", List.of(start, end), List.of(), List.of(), List.of(edge, edge), 100));
    }

    @Test
    void workflowManagementRejectsInvalidNodeIdentityAndInputWaitBudget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowManagementContracts.NodeRow(
                        "bad id",
                        WorkflowContracts.NodeKind.START,
                        "name",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        0,
                        ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowManagementContracts.NodeRow(
                        "input",
                        WorkflowContracts.NodeKind.USER_INPUT,
                        "name",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "prompt",
                        "answer",
                        1_441,
                        ""));
    }

    @Test
    void workflowManagementRejectsOversizedTextBudget() {
        WorkflowManagementContracts.EdgeRow edge = new WorkflowManagementContracts.EdgeRow("edge", "start", "end", "");
        List<WorkflowManagementContracts.NodeRow> oversized = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            oversized.add(row("node-" + index, WorkflowContracts.NodeKind.TURN, "x".repeat(16_384)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowManagementContracts.SaveRequest(
                        "flow", "Workflow", oversized, List.of(), List.of(), List.of(edge), 100));
    }

    private static void assertConditionOperators() {
        assertEquals(
                WorkflowContracts.ConditionOperator.EQUALS,
                new WorkflowContracts.ConditionConfig(
                                "state", WorkflowContracts.ConditionOperator.EQUALS, Optional.of("ready"))
                        .operator());
        assertEquals(
                WorkflowContracts.ConditionOperator.NOT_EQUALS,
                new WorkflowContracts.ConditionConfig(
                                "state", WorkflowContracts.ConditionOperator.NOT_EQUALS, Optional.of("failed"))
                        .operator());
        assertEquals(
                WorkflowContracts.ConditionOperator.EXISTS,
                new WorkflowContracts.ConditionConfig(
                                "state", WorkflowContracts.ConditionOperator.EXISTS, Optional.empty())
                        .operator());
        assertEquals(
                WorkflowContracts.ConditionOperator.NOT_EXISTS,
                new WorkflowContracts.ConditionConfig(
                                "state", WorkflowContracts.ConditionOperator.NOT_EXISTS, Optional.empty())
                        .operator());
    }

    @Test
    void scheduleSupportsCronFixedIntervalAndAllTargetKinds() {
        OrchestrationContracts.ExecutionBudget budget = budget();
        AgentRoleRef profile = new AgentRoleRef("profile", 2);
        ScheduleContracts.DefinitionTarget definitionTarget = new ScheduleContracts.DefinitionTarget(
                "javaclaw.plan", "plan", 3, AutomationV6Fixtures.selection(profile), budget);
        ScheduleContracts.TurnTemplate template = new ScheduleContracts.TurnTemplate(
                AutomationV6Fixtures.selection(profile), "Daily review", "Review", budget);
        ScheduleActionContracts.Target action = actionTarget();

        assertEquals(
                ScheduleContracts.TimingKind.CRON,
                ScheduleContracts.Timing.cron("0 0 9 * * ?", "Asia/Shanghai").kind());
        assertEquals(
                Duration.ofMinutes(10),
                ScheduleContracts.Timing.fixed(Duration.ofMinutes(10), NOW)
                        .interval()
                        .orElseThrow());
        assertEquals(
                ScheduleContracts.TargetKind.DEFINITION,
                ScheduleContracts.Target.definition(definitionTarget).kind());
        assertEquals(
                ScheduleContracts.TargetKind.TURN_TEMPLATE,
                ScheduleContracts.Target.turn(template).kind());
        assertEquals(
                ScheduleContracts.TargetKind.ACTION,
                ScheduleContracts.Target.action(action).kind());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleContracts.Timing(
                        ScheduleContracts.TimingKind.CRON,
                        Optional.empty(),
                        "UTC",
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleContracts.Target(
                        ScheduleContracts.TargetKind.ACTION,
                        Optional.of(definitionTarget),
                        Optional.empty(),
                        Optional.of(action)));
    }

    @Test
    void scheduleOccurrenceProjectionPreviewAndQueriesPreserveFrozenIdentity() {
        ScheduleContracts.Definition definition = scheduleDefinition();
        ScheduleContracts.OccurrenceIdentity identity =
                new ScheduleContracts.OccurrenceIdentity("occurrence", definition.id(), definition.revision());
        ScheduleContracts.OccurrenceStatus dispatched = new ScheduleContracts.OccurrenceStatus(
                ScheduleContracts.OccurrenceState.DISPATCHED, Optional.of("job"), Optional.empty());
        ScheduleContracts.Occurrence occurrence =
                new ScheduleContracts.Occurrence(identity, definition, NOW, dispatched, NOW, NOW.plusSeconds(1));
        List<Instant> instants = List.of(
                NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.plusSeconds(3), NOW.plusSeconds(4), NOW.plusSeconds(5));

        assertEquals("job", occurrence.status().jobId().orElseThrow());
        assertEquals(5, new ScheduleContracts.Preview(instants).instants().size());
        assertEquals("schedule", new ScheduleContracts.ManualRun("schedule").scheduleId());
        assertEquals(1, new ScheduleContracts.PreviewRequest("schedule", 1, NOW).scheduleRevision());
        assertEquals(NOW, new ScheduleContracts.DeliveryRequest("schedule", 1, NOW).scheduledFor());
        assertEquals(100, new ScheduleContracts.OccurrenceQuery(Optional.empty(), "", 100).limit());
        assertEquals(
                1,
                new ScheduleContracts.OccurrencePage(List.of(occurrence), "next")
                        .occurrences()
                        .size());
        assertFalse(new ScheduleContracts.OccurrenceCheckpoint(false).completed());
        WorkspaceId workspaceId = WorkspaceId.parse(UUID.randomUUID().toString());
        assertEquals(
                definition,
                new ScheduleContracts.ProjectionChange(
                                workspaceId, definition.id(), Optional.of(definition), definition.revision())
                        .definition()
                        .orElseThrow());
        assertTrue(new ScheduleContracts.ScheduledExecution(definition, "occurrence", Optional.empty())
                .executionSnapshot()
                .isEmpty());

        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleContracts.OccurrenceStatus(
                        ScheduleContracts.OccurrenceState.DISPATCHED, Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleContracts.OccurrenceStatus(
                        ScheduleContracts.OccurrenceState.COMPLETED, Optional.empty(), Optional.of("reason")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleContracts.Preview(
                        List.of(NOW, NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.plusSeconds(3))));
        assertThrows(
                IllegalArgumentException.class, () -> new ScheduleContracts.Preview(List.of(NOW, NOW, NOW, NOW, NOW)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleContracts.ProjectionChange(
                        workspaceId, "other", Optional.of(definition), definition.revision()));
    }

    @Test
    void scheduleManagementBuildsAuthoritativeTimingProfileBudgetAndDefinition() {
        ScheduleManagementContracts.SaveRequest cron = cronScheduleRequest();
        ScheduleManagementContracts.SaveRequest fixed = fixedScheduleRequest();
        ScheduleContracts.Target target = ScheduleContracts.Target.action(actionTarget());

        assertEquals("profile", cron.execution().role().orElseThrow().id());
        assertEquals(4, cron.budget().maximumTurns());
        assertEquals(ScheduleContracts.TimingKind.FIXED_INTERVAL, fixed.timing().kind());
        assertEquals(3, cron.definition(target, 3, NOW).revision());
        assertThrows(
                IllegalArgumentException.class,
                () -> scheduleRequest(
                        ScheduleContracts.TimingKind.FIXED_INTERVAL,
                        ScheduleContracts.TargetKind.ACTION,
                        0,
                        Optional.of(0L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> scheduleRequest(
                        ScheduleContracts.TimingKind.CRON,
                        ScheduleContracts.TargetKind.DEFINITION,
                        0,
                        Optional.empty()));
    }

    private static WorkflowContracts.Definition definition(
            List<WorkflowContracts.Node> nodes, List<WorkflowContracts.Edge> edges) {
        return new WorkflowContracts.Definition("flow", 1, "Flow", nodes, edges, 10, NOW);
    }

    private static WorkflowContracts.Node node(
            String id,
            WorkflowContracts.NodeKind kind,
            Optional<String> instruction,
            WorkflowContracts.NodeConfig config) {
        return new WorkflowContracts.Node(id, kind, id, instruction, config);
    }

    private static WorkflowContracts.Edge edge(String from, String to) {
        return new WorkflowContracts.Edge(from, to, Optional.empty());
    }

    private static WorkflowContracts.NodeConfig toolConfig() {
        return new WorkflowContracts.NodeConfig(
                Optional.of(new WorkflowContracts.ToolConfig("shell", BuiltinContractsFixtures.payload(), "result")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static WorkflowContracts.NodeConfig conditionConfig() {
        return new WorkflowContracts.NodeConfig(
                Optional.empty(),
                Optional.of(new WorkflowContracts.ConditionConfig(
                        "state", WorkflowContracts.ConditionOperator.EQUALS, Optional.of("ready"))),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static WorkflowContracts.NodeConfig transformSetConfig() {
        return transformConfig(WorkflowContracts.TransformOperation.SET, Optional.empty(), Optional.of("value"));
    }

    private static WorkflowContracts.NodeConfig transformCopyConfig() {
        return transformConfig(WorkflowContracts.TransformOperation.COPY, Optional.of("source"), Optional.empty());
    }

    private static WorkflowContracts.NodeConfig transformRemoveConfig() {
        return transformConfig(WorkflowContracts.TransformOperation.REMOVE, Optional.empty(), Optional.empty());
    }

    private static WorkflowContracts.NodeConfig transformConfig(
            WorkflowContracts.TransformOperation operation, Optional<String> source, Optional<String> value) {
        return new WorkflowContracts.NodeConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new WorkflowContracts.TransformConfig(operation, "target", source, value)),
                Optional.empty(),
                Optional.empty());
    }

    private static WorkflowContracts.NodeConfig inputConfig() {
        return new WorkflowContracts.NodeConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new WorkflowContracts.UserInputConfig(
                        "确认？", BuiltinContractsFixtures.payload(), "answer", Duration.ofMinutes(5))),
                Optional.empty());
    }

    private static WorkflowContracts.NodeConfig outputConfig() {
        return new WorkflowContracts.NodeConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new WorkflowContracts.OutputConfig("answer")));
    }

    private static WorkflowManagementContracts.NodeRow row(
            String id, WorkflowContracts.NodeKind kind, String instruction) {
        return new WorkflowManagementContracts.NodeRow(
                id, kind, id, instruction, "", "", "", "", "", "", "", "", "", "", "", 0, "");
    }

    private static OrchestrationContracts.ExecutionBudget budget() {
        return new OrchestrationContracts.ExecutionBudget(5, 1_000, 500, 10);
    }

    private static ScheduleContracts.Definition scheduleDefinition() {
        ScheduleActionContracts.Target action = actionTarget();
        return new ScheduleContracts.Definition(
                "schedule",
                1,
                "Daily",
                true,
                ScheduleContracts.Timing.fixed(Duration.ofMinutes(5), NOW),
                ScheduleContracts.Target.action(action),
                ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                NOW);
    }

    private static ScheduleActionContracts.Target actionTarget() {
        return new ScheduleActionContracts.Target(
                "javaclaw.site",
                "refresh",
                List.of(),
                List.of(),
                BuiltinContractsFixtures.payload().sha256(),
                0);
    }

    private static ScheduleManagementContracts.SaveRequest scheduleRequest(
            ScheduleContracts.TimingKind timing,
            ScheduleContracts.TargetKind target,
            long targetRevision,
            Optional<Long> interval) {
        return new ScheduleManagementContracts.SaveRequest(
                "schedule",
                "Name",
                true,
                timing,
                target,
                "extension",
                "target",
                targetRevision,
                target == ScheduleContracts.TargetKind.ACTION
                        ? BuiltinContractsFixtures.payload().sha256()
                        : "",
                Optional.empty(),
                Optional.empty(),
                interval,
                Optional.of(NOW),
                AutomationV6Fixtures.selection(new AgentRoleRef("profile", 1)),
                "Title",
                "Instruction",
                1,
                1,
                1,
                1,
                List.of());
    }

    private static ScheduleManagementContracts.SaveRequest cronScheduleRequest() {
        return new ScheduleManagementContracts.SaveRequest(
                "schedule",
                "Daily",
                true,
                ScheduleContracts.TimingKind.CRON,
                ScheduleContracts.TargetKind.ACTION,
                "javaclaw.site",
                "refresh",
                0,
                actionTarget().schemaHash(),
                Optional.of("0 0 9 * * ?"),
                Optional.of("UTC"),
                Optional.empty(),
                Optional.empty(),
                AutomationV6Fixtures.selection(new AgentRoleRef("profile", 2)),
                "Daily",
                "Refresh",
                4,
                1_000,
                500,
                10,
                List.of());
    }

    private static ScheduleManagementContracts.SaveRequest fixedScheduleRequest() {
        return new ScheduleManagementContracts.SaveRequest(
                "schedule-2",
                "Interval",
                false,
                ScheduleContracts.TimingKind.FIXED_INTERVAL,
                ScheduleContracts.TargetKind.DEFINITION,
                "javaclaw.plan",
                "plan",
                1,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(15L),
                Optional.of(NOW),
                AutomationV6Fixtures.selection(new AgentRoleRef("profile", 2)),
                "Interval",
                "Run",
                4,
                1_000,
                500,
                10,
                List.of());
    }
}
