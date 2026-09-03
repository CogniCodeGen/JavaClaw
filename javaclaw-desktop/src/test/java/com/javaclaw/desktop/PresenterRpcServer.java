package com.javaclaw.desktop;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.ServerNotification;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeResult;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.LocalTransport;
import com.javaclaw.protocol.NegotiatedCapabilities;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.ToolRpcContracts;
import com.javaclaw.protocol.TransportKind;
import com.javaclaw.protocol.WriteCommand;

final class PresenterRpcServer implements LocalTransport, RpcConnection {
    private static final Object CLOSED = new Object();

    private final CanonicalJson json = new CanonicalJson();
    private final SessionSecretChannel secrets = SessionSecretChannel.open();
    private final BlockingQueue<Object> inbound = new ArrayBlockingQueue<>(64);
    private final Workspace workspace = DesktopTestFixtures.workspace();
    private final ConversationThread thread = DesktopTestFixtures.thread(workspace);
    private final AgentProfile profile = DesktopTestFixtures.profile();

    final AtomicInteger workspaceCreates = new AtomicInteger();
    final AtomicInteger threadCreates = new AtomicInteger();
    final AtomicInteger turnStarts = new AtomicInteger();
    final AtomicInteger turnReads = new AtomicInteger();
    final AtomicInteger turnCancels = new AtomicInteger();
    final AtomicInteger inputLists = new AtomicInteger();
    final AtomicInteger inputResolutions = new AtomicInteger();
    final AtomicInteger jobLists = new AtomicInteger();
    final AtomicInteger jobReads = new AtomicInteger();
    final AtomicInteger jobMutations = new AtomicInteger();
    final AtomicInteger approvalResolutions = new AtomicInteger();
    final AtomicInteger extensionQueries = new AtomicInteger();
    final AtomicInteger extensionCommands = new AtomicInteger();
    final AtomicInteger extensionViewLists = new AtomicInteger();
    final List<ViewQueryRequest> viewQueries = new CopyOnWriteArrayList<>();

    volatile TurnStatus completion = TurnStatus.COMPLETED;
    volatile boolean cancelRequested;
    volatile boolean closed;
    volatile long lastExpectedRevision = -1;
    volatile CoreRpcContracts.TurnStartPayload lastTurnStart;
    volatile CanonicalPayload lastInputResponse;
    volatile String lastJobIdempotencyKey = "";
    volatile long lastJobExpectedRevision = -1;
    volatile InputRequestRecord input = DesktopTestFixtures.input();
    volatile ExtensionExecutionReceipt job = job(ExecutionState.RUNNING, 3);
    volatile CanonicalPayload viewSchema = new CanonicalPayload("{\"schemaVersion\":2}");
    volatile boolean profileBound = true;
    volatile ToolRpcContracts.CatalogQuery lastToolCatalog;

    JavaClawClient client(Consumer<ServerNotification> notifications) throws IOException {
        return JavaClawClient.connect(this, new ClientInfo("desktop-test", "5.0"), Set.of(), notifications);
    }

    Workspace workspace() {
        return workspace;
    }

    ConversationThread thread() {
        return thread;
    }

    AgentProfile profile() {
        return profile;
    }

    @Override
    public TransportKind kind() {
        return TransportKind.STDIO;
    }

    @Override
    public RpcConnection connect() {
        return this;
    }

    @Override
    public void send(JsonRpcMessage message) {
        JsonRpcRequest request = (JsonRpcRequest) message;
        inbound.add(response(request));
    }

    @Override
    public JsonRpcMessage receive() throws IOException {
        try {
            Object message = inbound.take();
            if (message == CLOSED) {
                throw new IOException("Presenter RPC server closed");
            }
            return (JsonRpcMessage) message;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Presenter RPC server interrupted", interrupted);
        }
    }

    @Override
    public void close() {
        secrets.close();
        closed = true;
        inbound.clear();
        inbound.offer(CLOSED);
    }

    void emit(ExtensionRpcContracts.ExtensionEvent event) {
        inbound.add(new JsonRpcNotification("extension/event", json.encode(event)));
    }

    private JsonRpcResponse response(JsonRpcRequest request) {
        return JsonRpcResponse.success(request.id(), json.encode(catalogResult(request)));
    }

