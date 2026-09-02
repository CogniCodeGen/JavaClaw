package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobInputWait;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.OrchestratedTurnCommand;
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;

/** 一次只推进一个声明节点的 Workflow 安全 Graph 解释器。 */
final class WorkflowJobExecutor implements ExtensionJobExecutor {
    private final ExtensionJobRuntimeContext context;

    WorkflowJobExecutor(ExtensionJobRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
        FrozenWorkflow frozen = frozen(job);
        WorkflowContracts.Checkpoint checkpoint = checkpoint(job);
        WorkflowContracts.Node node = node(frozen.definition(), checkpoint.currentNodeId());
        if (node.kind() == WorkflowContracts.NodeKind.END) {
            return Optional.empty();
        }
        if (checkpoint.pendingInputRequestId().isPresent()) {
            throw new IllegalArgumentException("waiting Workflow cannot plan another node");
        }
        int visit = Math.addExact(checkpoint.visits().getOrDefault(node.id(), 0), 1);
        int total = Math.addExact(
                checkpoint.visits().values().stream()
                        .mapToInt(Integer::intValue)
                        .sum(),
                1);
        if (total > frozen.definition().maxVisits()) {
            throw new IllegalStateException("Workflow exceeded maxVisits");
        }
        NodeIntent intent = new NodeIntent(node.id(), node.kind(), visit);
        return Optional.of(new ExtensionJobWorkUnit(
                "node-" + node.id() + "-visit-" + visit, context.payloads().encode(intent)));
    }

    @Override
    public ExtensionJobStepResult execute(ExtensionJobExecution execution, CancellationToken cancellation)
            throws Exception {
        FrozenWorkflow frozen = frozen(execution.job());
        NodeIntent intent = context.payloads().decode(execution.unit().intent(), NodeIntent.class);
        WorkflowContracts.Checkpoint checkpoint = checkpoint(execution.job());
        WorkflowContracts.Node node = node(frozen.definition(), intent.nodeId());
        requireIntent(node, checkpoint, intent);
        return switch (node.kind()) {
            case START -> localResult(execution, frozen, visit(checkpoint, node), node, Optional.empty(), "已进入工作流");
            case TURN -> executeTurn(execution, frozen, checkpoint, node, cancellation);
            case TOOL -> executeTool(execution, frozen, checkpoint, node, cancellation);
            case CONDITION -> executeCondition(execution, frozen, checkpoint, node);
            case TRANSFORM -> executeTransform(execution, frozen, checkpoint, node);
            case USER_INPUT -> openInput(execution, frozen, checkpoint, node, cancellation);
            case OUTPUT -> executeOutput(execution, frozen, checkpoint, node);
            case END -> throw new IllegalArgumentException("END node is completed by planner");
        };
    }

    private ExtensionJobStepResult executeTurn(
            ExtensionJobExecution execution,
            FrozenWorkflow frozen,
            WorkflowContracts.Checkpoint current,
            WorkflowContracts.Node node,
            CancellationToken cancellation)
            throws Exception {
        OrchestratedTurnResult turn = context.turns().execute(turnCommand(execution.job(), frozen, node), cancellation);
        if (turn.status() != TurnStatus.COMPLETED) {
            throw new IllegalStateException("Workflow Turn did not complete: " + turn.status());
        }
        OrchestratedTurnSummary summary = context.payloads().decode(turn.output(), OrchestratedTurnSummary.class);
        WorkflowContracts.Checkpoint next = advance(frozen.definition(), visit(current, node), node, Optional.empty());
        var consumption = ExecutionBudgetGuard.consume(
                frozen.execution().budget(), shared(execution.job()).consumption(), summary);
        return result(
                execution,
                next,
                consumption,
                StepOutcome.running(Optional.of(turn.turnId()), Optional.empty(), summary.assistantText()));
    }

