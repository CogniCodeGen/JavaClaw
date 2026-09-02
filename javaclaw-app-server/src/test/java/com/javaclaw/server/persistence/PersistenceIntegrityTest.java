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
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ProviderState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceIntegrityTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private Clock clock;
    private CoreCommandService core;
    private H2TurnJournal journal;

    @BeforeEach
    void initializeDataV5() {
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
    }

    @Test
    void managedExtensionTransactionSupportsAllPortsAndRollsBackFailures() throws Exception {
        Aggregate aggregate = createAggregate("managed");
        H2ManagedExtensionStore store = new H2ManagedExtensionStore(database, clock);
        ExtensionId extension = new ExtensionId("com.javaclaw.test");
        CanonicalPayload payload = json.parse("{\"value\":1}");
        ExtensionTransaction[] escaped = new ExtensionTransaction[1];

        store.inTransaction(extension, transaction -> {
            escaped[0] = transaction;
            assertEquals(1, transaction.put("records", "a", 0, payload));
            assertEquals(1, transaction.put("records", "b", 0, payload));
            assertEquals(
                    List.of("a"),
                    transaction.list("records", "", 1).stream()
                            .map(document -> document.key())
                            .toList());
            transaction.appendItem(
                    aggregate.turn().id(),
                    "checkpoint",
                    "com.javaclaw.test/checkpoint@1",
                    payload,
                    ItemStatus.COMPLETED);
            transaction.appendEvent("record.changed", payload);
            transaction.enqueueOutbox("audit", "event-a", payload);
            transaction.delete("records", "a", 1);
            return null;
        });

        assertTrue(store.inTransaction(extension, transaction -> transaction.get("records", "a"))
                .isEmpty());
        assertEquals(1, count("CORE.EVENT"));
        assertEquals(1, count("CORE.OUTBOX"));
        assertTrue(core.listItems(aggregate.thread().id()).stream()
                .anyMatch(item -> item.producerId().equals(extension.value())));
        assertThrows(IllegalStateException.class, () -> escaped[0].list("records", "", 1));
    }

    @Test
    void managedExtensionRejectsInvalidNamesRevisionsAndCommandReuse() throws Exception {
        H2ManagedExtensionStore store = new H2ManagedExtensionStore(database, clock);
        ExtensionId extension = new ExtensionId("com.javaclaw.validation");
        CanonicalPayload payload = json.parse("{\"ok\":true}");
        String digest = payload.sha256();

        assertThrows(NullPointerException.class, () -> store.inTransaction(null, transaction -> null));
        assertThrows(NullPointerException.class, () -> store.inTransaction(extension, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.inTransaction(extension, transaction -> transaction.put("Bad Name", "key", 0, payload)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.inTransaction(extension, transaction -> transaction.put("records", " ", 0, payload)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.inTransaction(extension, transaction -> transaction.put("records", "x", -1, payload)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.inTransaction(extension, transaction -> transaction.list("records", "", 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.inTransaction(extension, transaction -> transaction.list("records", "", 501)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.inTransaction(extension, transaction -> {
                    transaction.delete("records", "missing", 0);
                    return null;
                }));
        assertThrows(
                PersistenceException.class,
                () -> store.inTransaction(extension, transaction -> {
                    transaction.delete("records", "missing", 1);
                    return null;
                }));

        ExtensionResponse response = new ExtensionResponse(payload, 1);
        assertEquals(response, store.inCommand(extension, "put", "command", digest, transaction -> response));
        assertEquals(
                response,
                store.recoverCommand(extension, "put", "command", digest).orElseThrow());
        assertTrue(store.recoverCommand(extension, "put", "absent", digest).isEmpty());
        assertThrows(
                PersistenceException.class,
                () -> store.inCommand(extension, "delete", "command", digest, transaction -> response));
        assertThrows(IllegalArgumentException.class, () -> store.recoverCommand(extension, "", "key", digest));
        assertThrows(IllegalArgumentException.class, () -> store.recoverCommand(extension, "put", "key", "bad-digest"));
    }

    @Test
    void turnJournalValidatesSchemasReceiptsAndRecoveredEffects() throws Exception {
        Aggregate aggregate = createAggregate("effects");
        journal.transition(aggregate.turn().id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        CanonicalPayload arguments = json.parse("{\"path\":\"notes.txt\"}");
        CanonicalPayload output = json.parse("{\"written\":true}");
        CorePayloads.ToolCall call = new CorePayloads.ToolCall("call-1", "builtin.files", "write", 2, arguments);
        journal.append(aggregate.turn().id(), "tool-call", CoreSchemas.TOOL_CALL, call, ItemStatus.COMPLETED);

        assertThrows(
                IllegalArgumentException.class,
                () -> journal.append(
                        aggregate.turn().id(),
                        "message",
                        CoreSchemas.ERROR,
                        new CorePayloads.Message(MessageRole.USER, "wrong schema", List.of(), Optional.empty()),
                        ItemStatus.COMPLETED));
        assertThrows(
                PersistenceException.class,
                () -> journal.append(
                        aggregate.turn().id(),
                        "tool-result",
                        CoreSchemas.TOOL_RESULT,
                        new CorePayloads.ToolResult(
                                "missing",
                                true,
                                output,
                                Optional.of(receipt("effect-missing", "write", arguments, output))),
                        ItemStatus.COMPLETED));
        assertThrows(
                PersistenceException.class,
                () -> journal.append(
                        aggregate.turn().id(),
                        "tool-result",
                        CoreSchemas.TOOL_RESULT,
                        new CorePayloads.ToolResult(
                                call.callId(),
                                true,
                                output,
                                Optional.of(receipt("effect-invalid", "other", arguments, output))),
                        ItemStatus.COMPLETED));

        EffectReceipt receipt = receipt("effect-1", call.toolName(), arguments, output);
        CorePayloads.ToolResult result = new CorePayloads.ToolResult(call.callId(), true, output, Optional.of(receipt));
        journal.append(aggregate.turn().id(), "tool-result", CoreSchemas.TOOL_RESULT, result, ItemStatus.COMPLETED);
        journal.append(aggregate.turn().id(), "tool-result", CoreSchemas.TOOL_RESULT, result, ItemStatus.COMPLETED);
        ToolCallRequest request = new ToolCallRequest(
                aggregate.turn().id(),
                call.callId(),
                new ToolIdentity(call.producerId(), call.toolName(), call.toolRevision()),
                arguments,
                receipt.idempotencyKey(),
                1);

        assertTrue(journal.recoverEffect(request).isPresent());
        assertTrue(journal.recoverEffect(new ToolCallRequest(
                        aggregate.turn().id(), "absent", request.tool(), arguments, "absent-effect", 1))
                .isEmpty());
        execute(
                "UPDATE CORE.EFFECT_RECEIPT SET RESULT_DIGEST = ? WHERE IDEMPOTENCY_KEY = ?",
                "0".repeat(64),
                "effect-1");
        assertThrows(PersistenceException.class, () -> journal.recoverEffect(request));
    }

    @Test
    void providerStateIsPositionedAfterCommittedItemsAndDigestChecked() throws Exception {
        Aggregate aggregate = createAggregate("provider-state");
        ProviderState state = new ProviderState("openai", "responses-v1", json.parse("{\"id\":\"response-1\"}"));
        ProviderStateService service = new ProviderStateService(database);

        assertTrue(service.latest(aggregate.thread().id(), "model").isEmpty());
        journal.saveProviderState(aggregate.turn().id(), "model", state, 123);
        ProviderStateService.StateSnapshot snapshot =
                service.latest(aggregate.thread().id(), "model").orElseThrow();

        assertEquals(state, snapshot.state());
        assertEquals(1, snapshot.throughSequence());
        assertEquals(123, snapshot.estimatedInputTokens());
        assertThrows(IllegalArgumentException.class, () -> new ProviderStateService.StateSnapshot(state, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProviderStateService.StateSnapshot(state, 1, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> journal.saveProviderState(aggregate.turn().id(), "model", state, -1));
        assertThrows(PersistenceException.class, () -> journal.saveProviderState(TurnId.random(), "model", state, 1));

        execute(
                "UPDATE CORE.PROVIDER_STATE SET PAYLOAD_DIGEST = ? WHERE TURN_ID = ?",
                "0".repeat(64),
                aggregate.turn().id().toString());
        assertThrows(
                PersistenceException.class,
                () -> service.latest(aggregate.thread().id(), "model"));
    }

    @Test
    void attachmentServiceRejectsIdentityReuseMetadataConflictsAndUnsafeBlobPaths() throws Exception {
        AttachmentService attachments = new AttachmentService(database, json, clock);
        byte[] content = "same-content".getBytes(StandardCharsets.UTF_8);
        AttachmentRpcContracts.BeginPayload payload = new AttachmentRpcContracts.BeginPayload(
                AttachmentScope.global(), "text/plain", ManagedWorktreePolicy.sha256(content), content.length);
        AttachmentMetadata metadata = attachments.store(
                payload.scope(),
                identity("attachment/internal/store", "attachment", 0, payload),
                payload.mediaType(),
                content);

        assertThrows(
                PersistenceException.class,
                () -> attachments.store(
                        payload.scope(),
                        identity("attachment/internal/store", "bad-revision", 1, payload),
                        payload.mediaType(),
                        content));
        assertThrows(
                PersistenceException.class,
                () -> attachments.store(
                        AttachmentScope.global(),
                        identity(
                                "attachment/internal/store",
                                "attachment",
                                0,
                                new AttachmentRpcContracts.BeginPayload(
                                        AttachmentScope.global(),
                                        "application/json",
                                        ManagedWorktreePolicy.sha256(content),
                                        content.length)),
                        "application/json",
                        content));
        assertThrows(
                PersistenceException.class,
                () -> attachments.store(
                        AttachmentScope.global(),
                        identity(
                                "attachment/internal/store",
                                "other-media",
                                0,
                                new AttachmentRpcContracts.BeginPayload(
                                        AttachmentScope.global(),
                                        "application/json",
                                        ManagedWorktreePolicy.sha256(content),
                                        content.length)),
                        "application/json",
                        content));
        assertThrows(PersistenceException.class, () -> attachments.read(AttachmentScope.global(), "f".repeat(64)));
        assertThrows(NullPointerException.class, () -> attachments.read(null, metadata.digest()));

        Path blob = database.dataRoot()
                .resolve("blobs/core")
                .resolve(metadata.digest().substring(0, 2))
                .resolve(metadata.digest() + ".blob");
        Files.writeString(blob, "same-contenx", StandardCharsets.UTF_8);
        assertThrows(PersistenceException.class, () -> attachments.read(AttachmentScope.global(), metadata.digest()));

        Files.write(blob, content);
        execute("UPDATE CORE.ATTACHMENT SET BLOB_PATH = ? WHERE DIGEST = ?", "../outside.blob", metadata.digest());
        assertThrows(PersistenceException.class, () -> attachments.read(AttachmentScope.global(), metadata.digest()));
    }

    @Test
    void dataRootAndTransactionFailuresFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new H2Database(temporaryDirectory.resolve("wrong")));
        assertThrows(NullPointerException.class, () -> new H2Database(null));
        assertTrue(database.healthy());

        H2Transactions transactions = new H2Transactions(database);
        assertThrows(
                IllegalStateException.class,
                () -> transactions.execute(connection -> {
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO CORE.EVENT (PRODUCER_ID, TOPIC, PAYLOAD, CREATED_AT) VALUES (?, ?, ?, ?)")) {
                        statement.setString(1, "test");
                        statement.setString(2, "rollback");
                        statement.setString(3, "{}");
                        statement.setObject(4, NOW.atOffset(ZoneOffset.UTC));
                        statement.executeUpdate();
                    }
                    throw new IllegalStateException("rollback");
                }));
        assertEquals(0, count("CORE.EVENT"));
    }

    private Aggregate createAggregate(String suffix) {
        Workspace workspace = core.listWorkspaces().stream().findFirst().orElseGet(() -> {
            CoreRpcContracts.WorkspaceCreatePayload payload =
                    new CoreRpcContracts.WorkspaceCreatePayload("持久化测试", temporaryDirectory.resolve("workspace"));
            return core.createWorkspace(
                    identity("workspace/create", "workspace", 0, payload), payload.name(), payload.root());
        });
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, suffix);
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + suffix, 0, threadPayload),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                suffix);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload turnPayload =
                com.javaclaw.server.TurnContractFixtures.payload(thread.id(), suffix);
        AgentTurn turn = core.startTurn(
                identity("turn/start", "turn-" + suffix, 0, turnPayload),
                com.javaclaw.server.TurnContractFixtures.request(thread.id(), budget(), message));
        return new Aggregate(workspace, thread, turn);
    }

    private EffectReceipt receipt(String key, String toolName, CanonicalPayload arguments, CanonicalPayload output) {
        return new EffectReceipt(key, toolName, arguments.sha256(), output.sha256(), NOW);
    }

    private CommandIdentity identity(String method, String key, long expectedRevision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, expectedRevision, json.encode(payload)), json);
    }

    private int count(String table) throws Exception {
        try (var connection = database.open();
                var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private void execute(String sql, String first, String second) throws Exception {
        try (var connection = database.open();
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, first);
            statement.setString(2, second);
            statement.executeUpdate();
        }
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }

    private record Aggregate(Workspace workspace, ConversationThread thread, AgentTurn turn) {}
}