    private Object catalogResult(JsonRpcRequest request) {
        return switch (request.method()) {
            case "initialize/session" ->
                new InitializeResult(
                        2,
                        "javaclaw-app-server",
                        "5.0.0-SNAPSHOT",
                        new NegotiatedCapabilities(Set.of("core.item-envelope"), Set.of()),
                        secrets.publicKey());
            case "workspace/list" -> new CoreRpcContracts.WorkspaceListResult(List.of(workspace));
            case "workspace/create" -> createdWorkspace(request);
            case "profile/list" -> new ProviderProfileRpcContracts.AgentProfileListResult(List.of(profile));
            case "profile/read" -> profile;
            case "profile/binding/read" -> profileBinding(request);
            case "tool/search" -> toolCatalog(request);
            case "thread/list" -> new CoreRpcContracts.ThreadListResult(List.of(thread));
            case "thread/create" -> createdThread(request);
            default -> executionResult(request);
        };
    }

    private ProviderProfileRpcContracts.ProfileBindingReadResult profileBinding(JsonRpcRequest request) {
        ProviderProfileRpcContracts.ProfileBindingReadPayload payload =
                json.decode(request.params(), ProviderProfileRpcContracts.ProfileBindingReadPayload.class);
        if (!workspace.id().equals(payload.workspaceId()) || payload.threadId().isPresent()) {
            throw new AssertionError("profile binding scope mismatch");
        }
        ProfileBinding binding = new ProfileBinding(
                workspace.id(),
                Optional.empty(),
                new com.javaclaw.api.AgentProfileRef(profile.id(), profile.revision()),
                1,
                DesktopTestFixtures.NOW);
        return new ProviderProfileRpcContracts.ProfileBindingReadResult(
                profileBound ? Optional.of(binding) : Optional.empty());
    }