    private ExtensionJobStepResult executeTool(
            ExtensionJobExecution execution,
            FrozenWorkflow frozen,
            WorkflowContracts.Checkpoint current,
            WorkflowContracts.Node node,
            CancellationToken cancellation)
            throws Exception {
        WorkflowContracts.ToolConfig config = node.config().tool().orElseThrow();
        AutomationStepPort.ToolResult tool = context.automationSteps()
                .executeTool(
                        new AutomationStepPort.ToolCommand(
                                stepContext(execution.job(), frozen, node), config.toolName(), config.arguments()),
                        cancellation);
        if (!tool.successful()) {
            throw new IllegalStateException("Workflow governed Tool reported failure");
        }
        Map<String, String> variables = new HashMap<>(current.variables());
        variables.put(config.resultField(), tool.output().json());
        WorkflowContracts.Checkpoint next =
                advance(frozen.definition(), withVariables(visit(current, node), variables), node, Optional.empty());
        var consumption = ExecutionBudgetGuard.consumeTool(
                frozen.execution().budget(), shared(execution.job()).consumption());
        return result(
                execution,
                next,
                consumption,
                StepOutcome.running(Optional.of(tool.turnId()), tool.effectReceiptKey(), "工具节点完成"));
    }

    private ExtensionJobStepResult executeCondition(
            ExtensionJobExecution execution,
            FrozenWorkflow frozen,
            WorkflowContracts.Checkpoint current,
            WorkflowContracts.Node node) {
        WorkflowContracts.ConditionConfig config = node.config().condition().orElseThrow();
        boolean selected = condition(current.variables(), config);
        WorkflowContracts.Checkpoint next = advance(
                frozen.definition(),
                visit(current, node),
                node,
                Optional.of(Boolean.toString(selected).toUpperCase()));
        return localResult(
                execution,
                frozen,
                next,
                node,
                Optional.of(Boolean.toString(selected).toUpperCase()),
                "条件已判定");
    }

    private ExtensionJobStepResult executeTransform(
            ExtensionJobExecution execution,
            FrozenWorkflow frozen,
            WorkflowContracts.Checkpoint current,
            WorkflowContracts.Node node) {
        WorkflowContracts.TransformConfig config = node.config().transform().orElseThrow();
        Map<String, String> variables = new HashMap<>(current.variables());
        switch (config.operation()) {
            case SET -> variables.put(config.target(), config.value().orElseThrow());
            case COPY ->
                variables.put(
                        config.target(),
                        requireVariable(variables, config.source().orElseThrow()));
            case REMOVE -> variables.remove(config.target());
        }
        WorkflowContracts.Checkpoint next =
                advance(frozen.definition(), withVariables(visit(current, node), variables), node, Optional.empty());
        return localResult(execution, frozen, next, node, Optional.empty(), "字段变换完成");
    }

    private ExtensionJobStepResult openInput(
            ExtensionJobExecution execution,
            FrozenWorkflow frozen,
            WorkflowContracts.Checkpoint current,
            WorkflowContracts.Node node,
            CancellationToken cancellation)
            throws Exception {
        WorkflowContracts.UserInputConfig config = node.config().input().orElseThrow();
        AutomationStepPort.InputResult opened = context.automationSteps()
                .openInput(
                        new AutomationStepPort.InputCommand(
                                stepContext(execution.job(), frozen, node),
                                config.prompt(),
                                config.responseSchema(),
                                context.clock().instant().plus(config.timeout())),
                        cancellation);
        WorkflowContracts.Checkpoint visited = visit(current, node);
        WorkflowContracts.Checkpoint waiting = new WorkflowContracts.Checkpoint(
                node.id(),
                visited.variables(),
                visited.visits(),
                Optional.of(opened.requestId()),
                Optional.of(opened.turnId()),
                visited.outputs());
        return result(
                execution,
                waiting,
                shared(execution.job()).consumption(),
                StepOutcome.waiting(
                        opened.turnId(), new ExtensionJobInputWait(opened.requestId(), opened.turnId()), "等待用户输入"));
    }

