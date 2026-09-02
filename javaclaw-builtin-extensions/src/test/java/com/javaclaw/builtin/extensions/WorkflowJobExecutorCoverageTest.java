package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.ScheduledCommandPort;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowJobExecutorCoverageTest {
    private static final AgentProfileRef PROFILE = new AgentProfileRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(20, 10_000, 10_000, 20);

    @Test
    void plannerStopsAtEndAndRejectsWaitingOrVisitLimitViolations() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        WorkflowJobExecutor executor = executor(support, new RecordingSteps());
        WorkflowContracts.Definition simple = simpleWorkflow();

        assertTrue(executor.plan(job(support, simple, checkpoint("end", Map.of(), Map.of()), Optional.empty()))
                .isEmpty());
        WorkflowContracts.Checkpoint waiting = new WorkflowContracts.Checkpoint(
                "start", Map.of(), Map.of(), Optional.of("request"), Optional.of(TurnId.random()), List.of());
        assertThrows(
                IllegalArgumentException.class, () -> executor.plan(job(support, simple, waiting, Optional.empty())));
        WorkflowContracts.Definition oneVisit = new WorkflowContracts.Definition(
                simple.id(), simple.revision(), simple.name(), simple.nodes(), simple.edges(), 1, NOW);
        assertThrows(
                IllegalStateException.class,
                () -> executor.plan(
                        job(support, oneVisit, checkpoint("start", Map.of(), Map.of("end", 1)), Optional.empty())));
    }

    @Test
    void startTurnAndToolNodesAdvanceExactlyOneUnitAndConsumeFrozenBudget() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        RecordingSteps steps = new RecordingSteps();
        WorkflowJobExecutor executor = executor(support, steps);
        WorkflowContracts.Definition definition = startTurnToolWorkflow();

        var start =
                execute(executor, job(support, definition, checkpoint("start", Map.of(), Map.of()), Optional.empty()));
        assertEquals("turn", checkpoint(support, start).currentNodeId());
        assertEquals(1, checkpoint(support, start).visits().get("start"));

        var turn =
                execute(executor, job(support, definition, checkpoint("turn", Map.of(), Map.of()), Optional.empty()));
        assertEquals("tool", checkpoint(support, turn).currentNodeId());
        assertEquals(1, shared(support, turn).consumption().turns());
        assertEquals(
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                support.turns.commands().getFirst().executionIntent());

        var tool =
                execute(executor, job(support, definition, checkpoint("tool", Map.of(), Map.of()), Optional.empty()));
        assertEquals("end", checkpoint(support, tool).currentNodeId());
        assertEquals("{\"passed\":true}", checkpoint(support, tool).variables().get("toolResult"));
        assertEquals(1, shared(support, tool).consumption().toolCalls());
        assertEquals(Optional.of("effect-1"), tool.effectReceiptKey());
        assertEquals("verify", steps.tools.getFirst().toolName());
    }

    @Test
    void turnAndToolFailuresStopStateMachineBeforeCheckpointAdvances() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        RecordingSteps steps = new RecordingSteps();
        WorkflowJobExecutor executor = executor(support, steps);
        WorkflowContracts.Definition definition = startTurnToolWorkflow();
        support.turns.returnStatuses(TurnStatus.FAILED);

        assertThrows(
                IllegalStateException.class,
                () -> execute(
                        executor, job(support, definition, checkpoint("turn", Map.of(), Map.of()), Optional.empty())));
        steps.toolSuccessful = false;
        assertThrows(
                IllegalStateException.class,
                () -> execute(
                        executor, job(support, definition, checkpoint("tool", Map.of(), Map.of()), Optional.empty())));
    }

    @Test
    void everyConditionOperatorSelectsOnlyItsMatchingBranch() throws Exception {
        assertCondition(WorkflowContracts.ConditionOperator.EQUALS, Optional.of("yes"), Map.of("state", "yes"), true);
        assertCondition(
                WorkflowContracts.ConditionOperator.NOT_EQUALS, Optional.of("yes"), Map.of("state", "no"), true);
        assertCondition(WorkflowContracts.ConditionOperator.EXISTS, Optional.empty(), Map.of("state", "yes"), true);
        assertCondition(WorkflowContracts.ConditionOperator.NOT_EXISTS, Optional.empty(), Map.of(), true);
        assertCondition(WorkflowContracts.ConditionOperator.EQUALS, Optional.of("yes"), Map.of("state", "no"), false);
    }

    @Test
    void transformNodesSetCopyAndRemoveOnlyDeclaredVariables() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        WorkflowJobExecutor executor = executor(support, new RecordingSteps());

        var set = execute(
                executor,
                job(
                        support,
                        transformWorkflow(WorkflowContracts.TransformOperation.SET),
                        checkpoint("transform", Map.of(), Map.of()),
                        Optional.empty()));
        assertEquals("fixed", checkpoint(support, set).variables().get("target"));

        var copy = execute(
                executor,
                job(
                        support,
                        transformWorkflow(WorkflowContracts.TransformOperation.COPY),
                        checkpoint("transform", Map.of("source", "copied"), Map.of()),
                        Optional.empty()));
        assertEquals("copied", checkpoint(support, copy).variables().get("target"));

        var remove = execute(
                executor,
                job(
                        support,
                        transformWorkflow(WorkflowContracts.TransformOperation.REMOVE),
                        checkpoint("transform", Map.of("target", "remove"), Map.of()),
                        Optional.empty()));
        assertFalse(checkpoint(support, remove).variables().containsKey("target"));
        assertThrows(
                IllegalStateException.class,
                () -> execute(
                        executor,
                        job(
                                support,
                                transformWorkflow(WorkflowContracts.TransformOperation.COPY),
                                checkpoint("transform", Map.of(), Map.of()),
                                Optional.empty())));
    }

    @Test
    void inputWaitAndOutputUseAuthoritativeTurnRequestAndExistingVariable() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        RecordingSteps steps = new RecordingSteps();
        WorkflowJobExecutor executor = executor(support, steps);
        WorkflowContracts.Definition input = inputWorkflow();

        var waiting = execute(
                executor, job(support, input, checkpoint("input", Map.of(), Map.of()), Optional.of(ThreadId.random())));
        WorkflowContracts.Checkpoint waitingCheckpoint = checkpoint(support, waiting);
        assertEquals(ExecutionState.WAITING_INPUT, waiting.nextState());
        assertEquals("request-1", waitingCheckpoint.pendingInputRequestId().orElseThrow());
        assertEquals(steps.inputResult.turnId(), waiting.turnId().orElseThrow());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), steps.inputs.getFirst().expiresAt());

        var output = execute(
                executor,
                job(
                        support,
                        outputWorkflow(),
                        checkpoint("output", Map.of("answer", "yes"), Map.of()),
                        Optional.empty()));
        assertEquals(List.of("yes"), checkpoint(support, output).outputs());
        assertThrows(
                IllegalStateException.class,
                () -> execute(
                        executor,
                        job(support, outputWorkflow(), checkpoint("output", Map.of(), Map.of()), Optional.empty())));
    }

    @Test
    void executorRejectsFrozenIdentityUnknownCheckpointAndStaleIntent() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        WorkflowJobExecutor executor = executor(support, new RecordingSteps());
        WorkflowContracts.Definition definition = simpleWorkflow();
        ExtensionJob differentIdentity = rawJob(
                support,
                definition,
                checkpoint("start", Map.of(), Map.of()),
                Optional.empty(),
                "different",
                definition.revision());
        assertThrows(IllegalArgumentException.class, () -> executor.plan(differentIdentity));
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.plan(
                        job(support, definition, checkpoint("missing", Map.of(), Map.of()), Optional.empty())));

        ExtensionJob plannedJob = job(support, definition, checkpoint("start", Map.of(), Map.of()), Optional.empty());
        ExtensionJobWorkUnit work = executor.plan(plannedJob).orElseThrow();
        ExtensionJob changedJob =
                job(support, definition, checkpoint("start", Map.of(), Map.of("start", 1)), Optional.empty());
        ExtensionJob active = active(changedJob);
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(
                        new ExtensionJobExecution(active, unit(active, work)), new CancellationSource()));
    }

    private static void assertCondition(
            WorkflowContracts.ConditionOperator operator,
            Optional<String> expected,
            Map<String, String> variables,
            boolean expectedBranch)
            throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        WorkflowJobExecutor executor = executor(support, new RecordingSteps());
        WorkflowContracts.Definition definition = conditionWorkflow(operator, expected);
        var result = execute(
                executor, job(support, definition, checkpoint("condition", variables, Map.of()), Optional.empty()));
        assertEquals(
                expectedBranch ? "true-end" : "false-end",
                checkpoint(support, result).currentNodeId());
    }

    private static WorkflowJobExecutor executor(BuiltinExtensionTestSupport support, AutomationStepPort steps) {
        return new WorkflowJobExecutor(new ExtensionJobRuntimeContext(
                support.clock,
                support.payloads,
                support.turns,
                support.store,
                invocation -> {
                    throw new IllegalStateException("isolated service is not configured");
                },
                support.embeddings,
                steps,
                ScheduledCommandPort.unavailable(),
                com.javaclaw.extension.spi.ScheduleLifecyclePort.unavailable()));
    }

    private static com.javaclaw.extension.spi.ExtensionJobStepResult execute(
            WorkflowJobExecutor executor, ExtensionJob job) throws Exception {
        ExtensionJobWorkUnit work = executor.plan(job).orElseThrow();
        ExtensionJob active = active(job);
        return executor.execute(new ExtensionJobExecution(active, unit(active, work)), new CancellationSource());
    }

    private static ExtensionJob job(
            BuiltinExtensionTestSupport support,
            WorkflowContracts.Definition definition,
            WorkflowContracts.Checkpoint checkpoint,
            Optional<ThreadId> parentThreadId) {
        return rawJob(support, definition, checkpoint, parentThreadId, definition.id(), definition.revision());
    }

    private static ExtensionJob rawJob(
            BuiltinExtensionTestSupport support,
            WorkflowContracts.Definition definition,
            WorkflowContracts.Checkpoint checkpoint,
            Optional<ThreadId> parentThreadId,
            String definitionId,
            long definitionRevision) {
        var frozen = new OrchestrationContracts.FrozenExecution(
                support.executionSnapshot(PROFILE), parentThreadId, support.payloads.encode(definition), BUDGET);
        var shared = new OrchestrationContracts.ExecutionCheckpoint(
                support.payloads.encode(checkpoint), OrchestrationContracts.ExecutionConsumption.zero());
        return new ExtensionJob(
                "workflow-job",
                new ExtensionId(BuiltinExtensionIds.WORKFLOW),
                support.workspaceId,
                AutomationExecutionResource.JOB_TYPE,
                definitionId,
                definitionRevision,
                support.payloads.encode(frozen),
                ExecutionState.RUNNING,
                1,
                support.payloads.encode(shared),
                1,
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW);
    }

    private static ExtensionJob active(ExtensionJob job) {
        return new ExtensionJob(
                job.id(),
                job.extensionId(),
                job.workspaceId(),
                job.jobType(),
                job.definitionId(),
                job.definitionRevision(),
                job.frozenInput(),
                ExecutionState.RUNNING,
                2,
                job.checkpoint(),
                2,
                Optional.of(1L),
                Optional.empty(),
                job.createdAt(),
                job.updatedAt());
    }

    private static ExtensionJobUnit unit(ExtensionJob active, ExtensionJobWorkUnit work) {
        return new ExtensionJobUnit(
                active.id(),
                1,
                work.unitId(),
                work.intent(),
                ExtensionJobUnitState.INTENT_RECORDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW,
                Optional.empty());
    }

    private static OrchestrationContracts.ExecutionCheckpoint shared(
            BuiltinExtensionTestSupport support, com.javaclaw.extension.spi.ExtensionJobStepResult result) {
        return support.payloads.decode(result.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
    }

    private static WorkflowContracts.Checkpoint checkpoint(
            BuiltinExtensionTestSupport support, com.javaclaw.extension.spi.ExtensionJobStepResult result) {
        return support.payloads.decode(shared(support, result).domain(), WorkflowContracts.Checkpoint.class);
    }

    private static WorkflowContracts.Checkpoint checkpoint(
            String node, Map<String, String> variables, Map<String, Integer> visits) {
        return new WorkflowContracts.Checkpoint(node, variables, visits, Optional.empty(), Optional.empty(), List.of());
    }

    private static WorkflowContracts.Definition simpleWorkflow() {
        return definition(
                List.of(
                        emptyNode("start", WorkflowContracts.NodeKind.START),
                        emptyNode("end", WorkflowContracts.NodeKind.END)),
                List.of(edge("start", "end")));
    }

    private static WorkflowContracts.Definition startTurnToolWorkflow() {
        WorkflowContracts.Node turn = new WorkflowContracts.Node(
                "turn",
                WorkflowContracts.NodeKind.TURN,
                "Turn",
                Optional.of("执行"),
                WorkflowContracts.NodeConfig.empty());
        WorkflowContracts.Node tool = new WorkflowContracts.Node(
                "tool",
                WorkflowContracts.NodeKind.TOOL,
                "Tool",
                Optional.empty(),
                config(new WorkflowContracts.ToolConfig("verify", new CanonicalPayload("{}"), "toolResult")));
        return definition(
                List.of(
                        emptyNode("start", WorkflowContracts.NodeKind.START),
                        turn,
                        tool,
                        emptyNode("end", WorkflowContracts.NodeKind.END)),
                List.of(edge("start", "turn"), edge("turn", "tool"), edge("tool", "end")));
    }

    private static WorkflowContracts.Definition conditionWorkflow(
            WorkflowContracts.ConditionOperator operator, Optional<String> expected) {
        WorkflowContracts.Node condition = new WorkflowContracts.Node(
                "condition",
                WorkflowContracts.NodeKind.CONDITION,
                "Condition",
                Optional.empty(),
                config(new WorkflowContracts.ConditionConfig("state", operator, expected)));
        return definition(
                List.of(
                        emptyNode("start", WorkflowContracts.NodeKind.START),
                        condition,
                        emptyNode("true-end", WorkflowContracts.NodeKind.END),
                        emptyNode("false-end", WorkflowContracts.NodeKind.END)),
                List.of(
                        edge("start", "condition"),
                        new WorkflowContracts.Edge("condition", "true-end", Optional.of("TRUE")),
                        new WorkflowContracts.Edge("condition", "false-end", Optional.of("FALSE"))));
    }

    private static WorkflowContracts.Definition transformWorkflow(WorkflowContracts.TransformOperation operation) {
        Optional<String> source =
                operation == WorkflowContracts.TransformOperation.COPY ? Optional.of("source") : Optional.empty();
        Optional<String> value =
                operation == WorkflowContracts.TransformOperation.SET ? Optional.of("fixed") : Optional.empty();
        WorkflowContracts.Node transform = new WorkflowContracts.Node(
                "transform",
                WorkflowContracts.NodeKind.TRANSFORM,
                "Transform",
                Optional.empty(),
                config(new WorkflowContracts.TransformConfig(operation, "target", source, value)));
        return definition(
                List.of(
                        emptyNode("start", WorkflowContracts.NodeKind.START),
                        transform,
                        emptyNode("end", WorkflowContracts.NodeKind.END)),
                List.of(edge("start", "transform"), edge("transform", "end")));
    }

    private static WorkflowContracts.Definition inputWorkflow() {
        WorkflowContracts.Node input = new WorkflowContracts.Node(
                "input",
                WorkflowContracts.NodeKind.USER_INPUT,
                "Input",
                Optional.empty(),
                config(new WorkflowContracts.UserInputConfig(
                        "确认？", new CanonicalPayload("{\"type\":\"object\"}"), "answer", Duration.ofMinutes(5))));
        return definition(
                List.of(
                        emptyNode("start", WorkflowContracts.NodeKind.START),
                        input,
                        emptyNode("end", WorkflowContracts.NodeKind.END)),
                List.of(edge("start", "input"), edge("input", "end")));
    }

    private static WorkflowContracts.Definition outputWorkflow() {
        WorkflowContracts.Node output = new WorkflowContracts.Node(
                "output",
                WorkflowContracts.NodeKind.OUTPUT,
                "Output",
                Optional.empty(),
                config(new WorkflowContracts.OutputConfig("answer")));
        return definition(
                List.of(
                        emptyNode("start", WorkflowContracts.NodeKind.START),
                        output,
                        emptyNode("end", WorkflowContracts.NodeKind.END)),
                List.of(edge("start", "output"), edge("output", "end")));
    }

    private static WorkflowContracts.Definition definition(
            List<WorkflowContracts.Node> nodes, List<WorkflowContracts.Edge> edges) {
        return new WorkflowContracts.Definition("workflow", 1, "Workflow", nodes, edges, 100, NOW);
    }

    private static WorkflowContracts.Node emptyNode(String id, WorkflowContracts.NodeKind kind) {
        return new WorkflowContracts.Node(id, kind, id, Optional.empty(), WorkflowContracts.NodeConfig.empty());
    }

    private static WorkflowContracts.Edge edge(String from, String to) {
        return new WorkflowContracts.Edge(from, to, Optional.empty());
    }

    private static WorkflowContracts.NodeConfig config(Object value) {
        return new WorkflowContracts.NodeConfig(
                value instanceof WorkflowContracts.ToolConfig tool ? Optional.of(tool) : Optional.empty(),
                value instanceof WorkflowContracts.ConditionConfig condition
                        ? Optional.of(condition)
                        : Optional.empty(),
                value instanceof WorkflowContracts.TransformConfig transform
                        ? Optional.of(transform)
                        : Optional.empty(),
                value instanceof WorkflowContracts.UserInputConfig input ? Optional.of(input) : Optional.empty(),
                value instanceof WorkflowContracts.OutputConfig output ? Optional.of(output) : Optional.empty());
    }

    private static final class RecordingSteps implements AutomationStepPort {
        private final List<ToolCommand> tools = new ArrayList<>();
        private final List<InputCommand> inputs = new ArrayList<>();
        private final InputResult inputResult = new InputResult(ThreadId.random(), TurnId.random(), "request-1");
        private boolean toolSuccessful = true;

        @Override
        public ToolResult executeTool(ToolCommand command, CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            tools.add(command);
            return new ToolResult(
                    ThreadId.random(),
                    TurnId.random(),
                    toolSuccessful,
                    new CanonicalPayload("{\"passed\":true}"),
                    Optional.of("effect-1"));
        }

        @Override
        public InputResult openInput(InputCommand command, CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            inputs.add(command);
            return inputResult;
        }
    }
}
