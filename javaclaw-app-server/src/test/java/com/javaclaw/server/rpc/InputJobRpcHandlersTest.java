package com.javaclaw.server.rpc;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionJobService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.InputRequestService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputJobRpcHandlersTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final ExtensionId EXTENSION = new ExtensionId("com.javaclaw.workflow");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private InputRequestService inputs;
    private ExtensionJobService jobs;
    private RpcRouter router;
    private Workspace workspace;

    @BeforeEach
    void initializeDataV5() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        inputs = new InputRequestService(database, json, clock);
        jobs = new ExtensionJobService(database, json, clock);
        RpcRouter.Builder routes = RpcRouter.builder();
        new InputJobRpcHandlers(inputs, jobs, json).register(routes);
        router = routes.build();
        workspace = createWorkspace();
    }

    @Test
    void input列表和决议经由Rpc保持Turn过滤与幂等Revision() throws Exception {
        AgentTurn turn = createTurn("input");
        InputRequest request = new InputRequest(
                "workflow-input",
                turn.id(),
                EXTENSION.value(),
                "请选择",
                json.parse("{\"properties\":{\"choice\":{\"type\":\"string\"}},\"type\":\"object\"}"),
                NOW,
                NOW.plusSeconds(60));
        inputs.open(request);

        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            InputJobRpcContracts.InputListResult pending = route(
                    "turn/input/list",
                    new InputJobRpcContracts.InputListPayload(Optional.of(turn.id()), false),
                    InputJobRpcContracts.InputListResult.class,
                    secrets);
            InputJobRpcContracts.InputResolvePayload resolution =
                    new InputJobRpcContracts.InputResolvePayload(request.id(), json.parse("{\"choice\":\"continue\"}"));
            var resolved = route(
                    "turn/input/resolve",
                    command("resolve-input", 1, resolution),
                    com.javaclaw.api.InputRequestRecord.class,
                    secrets);
            InputJobRpcContracts.InputListResult all = route(
                    "turn/input/list",
                    new InputJobRpcContracts.InputListPayload(Optional.empty(), true),
                    InputJobRpcContracts.InputListResult.class,
                    secrets);

            assertEquals(
                    List.of(request.id()),
                    pending.requests().stream().map(item -> item.request().id()).toList());
            assertEquals(com.javaclaw.api.InputRequestState.RESOLVED, resolved.state());
            assertEquals(List.of(resolved), all.requests());
        }
    }

    @Test
    void job查询分页和三种生命周期命令只返回脱敏Receipt() throws Exception {
        ExtensionJob first = submit("first", "submit-first");
        submit("second", "submit-second");

        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            InputJobRpcContracts.JobListResult page = route(
                    "extension/job/list",
                    new InputJobRpcContracts.JobListPayload(
                            Optional.of(workspace.id()),
                            Optional.of(EXTENSION.value()),
                            Set.of(ExecutionState.QUEUED),
                            Optional.empty(),
                            1),
                    InputJobRpcContracts.JobListResult.class,
                    secrets);
            InputJobRpcContracts.JobReadResult detail = route(
                    "extension/job/read",
                    new InputJobRpcContracts.JobReadPayload(first.id()),
                    InputJobRpcContracts.JobReadResult.class,
                    secrets);
            ExtensionExecutionReceipt paused =
                    mutate("extension/job/pause", "pause-job", first.id(), first.revision(), secrets);
            ExtensionExecutionReceipt resumed =
                    mutate("extension/job/resume", "resume-job", first.id(), paused.revision(), secrets);
            ExtensionExecutionReceipt cancelled =
                    mutate("extension/job/cancel", "cancel-job", first.id(), resumed.revision(), secrets);

            assertEquals(1, page.jobs().size());
            assertTrue(page.nextCursor().isPresent());
            assertEquals(first.id(), detail.job().id());
            assertTrue(detail.units().isEmpty());
            assertEquals(ExecutionState.PAUSED, paused.state());
            assertEquals(ExecutionState.QUEUED, resumed.state());
            assertEquals(ExecutionState.CANCELLED, cancelled.state());
        }
    }

    private Workspace createWorkspace() {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("RPC Job", temporaryDirectory.resolve("workspace"));
        Workspace created = core.createWorkspace(
                identity("workspace/create", "workspace", 0, payload), payload.name(), payload.root());
        return created;
    }

    private AgentTurn createTurn(String suffix) {
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, suffix);
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + suffix, 0, threadPayload),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                suffix);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload payload = TurnContractFixtures.payload(thread.id(), suffix);
        AgentTurn turn = core.startTurn(
                identity("turn/start", "turn-" + suffix, 0, payload),
                TurnContractFixtures.request(thread.id(), budget(), message));
        journal.transition(turn.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        return core.findTurn(turn.id()).orElseThrow();
    }

    private ExtensionJob submit(String definitionId, String key) throws Exception {
        ExtensionJobSubmission submission = new ExtensionJobSubmission(
                EXTENSION,
                workspace.id(),
                "workflow",
                definitionId,
                1,
                json.parse("{\"roleRevision\":1}"),
                json.parse("{\"completed\":0}"));
        return jobs.submit(json.encode(submission), new ExtensionJobMutation(key, 0), () -> submission);
    }

    private ExtensionExecutionReceipt mutate(
            String method, String key, String jobId, long revision, SessionSecretChannel secrets) throws Exception {
        return route(
                method,
                command(key, revision, new InputJobRpcContracts.JobMutationPayload(jobId)),
                ExtensionExecutionReceipt.class,
                secrets);
    }

    private <T> T route(String method, Object params, Class<T> type, SessionSecretChannel secrets) throws Exception {
        return json.decode(router.route(method, json.encode(params), secrets), type);
    }

    private WriteCommand command(String key, long revision, Object payload) {
        return new WriteCommand(key, revision, json.encode(payload));
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, command(key, revision, payload), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }
}
