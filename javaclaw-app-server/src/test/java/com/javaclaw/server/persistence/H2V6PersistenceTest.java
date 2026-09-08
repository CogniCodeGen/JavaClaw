package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.RolloutManifest;
import com.javaclaw.api.RolloutSnapshot;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.DocumentRevision;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.extension.CoreItemEvidencePort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2V6PersistenceTest {
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private Clock clock;
    private H2Database database;
    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;

    @BeforeEach
    void initializeEmptyDataV6() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
    }

    @Test
    void emptyDataV6CreatesChatAggregateAndMonotonicItems() {
        Aggregate aggregate = createAggregate();
        journal.transition(aggregate.turn().id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        journal.append(
                aggregate.turn().id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.USER, "你好", List.of(), Optional.empty()),
                ItemStatus.COMPLETED);
        journal.append(
                aggregate.turn().id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.ASSISTANT, "你好，我在。", List.of(), Optional.empty()),
                ItemStatus.COMPLETED);

        List<ItemEnvelope> items = core.listItems(aggregate.thread().id());
        assertEquals(
                List.of(1L, 2L, 3L), items.stream().map(ItemEnvelope::sequence).toList());
        assertEquals(
                "你好",
                json.decode(items.get(1).payload(), CorePayloads.Message.class).text());
        assertTrue(core.findWorkspace(aggregate.workspace().id()).isPresent());
    }

    @Test
    void itemEvidenceRequiresExactWorkspaceThreadItemAndVerbatimText() {
        Aggregate aggregate = createAggregate();
        ItemEnvelope item = core.listItems(aggregate.thread().id()).getFirst();
        CoreItemEvidencePort evidence = new CoreItemEvidencePort(core, json);

        assertTrue(evidence.containsVerbatim(
                aggregate.workspace().id(), aggregate.thread().id(), item.id(), "初始消息"));
        assertFalse(evidence.containsVerbatim(
                WorkspaceId.random(), aggregate.thread().id(), item.id(), "初始消息"));
        assertFalse(evidence.containsVerbatim(
                aggregate.workspace().id(), aggregate.thread().id(), ItemId.random(), "初始消息"));
        assertFalse(evidence.containsVerbatim(
                aggregate.workspace().id(), aggregate.thread().id(), item.id(), "不存在的正文"));
        assertFalse(evidence.isUncertainOutcome(
                aggregate.workspace().id(), aggregate.thread().id(), item.id()));
        assertTrue(evidence.isUserText(
                aggregate.workspace().id(), aggregate.thread().id(), item.id()));
        assertFalse(evidence.isUserText(WorkspaceId.random(), aggregate.thread().id(), item.id()));
        assertFalse(evidence.isUserText(
                aggregate.workspace().id(), aggregate.thread().id(), ItemId.random()));
        journal.append(
                aggregate.turn().id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.ASSISTANT, "助手声称的事实", List.of(), Optional.empty()),
                ItemStatus.COMPLETED);
        assertFalse(evidence.isUserText(
                aggregate.workspace().id(),
                aggregate.thread().id(),
                core.listItems(aggregate.thread().id()).getLast().id()));

        journal.append(
                aggregate.turn().id(),
                "tool-result",
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult("failed-call", false, json.parse("{\"error\":\"结果未知\"}"), Optional.empty()),
                ItemStatus.COMPLETED);
        ItemEnvelope failed = core.listItems(aggregate.thread().id()).getLast();
        assertTrue(evidence.isUncertainOutcome(
                aggregate.workspace().id(), aggregate.thread().id(), failed.id()));
        assertFalse(evidence.isUserText(
                aggregate.workspace().id(), aggregate.thread().id(), failed.id()));
        assertTrue(evidence.isUncertainOutcome(
                aggregate.workspace().id(), aggregate.thread().id(), ItemId.random()));
    }

    @Test
    void messageEvidenceExcludesRoleAndAttachmentMetadata() {
        Aggregate aggregate = createAggregate();
        journal.append(
                aggregate.turn().id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(
                        MessageRole.USER,
                        "请看附件",
                        List.of(new AttachmentRef("a".repeat(64), "text/plain", "使用PostgreSQL.txt", 0)),
                        Optional.empty()),
                ItemStatus.COMPLETED);
        ItemEnvelope item = core.listItems(aggregate.thread().id()).getLast();
        CoreItemEvidencePort evidence = new CoreItemEvidencePort(core, json);
        assertTrue(evidence.isUserText(
                aggregate.workspace().id(), aggregate.thread().id(), item.id()));
        assertTrue(evidence.containsVerbatim(
                aggregate.workspace().id(), aggregate.thread().id(), item.id(), "请看附件"));
        assertFalse(evidence.containsVerbatim(
                aggregate.workspace().id(), aggregate.thread().id(), item.id(), "使用PostgreSQL"));
        assertFalse(evidence.containsVerbatim(
                aggregate.workspace().id(), aggregate.thread().id(), item.id(), "USER"));
        assertFalse(evidence.containsVerbatim(
                aggregate.workspace().id(), aggregate.thread().id(), item.id(), "text/plain"));
    }

    @Test
    void effectReceiptRecoversOnlyTheIdenticalToolRequest() {
        Aggregate aggregate = createAggregate();
        journal.transition(aggregate.turn().id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        CanonicalPayload arguments = json.parse("{\"path\":\"notes.txt\"}");
        CanonicalPayload output = json.parse("{\"written\":true}");
        ToolIdentity identity = new ToolIdentity("builtin.files", "file_write", 3);
        ToolCallRequest request =
                new ToolCallRequest(aggregate.turn().id(), "call-1", identity, arguments, "effect-1", 7);
        EffectReceipt receipt =
                new EffectReceipt("effect-1", identity.name(), arguments.sha256(), output.sha256(), NOW);

        journal.append(
                aggregate.turn().id(),
                "tool-call",
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall(
                        request.callId(), identity.producerId(), identity.name(), identity.revision(), arguments),
                ItemStatus.COMPLETED);
        journal.append(
                aggregate.turn().id(),
                "tool-result",
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(request.callId(), true, output, Optional.of(receipt)),
                ItemStatus.COMPLETED);

        ToolCallResult recovered = journal.recoverEffect(request).orElseThrow();
        assertEquals(output, recovered.output());
        ToolCallRequest conflicting = new ToolCallRequest(
                aggregate.turn().id(), "call-1", identity, json.parse("{\"path\":\"other.txt\"}"), "effect-1", 7);
        assertThrows(PersistenceException.class, () -> journal.recoverEffect(conflicting));
    }

    @Test
    void managedExtensionStoreUsesOptimisticRevisionAndInvalidatesEscapedTransaction() throws Exception {
        H2ManagedExtensionStore store = new H2ManagedExtensionStore(database, clock);
        ExtensionId extensionId = new ExtensionId("com.javaclaw.plan");
        CanonicalPayload first = json.parse("{\"title\":\"第一步\"}");
        CanonicalPayload second = json.parse("{\"title\":\"完成\"}");
        com.javaclaw.extension.spi.ExtensionTransaction[] escaped =
                new com.javaclaw.extension.spi.ExtensionTransaction[1];

        long created = store.inTransaction(extensionId, transaction -> {
            escaped[0] = transaction;
            return transaction.put("plans", "plan-1", 0, first);
        });
        long updated =
                store.inTransaction(extensionId, transaction -> transaction.put("plans", "plan-1", created, second));
        Optional<VersionedDocument> stored =
                store.inTransaction(extensionId, transaction -> transaction.get("plans", "plan-1"));
        List<VersionedDocument> page =
                store.inTransaction(extensionId, transaction -> transaction.list("plans", "", 20));
        store.inTransaction(extensionId, transaction -> {
            transaction.delete("plans", "plan-1", updated);
            return null;
        });
        List<DocumentRevision> deletedHistory =
                store.inTransaction(extensionId, transaction -> transaction.history("plans", "plan-1", 0, 20));
        List<DocumentRevision> tombstones =
                store.inTransaction(extensionId, transaction -> transaction.listTombstones("plans", "", 20));
        long restored = store.inTransaction(extensionId, transaction -> transaction.put("plans", "plan-1", 3, first));
        ExtensionResponse command = store.inCommand(
                extensionId,
                "put",
                "extension-command",
                first.sha256(),
                transaction -> new ExtensionResponse(first, 2));
        ExtensionResponse retried =
                store.inCommand(extensionId, "put", "extension-command", first.sha256(), transaction -> {
                    throw new AssertionError("idempotent extension command executed twice");
                });

        assertEquals(1, created);
        assertEquals(2, updated);
        assertEquals(second, stored.orElseThrow().payload());
        assertEquals(
                List.of(second), page.stream().map(VersionedDocument::payload).toList());
        assertEquals(
                List.of(1L, 2L, 3L),
                deletedHistory.stream().map(DocumentRevision::revision).toList());
        assertTrue(deletedHistory.getLast().tombstone());
        assertEquals(
                List.of("plan-1"),
                tombstones.stream().map(DocumentRevision::key).toList());
        assertEquals(4, restored);
        assertTrue(store.inTransaction(extensionId, transaction -> transaction.listTombstones("plans", "", 20))
                .isEmpty());
        assertEquals(command, retried);
        assertThrows(IllegalStateException.class, () -> escaped[0].get("plans", "plan-1"));
        assertThrows(
                PersistenceException.class,
                () -> store.inTransaction(extensionId, transaction -> transaction.put("plans", "plan-1", 1, first)));
    }

    @Test
    void secondActiveTurnInSameThreadIsRejected() {
        Aggregate aggregate = createAggregate();

        CorePayloads.Message message =
                new CorePayloads.Message(MessageRole.USER, "第二个活动 Turn", List.of(), Optional.empty());
        TurnStartRequest request = com.javaclaw.server.TurnContractFixtures.request(
                aggregate.thread().id(), budget(), message);
        assertThrows(
                PersistenceException.class,
                () -> core.startTurn(
                        identity(
                                "turn/start",
                                "turn-2",
                                com.javaclaw.server.TurnContractFixtures.payload(
                                        aggregate.thread().id(), message.text())),
                        request));
        assertFalse(core.listItems(aggregate.thread().id()).isEmpty());
    }

    @Test
    void rolloutDetectsAnyHashChainModification() throws Exception {
        Aggregate aggregate = createAggregate();
        journal.append(
                aggregate.turn().id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.USER, "需要可验证导出", List.of(), Optional.empty()),
                ItemStatus.COMPLETED);
        Path rollout = temporaryDirectory.resolve("exports/thread.jsonl");
        RolloutExporter exporter = new RolloutExporter(database, json, clock);

        long sourceRevision =
                core.findThread(aggregate.thread().id()).orElseThrow().revision();
        RolloutManifest manifest = exporter.export(aggregate.thread().id(), sourceRevision, rollout);
        RolloutSnapshot verified = exporter.verify(rollout);

        assertEquals(2, manifest.itemCount());
        assertEquals(manifest, verified.manifest());
        assertEquals(
                "需要可验证导出",
                json.decode(verified.items().getLast().payload(), CorePayloads.Message.class)
                        .text());
        String content = Files.readString(rollout, StandardCharsets.UTF_8);
        int hashValue = content.indexOf("\"hash\":\"") + "\"hash\":\"".length();
        char replacement = content.charAt(hashValue) == '0' ? '1' : '0';
        String modified = content.substring(0, hashValue) + replacement + content.substring(hashValue + 1);
        Files.writeString(rollout, modified, StandardCharsets.UTF_8);
        assertThrows(PersistenceException.class, () -> exporter.verify(rollout));
    }

    private Aggregate createAggregate() {
        CoreRpcContracts.WorkspaceCreatePayload workspacePayload =
                new CoreRpcContracts.WorkspaceCreatePayload("测试工作区", temporaryDirectory.resolve("workspace"));
        Workspace workspace = core.createWorkspace(
                identity("workspace/create", "workspace-1", workspacePayload),
                workspacePayload.name(),
                workspacePayload.root());
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, "新对话");
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-1", threadPayload),
                threadPayload.workspaceId(),
                threadPayload.parentThreadId(),
                threadPayload.executionIntent(),
                threadPayload.title());
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, "初始消息", List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload turnPayload =
                com.javaclaw.server.TurnContractFixtures.payload(thread.id(), message.text());
        AgentTurn turn = core.startTurn(
                identity("turn/start", "turn-1", turnPayload),
                com.javaclaw.server.TurnContractFixtures.request(thread.id(), budget(), message));
        return new Aggregate(workspace, thread, turn);
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.encode(payload)), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(8_000, 2_000, 8, 4, Duration.ofMinutes(5));
    }

    private record Aggregate(Workspace workspace, ConversationThread thread, AgentTurn turn) {}
}