    private ExtensionJobStepResult executeOutput(
            ExtensionJobExecution execution,
            FrozenWorkflow frozen,
            WorkflowContracts.Checkpoint current,
            WorkflowContracts.Node node) {
        String field = node.config().output().orElseThrow().field();
        List<String> outputs = new ArrayList<>(current.outputs());
        outputs.add(requireVariable(current.variables(), field));
        WorkflowContracts.Checkpoint visited = visit(current, node);
        WorkflowContracts.Checkpoint changed = new WorkflowContracts.Checkpoint(
                visited.currentNodeId(),
                visited.variables(),
                visited.visits(),
                Optional.empty(),
                Optional.empty(),
                outputs);
        WorkflowContracts.Checkpoint next = advance(frozen.definition(), changed, node, Optional.empty());
        return localResult(execution, frozen, next, node, Optional.empty(), "输出已记录");
    }

    private ExtensionJobStepResult localResult(
            ExtensionJobExecution execution,
            FrozenWorkflow frozen,
            WorkflowContracts.Checkpoint checkpoint,
            WorkflowContracts.Node node,
            Optional<String> branch,
            String summary) {
        WorkflowContracts.Checkpoint next = checkpoint.currentNodeId().equals(node.id())
                ? advance(frozen.definition(), checkpoint, node, branch)
                : checkpoint;
        return result(
                execution,
                next,
                shared(execution.job()).consumption(),
                StepOutcome.running(Optional.empty(), Optional.empty(), summary));
    }

    private ExtensionJobStepResult result(
            ExtensionJobExecution execution,
            WorkflowContracts.Checkpoint checkpoint,
            OrchestrationContracts.ExecutionConsumption consumption,
            StepOutcome outcome) {
        NodeResult nodeResult =
                new NodeResult(execution.unit().unitId(), checkpoint.currentNodeId(), outcome.summary());
        var shared = new OrchestrationContracts.ExecutionCheckpoint(
                context.payloads().encode(checkpoint), consumption);
        return new ExtensionJobStepResult(
                context.payloads().encode(nodeResult),
                context.payloads().encode(shared),
                outcome.state(),
                outcome.turnId(),
                outcome.receipt(),
                outcome.inputWait());
    }

    private OrchestratedTurnCommand turnCommand(ExtensionJob job, FrozenWorkflow frozen, WorkflowContracts.Node node) {
        return new OrchestratedTurnCommand(
                job.workspaceId(),
                frozen.execution().parentThreadId(),
                intent(frozen),
                frozen.definition().name() + " / " + node.name(),
                frozen.execution().platform(),
                node.instruction().orElseThrow(),
                context.payloads()
                        .encode(new TurnContext(node.id(), checkpoint(job).variables())),
                key(job, node));
    }

    private AutomationStepPort.StepContext stepContext(
            ExtensionJob job, FrozenWorkflow frozen, WorkflowContracts.Node node) {
        return new AutomationStepPort.StepContext(
                job.workspaceId(),
                frozen.execution().parentThreadId(),
                frozen.definition().name() + " / " + node.name(),
                frozen.execution().platform(),
                key(job, node));
    }

    private FrozenWorkflow frozen(ExtensionJob job) {
        OrchestrationContracts.FrozenExecution execution =
                context.payloads().decode(job.frozenInput(), OrchestrationContracts.FrozenExecution.class);
        WorkflowContracts.Definition definition =
                context.payloads().decode(execution.definition(), WorkflowContracts.Definition.class);
        if (!definition.id().equals(job.definitionId()) || definition.revision() != job.definitionRevision()) {
            throw new IllegalArgumentException("Workflow frozen Definition identity differs from Job");
        }
        return new FrozenWorkflow(execution, definition);
    }

    private WorkflowContracts.Checkpoint checkpoint(ExtensionJob job) {
        return context.payloads().decode(shared(job).domain(), WorkflowContracts.Checkpoint.class);
    }

    private OrchestrationContracts.ExecutionCheckpoint shared(ExtensionJob job) {
        return context.payloads().decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
    }

    private static WorkflowContracts.Node node(WorkflowContracts.Definition definition, String id) {
        return definition.nodes().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Workflow checkpoint references unknown node"));
    }

    private static WorkflowContracts.Checkpoint visit(
            WorkflowContracts.Checkpoint checkpoint, WorkflowContracts.Node node) {
        Map<String, Integer> visits = new HashMap<>(checkpoint.visits());
        visits.merge(node.id(), 1, Math::addExact);
        return new WorkflowContracts.Checkpoint(
                checkpoint.currentNodeId(),
                checkpoint.variables(),
                visits,
                checkpoint.pendingInputRequestId(),
                checkpoint.pendingInputTurnId(),
                checkpoint.outputs());
    }

