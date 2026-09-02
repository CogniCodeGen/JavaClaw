package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

/** Workflow Definition 与平台解释的可恢复安全 Graph Execution。 */
final class WorkflowExtension implements ExtensionBundle {
    private final ManagedDocumentResource<WorkflowContracts.Definition> documents = new ManagedDocumentResource<>(
            BuiltinExtensionIds.WORKFLOW,
            "工作流",
            WorkflowContracts.Definition.class,
            Set.of(ContributionKind.ORCHESTRATOR, ContributionKind.SCHEDULABLE_ACTION));
    private final WorkflowManagement management = new WorkflowManagement(documents);
    private final AutomationExecutionResource<WorkflowContracts.Definition> executions =
            new AutomationExecutionResource<>(
                    documents,
                    "工作流",
                    WorkflowJobExecutor::new,
                    this::initialCheckpoint,
                    ignored -> {},
                    this::executionViewFields);

    @Override
    public ExtensionDescriptor descriptor() {
        return documents.descriptor();
    }

    @Override
    public List<ExtensionContribution> start(ExtensionContext context) {
        List<ExtensionContribution> contributions = new ArrayList<>(documents.startWithManagedWrites(context));
        contributions.addAll(management.contributions());
        contributions.addAll(executions.contributions(
                List.of(
                        new ExtensionContributions.Command(
                                "execution.input.continue", Set.of("execution/input/continue"), this::continueInput),
                        new ExtensionContributions.Query(
                                "graph.query", Set.of("graph/node/view.list", "graph/edge/view.list"), this::graphView),
                        new ExtensionContributions.View("graph.view", graphViewSchema())),
                List.of(continueInputAction())));
        return List.copyOf(contributions);
    }

    @Override
    public List<ExtensionSchema> schemas() {
        List<ExtensionSchema> schemas = new ArrayList<>(documents.schemas());
        schemas.addAll(executions.schemas());
        return List.copyOf(schemas);
    }

    @Override
    public List<ExtensionJobRegistration> jobExecutors(ExtensionJobRuntimeContext context) {
        return executions.jobExecutors(context);
    }

    @Override
    public void close() {
        documents.close();
    }

    private CanonicalPayload initialCheckpoint(WorkflowContracts.Definition definition) {
        String start = definition.nodes().stream()
                .filter(node -> node.kind() == WorkflowContracts.NodeKind.START)
                .findFirst()
                .orElseThrow()
                .id();
        return documents
                .payloads()
                .encode(new WorkflowContracts.Checkpoint(
                        start, Map.of(), Map.of(), Optional.empty(), Optional.empty(), List.of()));
    }

    private Map<String, Object> executionViewFields(ExtensionJob job) {
        if (job.state() != ExecutionState.WAITING_INPUT) {
            return Map.of();
        }
        OrchestrationContracts.ExecutionCheckpoint shared =
                documents.payloads().decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
        WorkflowContracts.Checkpoint checkpoint =
                documents.payloads().decode(shared.domain(), WorkflowContracts.Checkpoint.class);
        String requestId = checkpoint
                .pendingInputRequestId()
                .orElseThrow(() -> new IllegalStateException("WAITING_INPUT Workflow has no pending request"));
        return Map.of("pendingInputRequestId", requestId);
    }

    private ViewAction continueInputAction() {
        return new ViewAction(
                "继续已决议输入",
                "execution/input/continue",
                Map.of(),
                Map.of("jobId", "id", "requestId", "pendingInputRequestId"),
                new ExpectedRevisionBinding.RowField("revision"),
                false);
    }

