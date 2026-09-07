package com.javaclaw.server.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpSamplingMessage;
import com.javaclaw.api.McpSamplingRequest;
import com.javaclaw.api.McpSamplingRole;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.runtime.BudgetAccount;
import com.javaclaw.runtime.BudgetExceededException;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnExecutionResult;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.TurnPromptSnapshot;
import com.javaclaw.server.persistence.TurnStartRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSamplingTurnServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final ToolIdentity MCP_TOOL = new ToolIdentity("mcp.docs", "docs_lookup", 1);

    @TempDir
    Path temporaryDirectory;

    private final CanonicalJson json = new CanonicalJson();
    private final MutableClock clock = new MutableClock();
    private final List<TurnExecutionCommand> commands = new ArrayList<>();
    private CoreCommandService core;
    private H2TurnJournal journal;
    private Workspace workspace;
    private AgentTurn parent;
    private BudgetAccount budget;
    private McpSamplingTurnService sampling;
    private int modelCalls;
    private com.javaclaw.api.CancellationToken samplingCancellation;

    @BeforeEach
    void initialize() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock, core.liveBudgets());
        workspace = core.createWorkspace(identity("workspace/create", "workspace"), "Sampling", temporaryDirectory);
        sampling = new McpSamplingTurnService(core, this::executeChild, json, clock);
        createParent();
    }

    @AfterEach
    void releaseBudget() {
        if (parent != null && budget != null) {
            journal.deactivateBudget(parent.id(), budget);
        }
    }

    @Test
    void samplingReservesParentBudgetAndFreezesOnlyPlatformInstructions() throws Exception {
        sampling.sample(parent.id(), endpoint(), request("first"), new CancellationSource());

        TurnExecutionCommand child = commands.getFirst();
        assertEquals(parent.role(), child.turn().role());
        assertEquals(parent.provider(), child.provider());
        assertEquals(1_872, budget.remainingOutputTokens());
        assertEquals(0, child.turn().budget().toolCalls());
        assertEquals(0, child.turn().budget().childThreads());
        assertTrue(child.toolCatalog().tools().isEmpty());
        assertEquals("", child.instructions().developerInstructions());
        assertFalse(child.instructions().systemInstruction().contains("external-instruction"));
        assertTrue(child.userMessage().contains("external-instruction"));
        var config = core.resolvedConfig(child.turn().id());
        assertEquals(ApprovalPolicy.EVERY_CALL, config.approvalPolicy());
        assertEquals(PermissionConstraint.READ_ONLY, config.permissionConstraint());
        assertEquals(Optional.of(Set.of()), config.effectiveSkills());
        assertEquals(
                Optional.of(parent.id()),
                core.parentTurn(child.turn().threadId()).map(AgentTurn::id));
        TurnPromptSnapshot prompt =
                json.decode(core.promptManifest(config.promptManifestDigest()), TurnPromptSnapshot.class);
        assertTrue(prompt.instructions().sources().isEmpty());
        assertEquals(child.instructions(), prompt.modelInstructions());
    }

    @Test
    void sameRequestRecoversFrozenReservationWithoutActiveBudgetOrAnotherModelCall() throws Exception {
        var first = sampling.sample(parent.id(), endpoint(), request("retry"), new CancellationSource());
        journal.deactivateBudget(parent.id(), budget);
        clock.now = NOW.plusSeconds(20);

        var retried = sampling.sample(parent.id(), endpoint(), request("retry"), new CancellationSource());

        assertEquals(first, retried);
        assertEquals(1, modelCalls);
        assertEquals(1_872, budget.remainingOutputTokens());
        assertEquals(commands.getFirst().turn().id(), commands.getLast().turn().id());
        assertEquals(2, core.listThreads(workspace.id()).size());
    }

    @Test
    void consumedParentBudgetRejectsSamplingBeforeCreatingAChild() {
        budget.consume(new ModelUsage(0, 1_900, 0, 0));

        assertThrows(
                BudgetExceededException.class,
                () -> sampling.sample(parent.id(), endpoint(), request("exhausted"), new CancellationSource()));
        assertEquals(1, core.listThreads(workspace.id()).size());
        assertEquals(0, modelCalls);
        assertEquals(100, budget.remainingOutputTokens());
    }

    @Test
    void missingLiveAccountRejectsSamplingWithoutPersistingReservation() {
        journal.deactivateBudget(parent.id(), budget);

        assertThrows(
                PersistenceException.class,
                () -> sampling.sample(parent.id(), endpoint(), request("inactive"), new CancellationSource()));
        assertEquals(1, core.listThreads(workspace.id()).size());
    }

    @Test
    void samplingCannotExtendParentDeadlineOrReuseARequestForDifferentData() throws Exception {
        clock.now = NOW.plusSeconds(55);
        sampling.sample(parent.id(), endpoint(), request("deadline"), new CancellationSource());
        assertEquals(Duration.ofSeconds(5), commands.getFirst().turn().budget().wallTime());
        McpSamplingRequest changed =
                new McpSamplingRequest("deadline", "docs", request("deadline").messages(), 129);
        assertThrows(
                PersistenceException.class,
                () -> sampling.sample(parent.id(), endpoint(), changed, new CancellationSource()));
        clock.now = NOW.plusSeconds(60);
        assertTrue(samplingCancellation.isCancelled());
        assertThrows(
                PersistenceException.class,
                () -> sampling.sample(parent.id(), endpoint(), request("late"), new CancellationSource()));
    }

    private void createParent() {
        ConversationThread thread = core.createThread(
                identity("thread/create", "parent"),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "parent");
        ToolDescriptor tool =
                new ToolDescriptor(MCP_TOOL, "MCP", json.parse("{}"), json.parse("{}"), ToolRisk.READ_ONLY, Set.of());
        ToolCatalogSnapshot catalog = new ToolCatalogSnapshot(
                TurnId.random(), 1, List.of(tool), TurnContractFixtures.TOOL_CATALOG.permissionCeiling(), NOW);
        TurnBudget limit = new TurnBudget(32_768, 2_000, 4, 4, Duration.ofSeconds(60));
        TurnStartRequest start = TurnContractFixtures.request(
                thread.id(),
                new TurnContractFixtures.Selection(
                        limit,
                        TurnContractFixtures.ROLE,
                        TurnContractFixtures.PROVIDER,
                        TurnContractFixtures.PERMISSIONS),
                temporaryDirectory,
                TurnContractFixtures.PROMPT_SNAPSHOT,
                catalog,
                new CorePayloads.Message(MessageRole.USER, "parent", List.of(), Optional.empty()),
                Optional.empty());
        AgentTurn queued = core.startTurn(identity("turn/start", "parent-turn"), start);
        ToolCatalogSnapshot frozen = json.decode(core.toolCatalogSnapshot(queued.id()), ToolCatalogSnapshot.class);
        journal.beginOrRecover(new TurnExecutionCommand(
                queued, queued.provider(), "parent system", "parent", frozen.permissionCeiling(), frozen));
        journal.recordModelIntent(queued.id(), 1, "a".repeat(64));
        journal.commitModelResult(
                queued.id(),
                1,
                new ModelInvocationResult(
                        "",
                        List.of(new ModelToolCall("mcp-call", MCP_TOOL, json.parse("{}"))),
                        ModelUsage.zero(),
                        Optional.empty(),
                        Optional.empty(),
                        ModelFinishReason.TOOL_CALLS),
                ModelUsage.zero());
        parent = core.findTurn(queued.id()).orElseThrow();
        budget = new BudgetAccount(limit, clock, NOW, ModelUsage.zero(), 0);
        journal.activateBudget(parent.id(), budget);
    }

    private TurnExecutionResult executeChild(
            TurnExecutionCommand command, com.javaclaw.api.CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        samplingCancellation = cancellation;
        commands.add(command);
        if (core.findTurn(command.turn().id()).orElseThrow().status() != TurnStatus.COMPLETED) {
            journal.beginOrRecover(command);
            journal.recordModelIntent(command.turn().id(), 1, "b".repeat(64));
            journal.commitModelResult(
                    command.turn().id(),
                    1,
                    new ModelInvocationResult(
                            "sampled",
                            List.of(),
                            ModelUsage.zero(),
                            Optional.empty(),
                            Optional.empty(),
                            ModelFinishReason.COMPLETE),
                    ModelUsage.zero());
            journal.transition(command.turn().id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
            modelCalls++;
        }
        return new TurnExecutionResult(
                command.turn().id(),
                TurnStatus.COMPLETED,
                "sampled",
                ModelUsage.zero(),
                0,
                Optional.empty(),
                Optional.empty());
    }

    private McpSamplingRequest request(String id) {
        return new McpSamplingRequest(
                id,
                "docs",
                List.of(new McpSamplingMessage(
                        McpSamplingRole.USER, json.parse("{\"type\":\"text\",\"text\":\"external-instruction\"}"))),
                128);
    }

    private McpEndpoint endpoint() {
        McpEndpointSpec spec = new McpEndpointSpec(
                workspace.id(),
                "Docs",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example/rpc")),
                Optional.empty(),
                McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(10));
        return new McpEndpoint("docs", 1, McpEndpointState.ENABLED, 1, spec, NOW, NOW);
    }

    private CommandIdentity identity(String method, String key) {
        return new CommandIdentity(
                method, key, 0, json.encode(java.util.Map.of("key", key)).sha256());
    }

    private static final class MutableClock extends Clock {
        private Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