    private static WorkflowContracts.Checkpoint withVariables(
            WorkflowContracts.Checkpoint checkpoint, Map<String, String> variables) {
        return new WorkflowContracts.Checkpoint(
                checkpoint.currentNodeId(),
                variables,
                checkpoint.visits(),
                checkpoint.pendingInputRequestId(),
                checkpoint.pendingInputTurnId(),
                checkpoint.outputs());
    }

    private static WorkflowContracts.Checkpoint advance(
            WorkflowContracts.Definition definition,
            WorkflowContracts.Checkpoint checkpoint,
            WorkflowContracts.Node node,
            Optional<String> branch) {
        String next = definition.edges().stream()
                .filter(edge -> edge.from().equals(node.id()) && edge.branch().equals(branch))
                .map(WorkflowContracts.Edge::to)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Workflow node has no matching outgoing edge"));
        return new WorkflowContracts.Checkpoint(
                next,
                checkpoint.variables(),
                checkpoint.visits(),
                Optional.empty(),
                Optional.empty(),
                checkpoint.outputs());
    }

    private static boolean condition(Map<String, String> variables, WorkflowContracts.ConditionConfig config) {
        return switch (config.operator()) {
            case EQUALS -> config.expected().orElseThrow().equals(variables.get(config.field()));
            case NOT_EQUALS -> !config.expected().orElseThrow().equals(variables.get(config.field()));
            case EXISTS -> variables.containsKey(config.field());
            case NOT_EXISTS -> !variables.containsKey(config.field());
        };
    }

    private static String requireVariable(Map<String, String> variables, String field) {
        String value = variables.get(field);
        if (value == null) {
            throw new IllegalStateException("Workflow variable does not exist: " + field);
        }
        return value;
    }

    private static void requireIntent(
            WorkflowContracts.Node node, WorkflowContracts.Checkpoint checkpoint, NodeIntent intent) {
        int expectedVisit = Math.addExact(checkpoint.visits().getOrDefault(node.id(), 0), 1);
        if (!node.id().equals(checkpoint.currentNodeId())
                || node.kind() != intent.kind()
                || intent.visit() != expectedVisit) {
            throw new IllegalArgumentException("Workflow intent differs from checkpoint");
        }
    }

    private static ThreadExecutionIntent intent(FrozenWorkflow frozen) {
        return frozen.execution().parentThreadId().isPresent()
                ? ThreadExecutionIntent.ISOLATED_WRITE
                : ThreadExecutionIntent.WORKSPACE;
    }

    private static String key(ExtensionJob job, WorkflowContracts.Node node) {
        return "job-" + job.id() + "-node-" + node.id() + "-unit-" + job.nextUnitSequence();
    }

    private record NodeIntent(String nodeId, WorkflowContracts.NodeKind kind, int visit) {}

    private record NodeResult(String unitId, String nextNodeId, String summary) {}

    private record StepOutcome(
            ExecutionState state,
            Optional<com.javaclaw.api.TurnId> turnId,
            Optional<String> receipt,
            Optional<ExtensionJobInputWait> inputWait,
            String summary) {
        private static StepOutcome running(
                Optional<com.javaclaw.api.TurnId> turnId, Optional<String> receipt, String summary) {
            return new StepOutcome(ExecutionState.RUNNING, turnId, receipt, Optional.empty(), summary);
        }

        private static StepOutcome waiting(
                com.javaclaw.api.TurnId turnId, ExtensionJobInputWait inputWait, String summary) {
            return new StepOutcome(
                    ExecutionState.WAITING_INPUT,
                    Optional.of(turnId),
                    Optional.empty(),
                    Optional.of(inputWait),
                    summary);
        }
    }

    private record TurnContext(String nodeId, Map<String, String> variables) {
        private TurnContext {
            variables = Map.copyOf(variables);
        }
    }

    private record FrozenWorkflow(
            OrchestrationContracts.FrozenExecution execution, WorkflowContracts.Definition definition) {}
}