    private ExtensionResponse continueInput(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        WorkflowContracts.ContinueInput command =
                documents.payloads().decode(request.payload(), WorkflowContracts.ContinueInput.class);
        ExtensionJob job = requireWaitingJob(command.jobId(), request, context);
        OrchestrationContracts.ExecutionCheckpoint shared =
                documents.payloads().decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
        WorkflowContracts.Checkpoint checkpoint =
                documents.payloads().decode(shared.domain(), WorkflowContracts.Checkpoint.class);
        requireInputIdentity(checkpoint, command.requestId());
        InputRequestRecord input = context.inputs()
                .find(command.requestId())
                .orElseThrow(() -> new IllegalArgumentException("Workflow InputRequest does not exist"));
        requireInputIdentity(checkpoint, input);
        requireResolved(input, request);
        WorkflowContracts.Definition definition = frozenDefinition(job);
        WorkflowContracts.Checkpoint resumed = resumeCheckpoint(definition, checkpoint, input);
        context.inputs()
                .completeResolved(input.request().id(), documents.extensionId().value());
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("input continuation requires idempotency key"));
        ExtensionJob updated = context.jobs()
                .continueWaiting(
                        job.id(),
                        ExecutionState.WAITING_INPUT,
                        documents
                                .payloads()
                                .encode(new OrchestrationContracts.ExecutionCheckpoint(
                                        documents.payloads().encode(resumed), shared.consumption())),
                        new ExtensionJobMutation(key, request.expectedRevision()));
        return new ExtensionResponse(
                documents.payloads().encode(ExtensionExecutionReceipt.from(updated)), updated.revision());
    }

    private ExtensionResponse graphView(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        GraphSelection selection = requireDefinitionArgument(query);
        WorkflowContracts.Definition definition =
                documents.requireDocument(selection.definitionId(), selection.revision(), context);
        List<CanonicalPayload> rows =
                switch (query.dataSourceId()) {
                    case "graphNodes" ->
                        definition.nodes().stream()
                                .map(documents.payloads()::encode)
                                .toList();
                    case "graphEdges" ->
                        definition.edges().stream()
                                .map(documents.payloads()::encode)
                                .toList();
                    default -> throw new IllegalArgumentException("unknown Workflow graph data source");
                };
        var result = new ViewQueryResult(
                query.dataSourceId(), rows, documents.payloads().encode(Map.of()), "", false, definition.revision());
        return new ExtensionResponse(documents.payloads().encode(result), definition.revision());
    }

    private ExtensionJob requireWaitingJob(String jobId, ExtensionRequest request, ExtensionExecutionContext context) {
        ExtensionJob job = context.jobs()
                .find(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow Job does not exist"));
        boolean owned = job.extensionId().equals(documents.extensionId())
                && job.workspaceId().equals(request.workspaceId())
                && job.jobType().equals(AutomationExecutionResource.JOB_TYPE);
        if (!owned || job.state() != ExecutionState.WAITING_INPUT) {
            throw new IllegalArgumentException("Workflow Job is not waiting for input");
        }
        return job;
    }

    private void requireResolved(InputRequestRecord input, ExtensionRequest request) {
        requireResolved(input, documents.extensionId().value(), request.turnId());
    }

    static void requireResolved(
            InputRequestRecord input, String producerId, Optional<com.javaclaw.api.TurnId> requestTurnId) {
        boolean matches = input.state() == InputRequestState.RESOLVED
                && input.request().producerId().equals(producerId)
                && requestTurnId.map(input.request().turnId()::equals).orElse(true);
        if (!matches) {
            throw new IllegalArgumentException("Workflow input is not resolved by its owner");
        }
    }

    private WorkflowContracts.Checkpoint resumeCheckpoint(
            WorkflowContracts.Definition definition,
            WorkflowContracts.Checkpoint checkpoint,
            InputRequestRecord input) {
        WorkflowContracts.Node node = node(definition, checkpoint.currentNodeId());
        WorkflowContracts.UserInputConfig config = node.config().input().orElseThrow();
        Map<String, String> variables = new HashMap<>(checkpoint.variables());
        variables.put(config.responseField(), input.response().orElseThrow().json());
        return new WorkflowContracts.Checkpoint(
                next(definition, node.id(), Optional.empty()),
                variables,
                checkpoint.visits(),
                Optional.empty(),
                Optional.empty(),
                checkpoint.outputs());
    }

    private WorkflowContracts.Definition frozenDefinition(ExtensionJob job) {
        OrchestrationContracts.FrozenExecution frozen =
                documents.payloads().decode(job.frozenInput(), OrchestrationContracts.FrozenExecution.class);
        return documents.payloads().decode(frozen.definition(), WorkflowContracts.Definition.class);
    }

    static void requireInputIdentity(WorkflowContracts.Checkpoint checkpoint, String requestId) {
        if (checkpoint.pendingInputRequestId().filter(requestId::equals).isEmpty()) {
            throw new IllegalArgumentException("InputRequest does not match Workflow checkpoint");
        }
    }

    static void requireInputIdentity(WorkflowContracts.Checkpoint checkpoint, InputRequestRecord authoritative) {
        boolean requestMatches = checkpoint
                .pendingInputRequestId()
                .filter(authoritative.request().id()::equals)
                .isPresent();
        boolean turnMatches = checkpoint
                .pendingInputTurnId()
                .filter(authoritative.request().turnId()::equals)
                .isPresent();
        if (!requestMatches || !turnMatches) {
            throw new IllegalArgumentException("InputRequest authority does not match Workflow checkpoint");
        }
    }

    private static GraphSelection requireDefinitionArgument(ViewQueryRequest query) {
        String id = query.arguments().get("definitionId");
        String revision = query.arguments().get("definitionRevision");
        if (id == null || id.isBlank() || revision == null) {
            throw new IllegalArgumentException("Workflow graph view requires definitionId and revision");
        }
        try {
            return new GraphSelection(id, Long.parseLong(revision));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Workflow graph revision is invalid", failure);
        }
    }

    private static WorkflowContracts.Node node(WorkflowContracts.Definition definition, String id) {
        return definition.nodes().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Workflow checkpoint references unknown node"));
    }

    private static String next(WorkflowContracts.Definition definition, String nodeId, Optional<String> branch) {
        return definition.edges().stream()
                .filter(edge -> edge.from().equals(nodeId) && edge.branch().equals(branch))
                .map(WorkflowContracts.Edge::to)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Workflow node has no matching outgoing edge"));
    }

    private ViewSchema graphViewSchema() {
        List<ViewDataSource> sources = List.of(
                new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                new ViewDataSource("graphNodes", "graph/node/view.list", Map.of(), graphSelectionBindings(), 200),
                new ViewDataSource("graphEdges", "graph/edge/view.list", Map.of(), graphSelectionBindings(), 200));
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                documents.extensionId().value() + ".graph",
                "工作流图",
                sources,
                List.of(
                        new ViewSchema.Table(
                                "workflowDefinitions",
                                "工作流定义",
                                "documents",
                                "id",
                                List.of(
                                        new ViewSchema.Column("name", "名称", Optional.of(280)),
                                        new ViewSchema.Column("revision", "版本", Optional.of(90))),
                                com.javaclaw.extension.spi.ViewSelectionMode.SINGLE,
                                List.of()),
                        new ViewSchema.Graph(
                                "workflowGraph",
                                "安全 Graph",
                                "graphNodes",
                                "graphEdges",
                                "id",
                                "name",
                                "kind",
                                "from",
                                "to")));
    }

    private static List<ViewArgumentBinding> graphSelectionBindings() {
        return List.of(
                new ViewArgumentBinding("definitionId", "documents", "id"),
                new ViewArgumentBinding("definitionRevision", "documents", "revision"));
    }

    private record GraphSelection(String definitionId, long revision) {}
}
