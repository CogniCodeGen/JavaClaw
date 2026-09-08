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
import com.javaclaw.api.ApprovalRequest;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
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
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.ProviderState;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnExecutionPhase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnJournalBranchesTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final ModelUsage USAGE = new ModelUsage(5, 3, 1, 2);

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;

    @BeforeEach
    void initializeDataV6() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        core = new CoreCommandService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        journal = new H2TurnJournal(
                database, CoreItemCodecs.createRegistry(json), json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void journal拒绝无Checkpoint非法Turn状态和非法模型序号() {
        Fixture missing = fixture("missing-checkpoint", 1);
        assertTrue(journal.findRecovery(missing.turn().id()).isEmpty());
        assertThrows(
                PersistenceException.class,
                () -> journal.readRecovery(missing.turn().id()));
        assertThrows(
                IllegalArgumentException.class,
                () -> journal.recordModelIntent(missing.turn().id(), 0, "1".repeat(64)));

        journal.transition(missing.turn().id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        assertThrows(PersistenceException.class, () -> journal.beginOrRecover(runningCommand(missing)));

        Fixture completed = fixture("completed", 1);
        journal.transition(completed.turn().id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        journal.transition(completed.turn().id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
        assertThrows(PersistenceException.class, () -> journal.beginOrRecover(runningCommand(completed)));
    }

    @Test
    void model结果提交可见文本与ProviderState并累计usage() throws Exception {
        Fixture fixture = fixture("provider-state", 1);
        journal.beginOrRecover(fixture.command());
        journal.recordModelIntent(fixture.turn().id(), 1, "1".repeat(64));
        ProviderState state = new ProviderState("provider", "opaque-v1", json.parse("{\"cursor\":\"next\"}"));

        journal.commitModelResult(
                fixture.turn().id(),
                1,
                new ModelInvocationResult(
                        "模型响应", List.of(), USAGE, Optional.of("摘要"), Optional.of(state), ModelFinishReason.COMPLETE),
                USAGE);

        var recovery = journal.readRecovery(fixture.turn().id());
        assertEquals(TurnExecutionPhase.MODEL_COMMITTED, recovery.phase());
        assertEquals("模型响应", recovery.assistantText());
        assertEquals(USAGE, recovery.usage());
        assertEquals(1, providerStateCount(fixture.turn().id()));

        journal.saveProviderState(fixture.turn().id(), "manual-model", state, 10);
        assertEquals(1, providerStateCount(fixture.turn().id()));
        assertThrows(
                IllegalArgumentException.class,
                () -> journal.saveProviderState(fixture.turn().id(), "manual-model", state, -1));
    }

    @Test
    void 多工具批次逐项提交并在最后一个结果后清空恢复位置() {
        Fixture fixture = fixture("two-tools", 2);
        commitBatch(fixture);
        ToolCallRequest first = request(fixture, 0);
        ToolCallRequest second = request(fixture, 1);

        assertThrows(
                PersistenceException.class,
                () -> journal.recordToolIntent(fixture.turn().id(), 0, first, 2, "2".repeat(64)));
        journal.recordToolIntent(fixture.turn().id(), 0, first, 1, "2".repeat(64));
        assertThrows(
                PersistenceException.class,
                () -> journal.recordToolIntent(fixture.turn().id(), 0, first, 1, "2".repeat(64)));
        journal.commitToolResult(
                fixture.turn().id(),
                0,
                first,
                outcome(first),
                List.of(fixture.calls().getFirst().tool()));

        var afterFirst = journal.readRecovery(fixture.turn().id());
        assertEquals(TurnExecutionPhase.TOOLS_READY, afterFirst.phase());
        assertEquals(1, afterFirst.nextToolIndex());
        assertEquals(2, afterFirst.toolBatch().calls().size());

        journal.recordToolIntent(fixture.turn().id(), 1, second, 2, "3".repeat(64));
        journal.commitToolResult(
                fixture.turn().id(),
                1,
                second,
                outcome(second),
                fixture.calls().stream().map(ModelToolCall::tool).toList());
        assertEquals(
                TurnExecutionPhase.READY_FOR_MODEL,
                journal.readRecovery(fixture.turn().id()).phase());
        assertTrue(journal.readRecovery(fixture.turn().id()).toolBatch().calls().isEmpty());
    }

    @Test
    void journal拒绝恢复位置和工具身份不一致() {
        Fixture fixture = fixture("identity", 1);
        commitBatch(fixture);
        ToolCallRequest request = request(fixture, 0);
        ToolCallRequest wrongTurn = new ToolCallRequest(
                TurnId.random(),
                request.callId(),
                request.tool(),
                request.arguments(),
                request.idempotencyKey(),
                request.expectedCatalogRevision());
        ToolCallRequest wrongCall = new ToolCallRequest(
                request.turnId(),
                "other-call",
                request.tool(),
                request.arguments(),
                request.idempotencyKey(),
                request.expectedCatalogRevision());

        assertThrows(
                PersistenceException.class,
                () -> journal.recordToolIntent(fixture.turn().id(), 1, request, 1, "2".repeat(64)));
        assertThrows(
                PersistenceException.class,
                () -> journal.recordToolIntent(fixture.turn().id(), 0, wrongTurn, 1, "2".repeat(64)));
        assertThrows(
                PersistenceException.class,
                () -> journal.recordToolIntent(fixture.turn().id(), 0, wrongCall, 1, "2".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> journal.recordToolIntent(fixture.turn().id(), 0, request, 1, "not-a-digest"));
    }

    @Test
    void checkpoint审批阶段校验精确工具身份和状态() throws Exception {
        Fixture ready = fixture("approval-ready", 1);
        commitBatch(ready);
        ApprovalRequest readyApproval = approval(ready, 0);
        TurnExecutionCheckpointRepository checkpoints = new TurnExecutionCheckpointRepository(json);
        try (var connection = database.open()) {
            assertThrows(
                    PersistenceException.class, () -> checkpoints.markApprovalWaiting(connection, readyApproval, NOW));
        }

        journal.recordToolIntent(ready.turn().id(), 0, request(ready, 0), 1, "2".repeat(64));
        try (var connection = database.open()) {
            checkpoints.markApprovalWaiting(connection, readyApproval, NOW);
            assertThrows(
                    PersistenceException.class, () -> checkpoints.markApprovalWaiting(connection, readyApproval, NOW));
            checkpoints.markApprovalResolved(connection, readyApproval, NOW);
            checkpoints.markApprovedExternalCall(connection, readyApproval, NOW);
            checkpoints.markApprovedExternalCall(connection, readyApproval, NOW);

            ApprovalRequest mismatch = new ApprovalRequest(
                    "mismatch",
                    readyApproval.turnId(),
                    readyApproval.callId(),
                    new ToolIdentity(
                            "other",
                            readyApproval.tool().name(),
                            readyApproval.tool().revision()),
                    readyApproval.risk(),
                    readyApproval.explanation(),
                    readyApproval.requestDigest(),
                    NOW,
                    NOW.plusSeconds(60));
            assertThrows(PersistenceException.class, () -> checkpoints.markApprovalWaiting(connection, mismatch, NOW));
            assertThrows(
                    PersistenceException.class,
                    () -> checkpoints.markApprovalWaiting(
                            connection,
                            new ApprovalRequest(
                                    "missing",
                                    TurnId.random(),
                                    "missing-call",
                                    readyApproval.tool(),
                                    readyApproval.risk(),
                                    "缺失",
                                    readyApproval.requestDigest(),
                                    NOW,
                                    NOW.plusSeconds(60)),
                            NOW));
        }
    }

    @Test
    void append校验Schema并将Sql故障包装为持久化边界() throws Exception {
        Fixture fixture = fixture("append");
        assertThrows(
                IllegalArgumentException.class,
                () -> journal.append(
                        fixture.turn().id(),
                        "message",
                        CoreSchemas.TOOL_CALL,
                        new CorePayloads.Message(MessageRole.USER, "错误 schema", List.of(), Optional.empty()),
                        ItemStatus.COMPLETED));

        try (var connection = database.open();
                var statement = connection.prepareStatement("DROP TABLE CORE.TURN_EXECUTION_CHECKPOINT CASCADE")) {
            statement.executeUpdate();
        }
        PersistenceException failure = assertThrows(
                PersistenceException.class,
                () -> journal.findRecovery(fixture.turn().id()));
        assertTrue(failure.getCause() instanceof java.sql.SQLException);
    }

    @Test
    void 公开流同步落盘且最终Item使用预留身份并可跨实例重放() throws Exception {
        Fixture fixture = fixture("public-stream", 0);
        var turnId = fixture.turn().id();
        journal.beginOrRecover(fixture.command());
        journal.recordModelIntent(turnId, 1, "1".repeat(64));
        var sink = new H2ModelEventSink(database, json, Clock.fixed(NOW, ZoneOffset.UTC)).forInvocation(1);
        var cancellation = new com.javaclaw.api.CancellationSource();
        String body = "a".repeat(2047) + "😀" + "重复重复";
        sink.publish(turnId, new com.javaclaw.runtime.ModelStreamEvent.TextDelta(body), cancellation);
        sink.publish(turnId, new com.javaclaw.runtime.ModelStreamEvent.ReasoningSummaryDelta("不公开内部内容"), cancellation);
        var streams = new TurnStreamService(database, json);
        var before = streams.list(new com.javaclaw.protocol.TurnStreamRpcContracts.ListRequest(turnId, "START", 128));
        assertEquals(3, before.events().size());
        assertEquals(
                body,
                before.events().stream()
                        .map(event -> event.data().text())
                        .collect(java.util.stream.Collectors.joining()));
        assertTrue(
                before.events().stream().noneMatch(event -> event.data().text().contains("不公开")));
        var expectedId = before.events().getFirst().data().call().orElseThrow().messageItemId();
        journal.commitModelResult(
                turnId,
                1,
                new ModelInvocationResult(
                        body, List.of(), USAGE, Optional.empty(), Optional.empty(), ModelFinishReason.COMPLETE),
                USAGE);
        journal.transition(turnId, TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
        var after = new TurnStreamService(new H2Database(temporaryDirectory.resolve("data-v6")), json)
                .list(new com.javaclaw.protocol.TurnStreamRpcContracts.ListRequest(turnId, before.nextCursor(), 128));
        assertEquals(
                List.of(com.javaclaw.api.TurnStreamKind.COMMITTED, com.javaclaw.api.TurnStreamKind.TURN_FINISHED),
                after.events().stream().map(event -> event.data().kind()).toList());
        assertEquals(before.nextCursor(), after.events().getFirst().previousCursor());
        assertEquals(
                expectedId, core.listItems(fixture.turn().threadId()).getLast().id());
        assertTrue(streams.watermark(turnId).terminal());
        assertThrows(
                PersistenceException.class,
                () -> sink.publish(turnId, new com.javaclaw.runtime.ModelStreamEvent.TextDelta("迟到"), cancellation));
    }

    @Test
    void 首次模型调用前取消具有终态但不伪造调用身份() {
        Fixture fixture = fixture("early-cancel", 0);
        journal.transition(fixture.turn().id(), TurnStatus.QUEUED, TurnStatus.CANCELLED, Optional.empty());
        var page = new TurnStreamService(database, json)
                .list(new com.javaclaw.protocol.TurnStreamRpcContracts.ListRequest(
                        fixture.turn().id(), "START", 128));
        assertEquals(1, page.events().size());
        assertEquals(
                com.javaclaw.api.TurnStreamKind.TURN_FINISHED,
                page.events().getFirst().data().kind());
        assertTrue(page.events().getFirst().data().call().isEmpty());
    }

    @Test
    void 公开游标拒绝跨Turn伪造与不存在的已提交位置() {
        Fixture first = fixture("cursor-first", 0);
        Fixture second = fixture("cursor-second", 0);
        journal.transition(first.turn().id(), TurnStatus.QUEUED, TurnStatus.CANCELLED, Optional.empty());
        var streams = new TurnStreamService(database, json);
        var page = streams.list(new com.javaclaw.protocol.TurnStreamRpcContracts.ListRequest(
                first.turn().id(), "START", 1));
        assertThrows(
                PersistenceException.class,
                () -> streams.list(new com.javaclaw.protocol.TurnStreamRpcContracts.ListRequest(
                        second.turn().id(), page.nextCursor(), 1)));
        assertThrows(
                PersistenceException.class,
                () -> streams.list(new com.javaclaw.protocol.TurnStreamRpcContracts.ListRequest(
                        first.turn().id(), "not-a-cursor", 1)));
        var next = streams.list(new com.javaclaw.protocol.TurnStreamRpcContracts.ListRequest(
                first.turn().id(), page.nextCursor(), 1));
        assertTrue(next.events().isEmpty());
        assertEquals(page.nextCursor(), next.nextCursor());
    }

    @Test
    void 最终消息后续Checkpoint失败回滚Item和公开提交事件() throws Exception {
        Fixture fixture = fixture("rollback", 0);
        var turnId = fixture.turn().id();
        journal.beginOrRecover(fixture.command());
        journal.recordModelIntent(turnId, 1, "1".repeat(64));
        var streams = new TurnStreamService(database, json);
        var before = streams.list(new com.javaclaw.protocol.TurnStreamRpcContracts.ListRequest(turnId, "START", 128));
        int count = core.listItems(fixture.turn().threadId()).size();
        // 制造 Item 已插入后才触发的 checkpoint 条件更新失败，检查整个事务回滚。
        try (var connection = database.open();
                var statement = connection.prepareStatement(
                        "UPDATE CORE.TURN_EXECUTION_CHECKPOINT SET PHASE='READY_FOR_MODEL', ACTIVE_INTENT_DIGEST=NULL WHERE TURN_ID=?")) {
            statement.setString(1, turnId.toString());
            statement.executeUpdate();
        }
        var result = new ModelInvocationResult(
                "不能部分提交", List.of(), USAGE, Optional.empty(), Optional.empty(), ModelFinishReason.COMPLETE);
        assertThrows(PersistenceException.class, () -> journal.commitModelResult(turnId, 1, result, USAGE));
        assertEquals(count, core.listItems(fixture.turn().threadId()).size());
        assertEquals(
                before,
                streams.list(new com.javaclaw.protocol.TurnStreamRpcContracts.ListRequest(turnId, "START", 128)));
    }

    @Test
    void 分片末尾非法Unicode回滚整个publish且正文不重复写内部topic() throws Exception {
        Fixture fixture = fixture("delta-rollback", 0);
        var turnId = fixture.turn().id();
        journal.beginOrRecover(fixture.command());
        journal.recordModelIntent(turnId, 1, "1".repeat(64));
        var sink = new H2ModelEventSink(database, json, Clock.fixed(NOW, ZoneOffset.UTC)).forInvocation(1);
        var token = new com.javaclaw.api.CancellationSource();
        assertThrows(
                IllegalArgumentException.class,
                () -> sink.publish(
                        turnId,
                        new com.javaclaw.runtime.ModelStreamEvent.TextDelta("x".repeat(2048) + "\uDC00"),
                        token));
        var streams = new TurnStreamService(database, json);
        var page = streams.list(new com.javaclaw.protocol.TurnStreamRpcContracts.ListRequest(turnId, "START", 128));
        assertEquals(1, page.events().size());
        sink.publish(turnId, new com.javaclaw.runtime.ModelStreamEvent.TextDelta("唯一正文"), token);
        try (var connection = database.open();
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM CORE.EVENT WHERE TURN_ID=? AND TOPIC='turn.stream.text'")) {
            statement.setString(1, turnId.toString());
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    private void commitBatch(Fixture fixture) {
        journal.beginOrRecover(fixture.command());
        journal.recordModelIntent(fixture.turn().id(), 1, "1".repeat(64));
        journal.commitModelResult(
                fixture.turn().id(),
                1,
                new ModelInvocationResult(
                        "", fixture.calls(), USAGE, Optional.empty(), Optional.empty(), ModelFinishReason.TOOL_CALLS),
                USAGE);
    }

    private ToolCallRequest request(Fixture fixture, int index) {
        ModelToolCall call = fixture.calls().get(index);
        return new ToolCallRequest(
                fixture.turn().id(),
                call.callId(),
                call.tool(),
                call.arguments(),
                fixture.turn().id() + ":" + call.callId(),
                fixture.catalog().catalogRevision());
    }

    private ToolExecutionOutcome outcome(ToolCallRequest request) {
        return ToolExecutionOutcome.resultOnly(
                new ToolCallResult(request.callId(), true, json.parse("{\"ok\":true}"), Optional.empty()));
    }

    private ApprovalRequest approval(Fixture fixture, int index) {
        ModelToolCall call = fixture.calls().get(index);
        return new ApprovalRequest(
                "approval-" + call.callId(),
                fixture.turn().id(),
                call.callId(),
                call.tool(),
                ToolRisk.EXTERNAL_EFFECT,
                "执行工具",
                call.arguments().sha256(),
                NOW,
                NOW.plusSeconds(60));
    }

    private Fixture fixture(String suffix) {
        return fixture(suffix, 1);
    }

    private Fixture fixture(String suffix, int callCount) {
        Workspace workspace = core.listWorkspaces().stream()
                .findFirst()
                .orElseGet(() -> core.createWorkspace(
                        identity("workspace/create", "workspace", suffix),
                        "Turn 日志测试",
                        temporaryDirectory.resolve("workspace")));
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + suffix, suffix),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                suffix);
        ToolIdentity tool = new ToolIdentity("builtin.test", "write", 1);
        ToolDescriptor descriptor = new ToolDescriptor(
                tool,
                "写入测试",
                json.parse("{\"type\":\"object\"}"),
                json.parse("{\"type\":\"object\"}"),
                ToolRisk.WORKSPACE_WRITE,
                Set.of("test"));
        ToolCatalogSnapshot catalog = new ToolCatalogSnapshot(
                TurnId.random(),
                1,
                List.of(descriptor),
                com.javaclaw.server.TurnContractFixtures.TOOL_CATALOG.permissionCeiling(),
                NOW);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        TurnStartRequest start = com.javaclaw.server.TurnContractFixtures.request(
                thread.id(),
                new com.javaclaw.server.TurnContractFixtures.Selection(
                        budget(),
                        com.javaclaw.server.TurnContractFixtures.ROLE,
                        com.javaclaw.server.TurnContractFixtures.PROVIDER,
                        com.javaclaw.server.TurnContractFixtures.PERMISSIONS),
                temporaryDirectory,
                com.javaclaw.server.TurnContractFixtures.PROMPT_SNAPSHOT,
                catalog,
                message,
                Optional.empty());
        AgentTurn turn = core.startTurn(identity("turn/start", "turn-" + suffix, suffix), start);
        ToolCatalogSnapshot persisted = json.decode(core.toolCatalogSnapshot(turn.id()), ToolCatalogSnapshot.class);
        TurnExecutionCommand command = new TurnExecutionCommand(
                turn, turn.provider(), "system", suffix, persisted.permissionCeiling(), persisted);
        List<ModelToolCall> calls = java.util.stream.IntStream.range(0, callCount)
                .mapToObj(index -> new ModelToolCall("call-" + index, tool, json.parse("{\"index\":" + index + "}")))
                .toList();
        return new Fixture(turn, persisted, command, calls);
    }

    private TurnExecutionCommand runningCommand(Fixture fixture) {
        AgentTurn current = core.findTurn(fixture.turn().id()).orElseThrow();
        return new TurnExecutionCommand(
                current,
                current.provider(),
                fixture.command().systemInstruction(),
                fixture.command().userMessage(),
                fixture.catalog().permissionCeiling(),
                fixture.catalog());
    }

    private int providerStateCount(TurnId turnId) throws Exception {
        try (var connection = database.open();
                var statement =
                        connection.prepareStatement("SELECT COUNT(*) FROM CORE.PROVIDER_STATE WHERE TURN_ID = ?")) {
            statement.setString(1, turnId.toString());
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private CommandIdentity identity(String method, String key, String value) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.encode(Map.of("value", value))), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 4, 0, Duration.ofMinutes(1));
    }

    private record Fixture(
            AgentTurn turn, ToolCatalogSnapshot catalog, TurnExecutionCommand command, List<ModelToolCall> calls) {}
}
