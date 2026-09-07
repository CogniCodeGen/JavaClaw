package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.RolloutManifest;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RolloutIntegrityTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private RolloutExporter exporter;
    private Aggregate aggregate;
    private Path validRollout;
    private List<String> validLines;

    @BeforeEach
    void exportValidRollout() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        exporter = new RolloutExporter(database, json, clock);
        aggregate = createAggregate("primary");
        journal.append(
                aggregate.turn().id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.ASSISTANT, "完成", List.of(), Optional.empty()),
                ItemStatus.COMPLETED);
        validRollout = temporaryDirectory.resolve("valid.jsonl");
        exporter.export(aggregate.thread().id(), revision(aggregate.thread().id()), validRollout);
        validLines = Files.readAllLines(validRollout, StandardCharsets.UTF_8);
    }

    @Test
    void framingRejectsBlankMissingUnknownAndMisorderedRecords() throws Exception {
        assertInvalid("blank", lines -> lines.add(1, ""));
        assertInvalid("missing-type", lines -> lines.set(0, "{\"missing\":true}"));
        assertInvalid("unknown-type", lines -> lines.set(0, "{\"recordType\":\"unknown\"}"));
        assertInvalid("manifest-first", lines -> lines.addFirst(lines.removeLast()));
        assertInvalid("duplicate-manifest", lines -> lines.add(lines.getLast()));
        assertInvalid("missing-manifest", List::removeLast);
        assertInvalid(
                "null-manifest",
                lines -> lines.set(lines.size() - 1, "{\"manifest\":null,\"recordType\":\"manifest\"}"));
        Path missing = temporaryDirectory.resolve("missing.jsonl");
        assertThrows(PersistenceException.class, () -> exporter.verify(missing));
    }

    @Test
    void sequenceAndHashChainRejectEveryBrokenInvariant() throws Exception {
        assertInvalid("outer-sequence", lines -> mutate(lines, 1, record -> record.put("sequence", 9)));
        assertInvalid("null-item", lines -> mutate(lines, 1, record -> record.put("item", null)));
        assertInvalid(
                "inner-sequence",
                lines -> mutate(lines, 1, record -> nested(record, "item").put("sequence", 9)));
        assertInvalid("previous-hash", lines -> mutate(lines, 1, record -> record.put("previousHash", "0".repeat(64))));
        assertInvalid("line-hash", lines -> mutate(lines, 1, record -> record.put("hash", "0".repeat(64))));
    }

    @Test
    void manifestRejectsEveryMismatchedSummaryField() throws Exception {
        assertInvalid("item-count", lines -> mutateManifest(lines, manifest -> manifest.put("itemCount", 99)));
        assertInvalid("last-sequence", lines -> mutateManifest(lines, manifest -> manifest.put("lastSequence", 99)));
        assertInvalid(
                "final-chain",
                lines -> mutateManifest(lines, manifest -> manifest.put("finalChainHash", "0".repeat(64))));
        assertInvalid(
                "total-hash", lines -> mutateManifest(lines, manifest -> manifest.put("totalSha256", "0".repeat(64))));
    }

    @Test
    void exporterRejectsInvalidRevisionMissingThreadRevisionDriftAndOverwrite() throws Exception {
        Path output = temporaryDirectory.resolve("output.jsonl");
        assertThrows(
                IllegalArgumentException.class,
                () -> exporter.export(aggregate.thread().id(), 0, output));
        assertThrows(PersistenceException.class, () -> exporter.export(ThreadId.random(), 1, output));
        assertThrows(
                PersistenceException.class,
                () -> exporter.export(
                        aggregate.thread().id(), revision(aggregate.thread().id()) + 1, output));
        assertThrows(
                PersistenceException.class,
                () -> exporter.export(
                        aggregate.thread().id(), revision(aggregate.thread().id()), validRollout));
    }

    @Test
    void commandRecoveryRequiresExactIdentityAndExistingSnapshotIdentity() throws Exception {
        RolloutCommandService commands = new RolloutCommandService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        CoreRpcContracts.RolloutExportPayload payload = new CoreRpcContracts.RolloutExportPayload(
                aggregate.thread().id(), temporaryDirectory.resolve("cmd.jsonl"));
        long sourceRevision = revision(aggregate.thread().id());
        CommandIdentity identity = identity("thread/rollout/export", "rollout", sourceRevision, payload);
        RolloutManifest manifest = commands.export(identity, payload.threadId(), payload.outputFile());

        assertEquals(manifest, commands.export(identity, payload.threadId(), payload.outputFile()));
        CoreRpcContracts.RolloutExportPayload changed = new CoreRpcContracts.RolloutExportPayload(
                aggregate.thread().id(), temporaryDirectory.resolve("other.jsonl"));
        assertThrows(
                PersistenceException.class,
                () -> commands.export(
                        identity("thread/rollout/export", "rollout", sourceRevision, changed),
                        changed.threadId(),
                        changed.outputFile()));
        assertThrows(
                PersistenceException.class,
                () -> commands.export(
                        identity("other/export", "rollout", sourceRevision, payload),
                        payload.threadId(),
                        payload.outputFile()));
        assertThrows(
                PersistenceException.class,
                () -> commands.export(
                        identity("thread/rollout/export", "zero", 0, payload),
                        payload.threadId(),
                        payload.outputFile()));

        Aggregate other = createAggregate("other");
        CoreRpcContracts.RolloutExportPayload mismatched =
                new CoreRpcContracts.RolloutExportPayload(other.thread().id(), validRollout);
        assertThrows(
                PersistenceException.class,
                () -> commands.export(
                        identity(
                                "thread/rollout/export",
                                "mismatch",
                                revision(other.thread().id()),
                                mismatched),
                        mismatched.threadId(),
                        mismatched.outputFile()));
    }

    private void assertInvalid(String name, Consumer<List<String>> mutation) throws Exception {
        List<String> lines = new ArrayList<>(validLines);
        mutation.accept(lines);
        Path file = temporaryDirectory.resolve(name + ".jsonl");
        Files.write(file, lines, StandardCharsets.UTF_8);
        assertThrows(PersistenceException.class, () -> exporter.verify(file));
    }

    @SuppressWarnings("unchecked")
    private void mutate(List<String> lines, int index, Consumer<Map<String, Object>> mutation) {
        Map<String, Object> record = new LinkedHashMap<>(json.decode(json.parse(lines.get(index)), Map.class));
        mutation.accept(record);
        lines.set(index, json.encode(record).json());
    }

    private void mutateManifest(List<String> lines, Consumer<Map<String, Object>> mutation) {
        mutate(lines, lines.size() - 1, record -> mutation.accept(nested(record, "manifest")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    private Aggregate createAggregate(String suffix) {
        Workspace workspace = core.listWorkspaces().stream().findFirst().orElseGet(() -> {
            CoreRpcContracts.WorkspaceCreatePayload payload =
                    new CoreRpcContracts.WorkspaceCreatePayload("Rollout", temporaryDirectory.resolve("workspace"));
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
        return new Aggregate(thread, turn);
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    private long revision(ThreadId threadId) {
        return core.findThread(threadId).orElseThrow().revision();
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }

    private record Aggregate(ConversationThread thread, AgentTurn turn) {}
}
