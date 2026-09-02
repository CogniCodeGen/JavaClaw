package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnExecutionPhase;
import com.javaclaw.runtime.TurnRecoverySnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2TurnCrashRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final ModelUsage COMMITTED_USAGE = new ModelUsage(7, 3, 1, 2);

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;

    @BeforeEach
    void initializeDataV5() {
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        core = new CoreCommandService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        journal = restartedJournal();
    }

    @Test
    void 模型调用前的RunningTurn可按原冻结引用恢复() {
        Fixture fixture = fixture("before-model");
        TurnRecoverySnapshot first = journal.beginOrRecover(fixture.command());

        TurnRecoverySnapshot recovered = restartedJournal().beginOrRecover(running(fixture));

        assertEquals(TurnExecutionPhase.READY_FOR_MODEL, recovered.phase());
        assertEquals(first, recovered);
        assertFrozenIdentity(fixture.turn());
    }

    @Test
    void 模型调用意图后重启保留UnknownOutcome证据且不伪造Usage() {
        Fixture fixture = fixture("model-intent");
        journal.beginOrRecover(fixture.command());
        journal.recordModelIntent(fixture.turn().id(), 1, "1".repeat(64));

        TurnRecoverySnapshot recovered = restartedJournal().beginOrRecover(running(fixture));

        assertEquals(TurnExecutionPhase.MODEL_IN_FLIGHT, recovered.phase());
        assertTrue(recovered.phase().unknownAfterRestart());
        assertEquals(ModelUsage.zero(), recovered.usage());
        assertEquals(Optional.of("1".repeat(64)), recovered.activeIntentDigest());
        assertFrozenIdentity(fixture.turn());
    }

    @Test
    void 工具意图后重启保留已消费预算并禁止盲目重放() {
        Fixture fixture = fixture("tool-intent");
        ToolCallRequest request = commitToolBatch(fixture);
        journal.recordToolIntent(fixture.turn().id(), 0, request, 1, "2".repeat(64));

        TurnRecoverySnapshot recovered = restartedJournal().beginOrRecover(running(fixture));

        assertEquals(TurnExecutionPhase.TOOL_IN_FLIGHT, recovered.phase());
        assertTrue(recovered.phase().unknownAfterRestart());
        assertEquals(COMMITTED_USAGE, recovered.usage());
        assertEquals(1, recovered.toolCalls());
        assertEquals(
                fixture.call().callId(),
                recovered.toolBatch().calls().getFirst().callId());
        assertFrozenIdentity(fixture.turn());
    }

    @Test
    void receipt与ToolResult提交后只恢复下一安全点且不重复扣减() throws Exception {
        Fixture fixture = fixture("receipt");
        ToolCallRequest request = commitToolBatch(fixture);
        journal.recordToolIntent(fixture.turn().id(), 0, request, 1, "2".repeat(64));
        var output = json.parse("{\"written\":true}");
        EffectReceipt receipt = new EffectReceipt(
                request.idempotencyKey(),
                request.tool().name(),
                request.arguments().sha256(),
                output.sha256(),
                NOW);
        ToolExecutionOutcome outcome = ToolExecutionOutcome.resultOnly(
                new ToolCallResult(request.callId(), true, output, Optional.of(receipt)));
        journal.commitToolResult(
                fixture.turn().id(), 0, request, outcome, List.of(fixture.call().tool()));

        H2TurnJournal restarted = restartedJournal();
        TurnRecoverySnapshot recovered = restarted.beginOrRecover(running(fixture));

        assertEquals(TurnExecutionPhase.READY_FOR_MODEL, recovered.phase());
        assertFalse(recovered.phase().unknownAfterRestart());
        assertEquals(COMMITTED_USAGE, recovered.usage());
        assertEquals(1, recovered.toolCalls());
        assertEquals(outcome.result(), restarted.recoverEffect(request).orElseThrow());
        assertThrows(
                PersistenceException.class,
                () -> restarted.commitToolResult(
                        fixture.turn().id(),
                        0,
                        request,
                        outcome,
                        List.of(fixture.call().tool())));
        assertFrozenIdentity(fixture.turn());
    }

    private ToolCallRequest commitToolBatch(Fixture fixture) {
        journal.beginOrRecover(fixture.command());
        journal.recordModelIntent(fixture.turn().id(), 1, "1".repeat(64));
        journal.commitModelResult(
                fixture.turn().id(),
                1,
                new ModelInvocationResult(
                        "",
                        List.of(fixture.call()),
                        COMMITTED_USAGE,
                        Optional.empty(),
                        Optional.empty(),
                        ModelFinishReason.TOOL_CALLS),
                COMMITTED_USAGE);
        return new ToolCallRequest(
                fixture.turn().id(),
                fixture.call().callId(),
                fixture.call().tool(),
                fixture.call().arguments(),
                fixture.turn().id() + ":" + fixture.call().callId(),
                fixture.catalog().catalogRevision());
    }

    private Fixture fixture(String suffix) {
        Workspace workspace = core.listWorkspaces().stream()
                .findFirst()
                .orElseGet(() -> core.createWorkspace(
                        identity("workspace/create", "workspace", suffix),
                        "恢复测试",
                        temporaryDirectory.resolve("workspace")));
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + suffix, suffix),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                suffix);
        ToolIdentity identity = new ToolIdentity("builtin.test", "write", 1);
        ToolDescriptor descriptor = new ToolDescriptor(
                identity,
                "写入测试",
                json.parse("{\"type\":\"object\"}"),
                json.parse("{\"type\":\"object\"}"),
                ToolRisk.WORKSPACE_WRITE,
                Set.of("test"));
        var permission = com.javaclaw.server.TurnContractFixtures.TOOL_CATALOG.permissionCeiling();
        ToolCatalogSnapshot catalog =
                new ToolCatalogSnapshot(com.javaclaw.api.TurnId.random(), 1, List.of(descriptor), permission, NOW);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        TurnStartRequest start = new TurnStartRequest(
                thread.id(),
                budget(),
                com.javaclaw.server.TurnContractFixtures.PROFILE,
                com.javaclaw.server.TurnContractFixtures.PROVIDER,
                com.javaclaw.server.TurnContractFixtures.PERMISSIONS,
                temporaryDirectory,
                com.javaclaw.server.TurnContractFixtures.PROMPT_SNAPSHOT,
                catalog,
                message,
                Optional.empty());
        AgentTurn turn = core.startTurn(identity("turn/start", "turn-" + suffix, suffix), start);
        ToolCatalogSnapshot persisted = json.decode(core.toolCatalogSnapshot(turn.id()), ToolCatalogSnapshot.class);
        TurnExecutionCommand command = new TurnExecutionCommand(
                turn, turn.provider(), "system", suffix, persisted.permissionCeiling(), persisted);
        ModelToolCall call = new ModelToolCall("call-1", identity, json.parse("{\"path\":\"notes.txt\"}"));
        return new Fixture(turn, persisted, command, call);
    }

    private TurnExecutionCommand running(Fixture fixture) {
        AgentTurn current = core.findTurn(fixture.turn().id()).orElseThrow();
        return new TurnExecutionCommand(
                current,
                current.provider(),
                fixture.command().systemInstruction(),
                fixture.command().userMessage(),
                fixture.catalog().permissionCeiling(),
                fixture.catalog());
    }

    private void assertFrozenIdentity(AgentTurn expected) {
        AgentTurn current = core.findTurn(expected.id()).orElseThrow();
        assertEquals(expected.profile(), current.profile());
        assertEquals(expected.provider(), current.provider());
        assertEquals(expected.permissionProfile(), current.permissionProfile());
        assertEquals(expected.promptManifestDigest(), current.promptManifestDigest());
        assertEquals(expected.toolCatalogDigest(), current.toolCatalogDigest());
        assertEquals(TurnStatus.RUNNING, current.status());
    }

    private H2TurnJournal restartedJournal() {
        return new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CommandIdentity identity(String method, String key, String value) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.encode(Map.of("value", value))), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }

    private record Fixture(
            AgentTurn turn, ToolCatalogSnapshot catalog, TurnExecutionCommand command, ModelToolCall call) {}
}