    private ToolRpcContracts.SearchResult toolCatalog(JsonRpcRequest request) {
        lastToolCatalog = json.decode(request.params(), ToolRpcContracts.CatalogQuery.class);
        ToolDescriptor tool = new ToolDescriptor(
                new ToolIdentity("core", "read_file", 3),
                "读取文件",
                new CanonicalPayload("{\"additionalProperties\":false,\"properties\":{},\"type\":\"object\"}"),
                new CanonicalPayload(
                        "{\"additionalProperties\":false,\"properties\":{\"exitCode\":{\"type\":\"integer\"},\"metadata\":{\"properties\":{\"verified\":{\"type\":\"boolean\"}},\"type\":\"object\"},\"rows\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"}},\"type\":\"object\"}"),
                ToolRisk.READ_ONLY,
                Set.of("file"));
        return new ToolRpcContracts.SearchResult(9, List.of(tool));
    }

    private Object executionResult(JsonRpcRequest request) {
        return switch (request.method()) {
            case "item/list" -> items(request);
            case "turn/start" -> startedTurn(request);
            case "turn/read" -> currentTurn();
            case "turn/cancel" -> cancelledTurn(request);
            case "turn/input/list" -> inputs(request);
            case "turn/input/resolve" -> resolvedInput(request);
            default -> managementResult(request);
        };
    }

    private Object managementResult(JsonRpcRequest request) {
        return switch (request.method()) {
            case "extension/job/list" -> jobs(request);
            case "extension/job/read" -> jobDetails(request);
            case "extension/job/pause", "extension/job/resume", "extension/job/cancel" -> mutateJob(request);
            case "approval/list" -> approvals();
            case "approval/resolve" -> resolvedApproval(request);
            case "extension/view/list" -> views();
            case "extension/query" -> extensionQuery(request);
            case "extension/command" -> extensionCommand(request);
            default -> throw new AssertionError("unexpected method " + request.method());
        };
    }

    private Workspace createdWorkspace(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        CoreRpcContracts.WorkspaceCreatePayload payload =
                json.decode(command.payload(), CoreRpcContracts.WorkspaceCreatePayload.class);
        if (payload.name().isBlank()) {
            throw new AssertionError("workspace name must be present");
        }
        workspaceCreates.incrementAndGet();
        return workspace;
    }

    private ConversationThread createdThread(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        CoreRpcContracts.ThreadCreatePayload payload =
                json.decode(command.payload(), CoreRpcContracts.ThreadCreatePayload.class);
        if (!payload.workspaceId().equals(workspace.id())) {
            throw new AssertionError("thread workspace mismatch");
        }
        threadCreates.incrementAndGet();
        return thread;
    }

    private CoreRpcContracts.ItemListResult items(JsonRpcRequest request) {
        CoreRpcContracts.ItemList payload = json.decode(request.params(), CoreRpcContracts.ItemList.class);
        if (payload.afterSequence() == 0) {
            return new CoreRpcContracts.ItemListResult(List.of(DesktopTestFixtures.item(1)), 1);
        }
        if (payload.afterSequence() == 1) {
            return new CoreRpcContracts.ItemListResult(List.of(DesktopTestFixtures.item(2)), 2);
        }
        return new CoreRpcContracts.ItemListResult(List.of(), payload.afterSequence());
    }

    private AgentTurn startedTurn(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        lastTurnStart = json.decode(command.payload(), CoreRpcContracts.TurnStartPayload.class);
        cancelRequested = false;
        turnStarts.incrementAndGet();
        return DesktopTestFixtures.turn(thread, TurnStatus.RUNNING, 1);
    }

    private AgentTurn currentTurn() {
        turnReads.incrementAndGet();
        TurnStatus status = cancelRequested ? TurnStatus.CANCELLED : completion;
        return DesktopTestFixtures.turn(thread, status, status == TurnStatus.RUNNING ? 1 : 2);
    }

    private AgentTurn cancelledTurn(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        CoreRpcContracts.TurnCancelPayload payload =
                json.decode(command.payload(), CoreRpcContracts.TurnCancelPayload.class);
        if (!payload.turnId().equals(DesktopTestFixtures.turn().id())) {
            throw new AssertionError("turn id mismatch");
        }
        cancelRequested = true;
        if (input.pending()) {
            input = new InputRequestRecord(
                    input.request(),
                    InputRequestState.CANCELLED,
                    input.revision() + 1,
                    java.util.Optional.empty(),
                    java.util.Optional.of("所属 Turn 已取消"),
                    DesktopTestFixtures.NOW);
        }
        turnCancels.incrementAndGet();
        return DesktopTestFixtures.turn(thread, TurnStatus.CANCELLED, 2);
    }

    private InputJobRpcContracts.InputListResult inputs(JsonRpcRequest request) {
        InputJobRpcContracts.InputListPayload payload =
                json.decode(request.params(), InputJobRpcContracts.InputListPayload.class);
        inputLists.incrementAndGet();
        boolean matchesTurn =
                payload.turnId().map(input.request().turnId()::equals).orElse(true);
        boolean visible = matchesTurn && (payload.includeResolved() || input.pending());
        return new InputJobRpcContracts.InputListResult(visible ? List.of(input) : List.of());
    }

    private InputRequestRecord resolvedInput(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        InputJobRpcContracts.InputResolvePayload payload =
                json.decode(command.payload(), InputJobRpcContracts.InputResolvePayload.class);
        if (!payload.requestId().equals(input.request().id()) || command.expectedRevision() != input.revision()) {
            throw new AssertionError("input request identity mismatch");
        }
        lastInputResponse = payload.response();
        input = new InputRequestRecord(
                input.request(),
                InputRequestState.RESOLVED,
                input.revision() + 1,
                java.util.Optional.of(payload.response()),
                java.util.Optional.empty(),
                DesktopTestFixtures.NOW);
        inputResolutions.incrementAndGet();
        return input;
    }

    private InputJobRpcContracts.JobListResult jobs(JsonRpcRequest request) {
        InputJobRpcContracts.JobListPayload payload =
                json.decode(request.params(), InputJobRpcContracts.JobListPayload.class);
        boolean workspaceMatches =
                payload.workspaceId().map(job.workspaceId()::equals).orElse(true);
        boolean extensionMatches =
                payload.extensionId().map(job.extensionId().value()::equals).orElse(true);
        boolean stateMatches = payload.states().isEmpty() || payload.states().contains(job.state());
        jobLists.incrementAndGet();
        return new InputJobRpcContracts.JobListResult(
                workspaceMatches && extensionMatches && stateMatches ? List.of(job) : List.of(), Optional.empty());
    }

    private InputJobRpcContracts.JobReadResult jobDetails(JsonRpcRequest request) {
        InputJobRpcContracts.JobReadPayload payload =
                json.decode(request.params(), InputJobRpcContracts.JobReadPayload.class);
        if (!payload.jobId().equals(job.id())) {
            throw new AssertionError("job id mismatch");
        }
        jobReads.incrementAndGet();
        return new InputJobRpcContracts.JobReadResult(job, List.of(jobUnit(job)));
    }

    private ExtensionExecutionReceipt mutateJob(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        InputJobRpcContracts.JobMutationPayload payload =
                json.decode(command.payload(), InputJobRpcContracts.JobMutationPayload.class);
        if (!payload.jobId().equals(job.id()) || command.expectedRevision() != job.revision()) {
            throw new AssertionError("job mutation identity mismatch");
        }
        lastJobIdempotencyKey = command.idempotencyKey();
        lastJobExpectedRevision = command.expectedRevision();
        ExecutionState target =
                switch (request.method()) {
                    case "extension/job/pause" -> ExecutionState.PAUSED;
                    case "extension/job/resume" -> ExecutionState.QUEUED;
                    case "extension/job/cancel" -> ExecutionState.CANCELLED;
                    default -> throw new AssertionError("unexpected job mutation");
                };
        job = job(target, job.revision() + 1);
        jobMutations.incrementAndGet();
        return job;
    }

    private ExtensionExecutionReceipt job(ExecutionState state, long revision) {
        Instant updated = DesktopTestFixtures.NOW.plusSeconds(revision);
        return new ExtensionExecutionReceipt(
                "workflow-job",
                new ExtensionId("com.javaclaw.workflow"),
                workspace.id(),
                "definition-execution",
                "workflow-definition",
                2,
                state,
                revision,
                Optional.empty(),
                DesktopTestFixtures.NOW,
                updated);
    }

    private static InputJobRpcContracts.JobUnitSummary jobUnit(ExtensionExecutionReceipt owner) {
        return new InputJobRpcContracts.JobUnitSummary(
                owner.id(),
                1,
                "prepare",
                ExtensionJobUnitState.COMPLETED,
                Optional.of(DesktopTestFixtures.turn().id()),
                Optional.of("effect-1"),
                Optional.empty(),
                DesktopTestFixtures.NOW,
                Optional.of(DesktopTestFixtures.NOW.plusSeconds(1)));
    }

    private CoreRpcContracts.ApprovalListResult approvals() {
        return new CoreRpcContracts.ApprovalListResult(
                cancelRequested ? List.of() : List.of(DesktopTestFixtures.approval(ApprovalState.PENDING)));
    }

    private com.javaclaw.api.ApprovalRecord resolvedApproval(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        CoreRpcContracts.ApprovalResolvePayload payload =
                json.decode(command.payload(), CoreRpcContracts.ApprovalResolvePayload.class);
        if (payload.decision() != ApprovalDecision.APPROVED && payload.decision() != ApprovalDecision.DENIED) {
            throw new AssertionError("unsupported approval decision");
        }
        approvalResolutions.incrementAndGet();
        return DesktopTestFixtures.approval(
                payload.decision() == ApprovalDecision.APPROVED ? ApprovalState.APPROVED : ApprovalState.DENIED);
    }

    private ExtensionRpcContracts.ViewListResult views() {
        extensionViewLists.incrementAndGet();
        ExtensionRpcContracts.ViewDocument document =
                new ExtensionRpcContracts.ViewDocument("plan", "plan.documents", viewSchema);
        return new ExtensionRpcContracts.ViewListResult(List.of(document));
    }

    private ExtensionRpcContracts.CallResult extensionQuery(JsonRpcRequest request) {
        extensionQueries.incrementAndGet();
        ExtensionRpcContracts.CallPayload call = json.decode(request.params(), ExtensionRpcContracts.CallPayload.class);
        ViewQueryRequest query = json.decode(call.payload(), ViewQueryRequest.class);
        viewQueries.add(query);
        ViewQueryResult result =
                new ViewQueryResult(query.dataSourceId(), extensionRows(query), json.encode(Map.of()), "", false, 0);
        return new ExtensionRpcContracts.CallResult(json.encode(result), 0);
    }

    private List<CanonicalPayload> extensionRows(ViewQueryRequest query) {
        return switch (query.dataSourceId()) {
            case "definitions" ->
                List.of(
                        json.encode(Map.of("id", "workflow-1", "name", "第一项", "revision", 3)),
                        json.encode(Map.of("id", "workflow-2", "name", "第二项", "revision", 7)));
            case "graphNodes" -> List.of(json.encode(Map.of("id", "start", "name", "开始", "kind", "START")));
            case "graphEdges" -> List.of();
            default -> List.of(json.encode(Map.of("title", "计划", "detail", "执行中", "value", 0.5)));
        };
    }

    private ExtensionRpcContracts.CallResult extensionCommand(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        lastExpectedRevision = command.expectedRevision();
        ExtensionRpcContracts.CallPayload call =
                json.decode(command.payload(), ExtensionRpcContracts.CallPayload.class);
        if (call.operation().isBlank()) {
            throw new AssertionError("operation must be present");
        }
        extensionCommands.incrementAndGet();
        return new ExtensionRpcContracts.CallResult(new CanonicalPayload("{}"), 1);
    }
}
