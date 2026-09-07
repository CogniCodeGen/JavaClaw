package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class H2TurnJournalIntegrityBranchesTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String EFFECT_KEY = "effect-key";

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private Seed seed;

    @BeforeEach
    void initializeEffect() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        seed = seedEffect("primary");
    }

    @Test
    void recoveredEffectRejectsEveryMismatchedRequestIdentityComponent() {
        ToolCallRequest request = seed.request();
        ToolIdentity tool = request.tool();

        assertRejected(request(TurnId.random(), request.callId(), tool, seed.arguments()));
        assertRejected(request(request.turnId(), "other-call", tool, seed.arguments()));
        assertRejected(request(
                request.turnId(),
                request.callId(),
                new ToolIdentity("other-producer", tool.name(), tool.revision()),
                seed.arguments()));
        assertRejected(request(
                request.turnId(),
                request.callId(),
                new ToolIdentity(tool.producerId(), "other-tool", tool.revision()),
                seed.arguments()));
        assertRejected(request(
                request.turnId(),
                request.callId(),
                new ToolIdentity(tool.producerId(), tool.name(), tool.revision() + 1),
                seed.arguments()));
        assertRejected(request(request.turnId(), request.callId(), tool, json.parse("{\"path\":\"other\"}")));

        assertEquals(seed.result(), journal.recoverEffect(request).orElseThrow());
    }

    @Test
    void recoveredEffectRejectsEveryCorruptedResultPayloadComponent() throws Exception {
        EffectReceipt receipt = seed.receipt();
        CanonicalPayload otherOutput = json.parse("{\"ok\":false}");

        assertCorrupted(new ToolCallResult("other-call", true, seed.output(), Optional.of(receipt)));
        assertCorrupted(new ToolCallResult(seed.call().callId(), true, otherOutput, Optional.of(receipt)));
        assertCorrupted(new ToolCallResult(seed.call().callId(), true, seed.output(), Optional.empty()));
        assertCorrupted(new ToolCallResult(
                seed.call().callId(),
                true,
                seed.output(),
                Optional.of(receipt("other-effect", receipt.requestDigest(), receipt.resultDigest()))));
        assertCorrupted(new ToolCallResult(
                seed.call().callId(),
                true,
                seed.output(),
                Optional.of(receipt(EFFECT_KEY, "0".repeat(64), receipt.resultDigest()))));
        assertCorrupted(new ToolCallResult(
                seed.call().callId(),
                true,
                seed.output(),
                Optional.of(receipt(EFFECT_KEY, receipt.requestDigest(), "0".repeat(64)))));

        replaceResultPayload(seed.result());
        assertEquals(seed.result(), journal.recoverEffect(seed.request()).orElseThrow());
    }

    @Test
    void duplicateEffectRejectsEveryChangedStoredIdentityComponent() throws Exception {
        AgentTurn otherTurn = createAggregate("other").turn();

        assertStoredMismatch(
                "TURN_ID", otherTurn.id().toString(), seed.turn().id().toString());
        assertStoredMismatch("CALL_ID", "other-call", seed.call().callId());
        assertStoredMismatch("PRODUCER_ID", "other-producer", seed.call().producerId());
        assertStoredMismatch("TOOL_NAME", "other-tool", seed.call().toolName());
        assertStoredMismatch("TOOL_REVISION", 2L, seed.call().toolRevision());
        assertStoredMismatch("REQUEST_DIGEST", "0".repeat(64), seed.receipt().requestDigest());
        assertStoredMismatch("RESULT_DIGEST", "0".repeat(64), seed.receipt().resultDigest());

        journal.append(
                seed.turn().id(), "tool-result", CoreSchemas.TOOL_RESULT, coreResult(seed), ItemStatus.COMPLETED);
    }

    @Test
    void effectReceipt提交逐字段拒绝ToolName请求摘要与结果摘要漂移() {
        EffectReceipt receipt = seed.receipt();
        assertReceiptRejected(receipt("new-tool", "other-tool", receipt.requestDigest(), receipt.resultDigest()));
        assertReceiptRejected(receipt("new-request", receipt.toolName(), "0".repeat(64), receipt.resultDigest()));
        assertReceiptRejected(receipt("new-result", receipt.toolName(), receipt.requestDigest(), "0".repeat(64)));
    }

    private void assertRejected(ToolCallRequest request) {
        assertThrows(PersistenceException.class, () -> journal.recoverEffect(request));
    }

    private void assertCorrupted(ToolCallResult result) throws Exception {
        replaceResultPayload(result);
        assertThrows(PersistenceException.class, () -> journal.recoverEffect(seed.request()));
    }

    private void assertStoredMismatch(String column, Object changed, Object original) throws Exception {
        updateEffect(column, changed);
        assertThrows(
                PersistenceException.class,
                () -> journal.append(
                        seed.turn().id(),
                        "tool-result",
                        CoreSchemas.TOOL_RESULT,
                        coreResult(seed),
                        ItemStatus.COMPLETED));
        updateEffect(column, original);
    }

    private void assertReceiptRejected(EffectReceipt receipt) {
        ToolCallResult result = new ToolCallResult(seed.call().callId(), true, seed.output(), Optional.of(receipt));
        assertThrows(
                PersistenceException.class,
                () -> journal.append(
                        seed.turn().id(),
                        "tool-result",
                        CoreSchemas.TOOL_RESULT,
                        coreResult(seed.call(), result),
                        ItemStatus.COMPLETED));
    }

    private void replaceResultPayload(ToolCallResult result) throws Exception {
        updateEffect("RESULT_PAYLOAD", json.encode(result).json());
    }

    private void updateEffect(String column, Object value) throws Exception {
        try (var connection = database.open();
                var statement = connection.prepareStatement(
                        "UPDATE CORE.EFFECT_RECEIPT SET " + column + " = ? WHERE IDEMPOTENCY_KEY = ?")) {
            statement.setObject(1, value);
            statement.setString(2, EFFECT_KEY);
            statement.executeUpdate();
        }
    }

    private Seed seedEffect(String suffix) {
        AgentTurn turn = createAggregate(suffix).turn();
        CanonicalPayload arguments = json.parse("{\"path\":\"notes.txt\"}");
        CanonicalPayload output = json.parse("{\"ok\":true}");
        CorePayloads.ToolCall call = new CorePayloads.ToolCall("call-1", "builtin.files", "write", 1, arguments);
        EffectReceipt receipt = receipt(EFFECT_KEY, arguments.sha256(), output.sha256());
        ToolCallResult result = new ToolCallResult(call.callId(), true, output, Optional.of(receipt));
        journal.append(turn.id(), "tool-call", CoreSchemas.TOOL_CALL, call, ItemStatus.COMPLETED);
        journal.append(
                turn.id(), "tool-result", CoreSchemas.TOOL_RESULT, coreResult(call, result), ItemStatus.COMPLETED);
        ToolCallRequest request = request(
                turn.id(),
                call.callId(),
                new ToolIdentity(call.producerId(), call.toolName(), call.toolRevision()),
                arguments);
        return new Seed(turn, call, arguments, output, receipt, result, request);
    }

    private CorePayloads.ToolResult coreResult(Seed value) {
        return coreResult(value.call(), value.result());
    }

    private static CorePayloads.ToolResult coreResult(CorePayloads.ToolCall call, ToolCallResult result) {
        return new CorePayloads.ToolResult(call.callId(), result.success(), result.output(), result.receipt());
    }

    private ToolCallRequest request(TurnId turnId, String callId, ToolIdentity tool, CanonicalPayload arguments) {
        return new ToolCallRequest(turnId, callId, tool, arguments, EFFECT_KEY, 1);
    }

    private EffectReceipt receipt(String key, String requestDigest, String resultDigest) {
        return receipt(key, "write", requestDigest, resultDigest);
    }

    private EffectReceipt receipt(String key, String toolName, String requestDigest, String resultDigest) {
        return new EffectReceipt(key, toolName, requestDigest, resultDigest, NOW);
    }

    private Aggregate createAggregate(String suffix) {
        Workspace workspace = core.listWorkspaces().stream().findFirst().orElseGet(() -> {
            CoreRpcContracts.WorkspaceCreatePayload payload =
                    new CoreRpcContracts.WorkspaceCreatePayload("日志测试", temporaryDirectory.resolve("workspace"));
            return core.createWorkspace(
                    identity("workspace/create", "workspace", payload), payload.name(), payload.root());
        });
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, suffix);
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + suffix, threadPayload),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                suffix);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload payload =
                com.javaclaw.server.TurnContractFixtures.payload(thread.id(), suffix);
        AgentTurn turn = core.startTurn(
                identity("turn/start", "turn-" + suffix, payload),
                com.javaclaw.server.TurnContractFixtures.request(thread.id(), budget(), message));
        return new Aggregate(turn);
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.encode(payload)), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }

    private record Aggregate(AgentTurn turn) {}

    private record Seed(
            AgentTurn turn,
            CorePayloads.ToolCall call,
            CanonicalPayload arguments,
            CanonicalPayload output,
            EffectReceipt receipt,
            ToolCallResult result,
            ToolCallRequest request) {}
}
