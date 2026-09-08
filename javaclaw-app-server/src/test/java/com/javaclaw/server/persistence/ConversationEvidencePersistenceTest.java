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
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ConversationEvidencePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationEvidencePersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporary;

    private H2Database database;
    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private Workspace workspace;

    @BeforeEach
    void initialize() {
        database = new H2Database(temporary.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        workspace = core.createWorkspace(identity("workspace/create", "workspace"), "证据", temporary.resolve("project"));
    }

    @Test
    void lateCompletionIsReadAfterEarlierCursorAndPostCompletionItemsAreFrozenOut() throws Exception {
        var older = create("older", true);
        var newer = create("newer", true);
        complete(newer);
        var evidence = new ServerConversationEvidencePort(database, json);
        long firstUpper = evidence.committedUpperBound(workspace.id());
        var first = evidence.scan(
                workspace.id(), new ConversationEvidencePort.Cursor(0, 0), firstUpper, NOW.minusSeconds(1), 200);
        assertEquals(
                List.of("newer"),
                first.evidence().stream()
                        .map(ConversationEvidencePort.Evidence::text)
                        .toList());
        complete(older);
        var second = evidence.scan(
                workspace.id(), first.next(), evidence.committedUpperBound(workspace.id()), NOW.minusSeconds(1), 200);
        assertEquals(
                List.of("older"),
                second.evidence().stream()
                        .map(ConversationEvidencePort.Evidence::text)
                        .toList());
        new H2Transactions(database)
                .execute(connection -> new ItemRepository(new TurnRepository())
                        .append(
                                connection,
                                new ItemRepository.ItemWrite(
                                        older.id(),
                                        "message",
                                        CoreSchemas.MESSAGE,
                                        "core",
                                        ItemStatus.COMPLETED,
                                        json.encode(new CorePayloads.Message(
                                                MessageRole.ASSISTANT, "late mutation", List.of(), Optional.empty())),
                                        NOW)));
        assertTrue(evidence.scan(
                        workspace.id(),
                        second.next(),
                        evidence.committedUpperBound(workspace.id()),
                        NOW.minusSeconds(1),
                        200)
                .evidence()
                .isEmpty());
        assertEquals(firstUpper + 1, evidence.committedUpperBound(workspace.id()));
    }

    @Test
    void derivedAndIncompleteTurnsAreExcludedAndRollbackDoesNotConsumeVisibleSequence() throws Exception {
        var derived = create("derived", false);
        complete(derived);
        var pending = create("pending", true);
        var evidence = new ServerConversationEvidencePort(database, json);
        assertTrue(evidence.scan(
                        workspace.id(),
                        new ConversationEvidencePort.Cursor(0, 0),
                        evidence.committedUpperBound(workspace.id()),
                        NOW.minusSeconds(1),
                        200)
                .evidence()
                .isEmpty());
        long before = evidence.committedUpperBound(workspace.id());
        assertThrows(
                IllegalStateException.class,
                () -> new H2Transactions(database).execute(connection -> {
                    var repository = new TurnRepository();
                    repository.transition(
                            connection, pending.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty(), NOW);
                    repository.transition(
                            connection, pending.id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty(), NOW);
                    throw new IllegalStateException("rollback terminal transaction");
                }));
        assertEquals(before, evidence.committedUpperBound(workspace.id()));
        assertEquals(
                TurnStatus.QUEUED, core.findTurn(pending.id()).orElseThrow().status());
        complete(pending);
        assertEquals(before + 1, evidence.committedUpperBound(workspace.id()));
    }

    @Test
    void oversizeIsAuditableAndBackfillIsIdempotent() throws Exception {
        var turn = create("oversize", true);
        journal.append(
                turn.id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.ASSISTANT, "x".repeat(48001), List.of(), Optional.empty()),
                ItemStatus.COMPLETED);
        complete(turn);
        new H2Transactions(database).execute(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM CORE.CONVERSATION_COMPLETION");
                statement.executeUpdate("DELETE FROM CORE.CONVERSATION_COMPLETION_HEAD");
            }
            return null;
        });
        var first = new ServerConversationEvidencePort(database, json);
        long upper = first.committedUpperBound(workspace.id());
        var second = new ServerConversationEvidencePort(database, json);
        assertEquals(upper, second.committedUpperBound(workspace.id()));
        var page =
                second.scan(workspace.id(), new ConversationEvidencePort.Cursor(0, 0), upper, NOW.minusSeconds(1), 1);
        assertTrue(page.hasMore());
        var remainder = second.scan(workspace.id(), page.next(), upper, NOW.minusSeconds(1), 1);
        assertTrue(remainder.evidence().getFirst().oversized());
        assertEquals("", remainder.evidence().getFirst().text());
        assertEquals(64, remainder.evidence().getFirst().sha256().length());
        assertFalse(remainder.hasMore());
    }

    private AgentTurn create(String text, boolean evidenceEligible) {
        var thread = core.createThread(
                identity("thread/create", text + "-thread"),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                text);
        var message = new CorePayloads.Message(MessageRole.USER, text, List.of(), Optional.empty());
        return core.startTurn(
                identity("turn/start", text + "-turn"),
                TurnContractFixtures.request(
                        thread.id(), new TurnBudget(4000, 1000, 0, 0, Duration.ofMinutes(1)), message),
                evidenceEligible);
    }

    private void complete(AgentTurn turn) {
        journal.transition(turn.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        journal.transition(turn.id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
    }

    private CommandIdentity identity(String operation, String key) {
        return CommandIdentity.from(operation, new WriteCommand(key, 0, json.parse("{}")), json);
    }
}
