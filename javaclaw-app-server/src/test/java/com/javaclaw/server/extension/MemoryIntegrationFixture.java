package com.javaclaw.server.extension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ScheduleDefinitionBindingPort;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.ProviderRoleRpcFixtures;
import com.javaclaw.server.rpc.AppServerSession;

/** 经真实 RPC、H2、Supervisor 与 Thin Harness 操作 Memory；不直接改写领域托管记录。 */
final class MemoryIntegrationFixture implements AutoCloseable {
    final AppServerBootstrap.Components components;
    final AppServerSession session;
    final MemoryIntegrationModel model;
    final Path dataRoot;
    private final String commandPrefix = java.util.UUID.randomUUID().toString();
    private int sequence;

    MemoryIntegrationFixture(Path dataRoot, Clock clock, MemoryIntegrationModel model) throws Exception {
        this.dataRoot = dataRoot;
        this.model = model;
        components = AppServerBootstrap.create(dataRoot, clock, model, ignored -> {});
        session = components.newSession();
        rpc(
                "initialize/session",
                new InitializeParams(
                        ProtocolVersion.CURRENT,
                        new ClientInfo("memory-integration", "6"),
                        new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of())));
    }

    void installProvider() {
        ProviderRoleRpcFixtures.install(
                session,
                components,
                new ProviderRoleRpcFixtures.Installation(
                        "memory-provider",
                        "memory-model",
                        "memory-agent",
                        new PermissionProfileRef("standard", 1),
                        Set.of(),
                        new TurnBudget(16000, 2000, 0, 0, Duration.ofSeconds(120))));
    }

    Workspace workspace(String name) throws Exception {
        Path root = dataRoot.getParent().resolve(name);
        Files.createDirectories(root);
        return decode(
                write("workspace/create", new CoreRpcContracts.WorkspaceCreatePayload(name, root), 0), Workspace.class);
    }

    AgentTurn conversation(Workspace workspace, String text) throws Exception {
        var thread = decode(
                write(
                        "thread/create",
                        new CoreRpcContracts.ThreadCreatePayload(
                                workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, text),
                        0),
                ConversationThread.class);
        var turn = decode(
                        write(
                                "turn/start",
                                new CoreRpcContracts.TurnStartPayload(thread.id(), ExecutionOverrides.empty(), text),
                                0),
                        CoreRpcContracts.TurnStartResult.class)
                .turn();
        await(
                () -> Set.of(TurnStatus.COMPLETED, TurnStatus.FAILED, TurnStatus.CANCELLED)
                        .contains(turn(turn.id()).status()),
                "普通对话 Turn 未终结");
        if (turn(turn.id()).status() != TurnStatus.COMPLETED) {
            throw new AssertionError("普通对话未成功：" + turn(turn.id()));
        }
        return turn(turn.id());
    }

    AgentTurn turn(TurnId id) {
        return decode(rpc("turn/read", Map.of("turnId", id)), AgentTurn.class);
    }

    void awaitLearning(Workspace workspace) throws InterruptedException {
        try {
            model.awaitLearning();
        } catch (AssertionError failure) {
            throw new AssertionError(
                    failure.getMessage() + "\nJobs: " + jobs(workspace) + "\nBatches: " + batches(workspace), failure);
        }
    }

    MemoryV3Contracts.LearningDefinition save(Workspace workspace, boolean enabled, long revision) {
        return command(
                workspace,
                BuiltinExtensionIds.MEMORY,
                "learning/save",
                new MemoryV3Contracts.LearningSave(enabled, ExecutionOverrides.empty(), false),
                revision,
                MemoryV3Contracts.LearningDefinition.class);
    }

    ScheduleDefinitionBindingPort.Binding binding(Workspace workspace) {
        var payload = queryPayload(workspace, BuiltinExtensionIds.MEMORY, "learning/read", Map.of());
        return decode(
                components.json().objectField(payload, "binding").orElseThrow(),
                ScheduleDefinitionBindingPort.Binding.class);
    }

    ScheduleDefinitionBindingPort.Binding awaitBinding(Workspace workspace) throws Exception {
        await(
                () -> binding(workspace).state() == ScheduleDefinitionBindingPort.State.BOUND,
                "学习定义的托管 Schedule 未绑定：" + jobs(workspace));
        return binding(workspace);
    }

    ExtensionExecutionReceipt run(Workspace workspace, long revision) {
        return command(
                workspace,
                BuiltinExtensionIds.MEMORY,
                "learning/run",
                Map.of(),
                revision,
                ExtensionExecutionReceipt.class);
    }

    ScheduleContracts.Occurrence fire(Workspace workspace, String scheduleId, long revision) {
        return command(
                workspace,
                BuiltinExtensionIds.SCHEDULE,
                "occurrence/run",
                new ScheduleContracts.ManualRun(scheduleId),
                revision,
                ScheduleContracts.Occurrence.class);
    }

    List<ScheduleContracts.Occurrence> occurrences(Workspace workspace) {
        return query(
                        workspace,
                        BuiltinExtensionIds.SCHEDULE,
                        "occurrence/list",
                        new ScheduleContracts.OccurrenceQuery(Optional.empty(), "", 100),
                        ScheduleContracts.OccurrencePage.class)
                .occurrences();
    }

    List<ExtensionExecutionReceipt> jobs(Workspace workspace) {
        return decode(
                        rpc(
                                "extension/job/list",
                                new InputJobRpcContracts.JobListPayload(
                                        Optional.of(workspace.id()),
                                        Optional.empty(),
                                        Set.of(),
                                        Optional.empty(),
                                        200)),
                        InputJobRpcContracts.JobListResult.class)
                .jobs();
    }

    InputJobRpcContracts.JobReadResult job(String id) {
        return decode(
                rpc("extension/job/read", new InputJobRpcContracts.JobReadPayload(id)),
                InputJobRpcContracts.JobReadResult.class);
    }

    InputJobRpcContracts.JobReadResult awaitJob(String id, ExecutionState state) throws Exception {
        try {
            await(() -> job(id).job().state() == state || job(id).job().state().terminal(), "Job 未到达预期状态 " + state);
        } catch (AssertionError failure) {
            throw new AssertionError(failure.getMessage() + "：" + job(id), failure);
        }
        var result = job(id);
        if (result.job().state() != state) {
            throw new AssertionError("Job 状态非 " + state + "：" + result);
        }
        return result;
    }

    List<CanonicalPayload> batches(Workspace workspace) {
        return query(
                        workspace,
                        BuiltinExtensionIds.MEMORY,
                        "learning/batches",
                        new DocumentContracts.PageRequest("", 100),
                        DocumentContracts.Page.class)
                .documents();
    }

    List<MemoryContracts.Proposal> proposals(Workspace workspace) {
        return query(
                        workspace,
                        BuiltinExtensionIds.MEMORY,
                        "proposal/list",
                        new DocumentContracts.PageRequest("", 100),
                        DocumentContracts.Page.class)
                .documents()
                .stream()
                .map(value -> decode(value, MemoryContracts.Proposal.class))
                .toList();
    }

    MemoryV3Contracts.SearchResult search(Workspace workspace) {
        return query(
                workspace,
                BuiltinExtensionIds.MEMORY,
                "search/v2",
                new MemoryContracts.SearchRequest("记忆事实", Set.of(), Set.of(), 100),
                MemoryV3Contracts.SearchResult.class);
    }

    ViewQueryResult conflicts(Workspace workspace) {
        return query(
                workspace,
                BuiltinExtensionIds.MEMORY,
                "view.conflicts",
                new ViewQueryRequest("conflicts", Map.of(), "", 100, Optional.empty()),
                ViewQueryResult.class);
    }

    <T> T command(
            Workspace workspace, String extension, String operation, Object payload, long revision, Class<T> type) {
        var call = call(workspace, extension, operation, payload);
        var result = decode(write("extension/command", call, revision), ExtensionRpcContracts.CallResult.class);
        return decode(result.payload(), type);
    }

    JsonRpcResponse rejectedCommand(
            Workspace workspace, String extension, String operation, Object payload, long revision) {
        String key = commandPrefix + "-reject-" + (++sequence);
        return session.handle(request(
                "extension/command",
                new WriteCommand(
                        key, revision, components.json().encode(call(workspace, extension, operation, payload)))));
    }

    <T> T query(Workspace workspace, String extension, String operation, Object payload, Class<T> type) {
        return decode(queryPayload(workspace, extension, operation, payload), type);
    }

    CanonicalPayload queryPayload(Workspace workspace, String extension, String operation, Object payload) {
        return decode(
                        rpc("extension/query", call(workspace, extension, operation, payload)),
                        ExtensionRpcContracts.CallResult.class)
                .payload();
    }

    CanonicalPayload write(String method, Object payload, long revision) {
        String key = commandPrefix + "-command-" + (++sequence);
        return rpc(method, new WriteCommand(key, revision, components.json().encode(payload)));
    }

    CanonicalPayload rpc(String method, Object payload) {
        var response = session.handle(request(method, payload));
        return response.result()
                .orElseThrow(() ->
                        new AssertionError(method + " 请求失败：" + response.error().orElseThrow()));
    }

    <T> T decode(CanonicalPayload payload, Class<T> type) {
        return components.json().decode(payload, type);
    }

    private JsonRpcRequest request(String method, Object payload) {
        return new JsonRpcRequest(
                new RpcId("rpc-" + (++sequence)), method, components.json().encode(payload));
    }

    private ExtensionRpcContracts.CallPayload call(
            Workspace workspace, String extension, String operation, Object payload) {
        return new ExtensionRpcContracts.CallPayload(
                extension,
                workspace.id(),
                Optional.empty(),
                Optional.empty(),
                operation,
                components.json().encode(payload));
    }

    static void await(BooleanSupplier condition, String failure) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(failure);
            }
            Thread.sleep(25);
        }
    }

    @Override
    public void close() throws Exception {
        model.releaseLearning();
        session.close();
        components.close();
    }
}
