package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.spi.*;
import com.javaclaw.infrastructure.agent.LegacyThreadImporter;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ThreadLifecycleIntegrationTest {
    @TempDir Path directory;

    @Test void forkCopiesOnlyCutoffAndSurvivesRecursiveSourceDeletion() {
        Fixture f = new Fixture(directory);
        RunScope source = new RunScope("w", "u", "parent");
        RunId first = f.complete(source, "first", "answer one", null);
        f.complete(source, "future secret", "answer two", null);
        RunScope child = new RunScope("w", "u", "child");
        f.complete(child, "delegated", "child answer", first);
        ThreadSnapshot branch = f.client.fork(source, TurnId.from(first), "branch");
        List<ThreadEvent> history = f.client.events(branch.scope(), 0);
        assertTrue(history.toString().contains("answer one"));
        assertFalse(history.toString().contains("future secret"));
        assertEquals(1, f.client.turns(branch.scope()).size());
        RunId copied = f.client.turns(branch.scope()).getFirst().id();
        assertNotEquals(first, copied);
        assertEquals(source.sessionId(), f.runs.find(copied).orElseThrow().request().attributes().get("memory.originThreadId").asText());
        f.client.delete(source);
        assertTrue(f.runs.find(first).isEmpty());
        assertEquals(ThreadStatus.DELETED, f.threads.require(child).status());
        assertEquals(ThreadStatus.ACTIVE, f.client.get(branch.scope()).status());
        assertFalse(f.runs.eventsAfter(copied, 0).isEmpty());
        assertThrows(IllegalStateException.class, () -> f.complete(source, "late", "resurrection", null));
    }

    @Test void rolloutAndMemoryProjectionRetryIndependentlyWithoutDuplicatingLines() throws Exception {
        Fixture f = new Fixture(directory);
        RunScope scope = new RunScope("w", "u", "session");
        f.complete(scope, "question", "answer", null);
        f.projector.drain(); // No workspace memory consumer yet: terminal stays pending.
        Path path = f.projector.path(scope);
        int lines = Files.readAllLines(path).size();
        assertTrue(lines >= 3);
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_thread_outbox WHERE published_at IS NULL", Integer.class));
        Set<String> episodes = new HashSet<>();
        f.projections.register(new ThreadProjectionListener() {
            @Override public boolean accepts(RunScope candidate) { return candidate.equals(scope); }
            @Override public void project(RunRequest request, ThreadEvent event) { episodes.add(event.turnId().value()); }
        });
        f.projector.drain(); f.projector.drain();
        assertEquals(lines, Files.readAllLines(path).size());
        assertEquals(1, episodes.size());
        Files.writeString(path, "{torn", java.nio.file.StandardOpenOption.APPEND);
        f.projector.project(scope);
        assertEquals(lines, Files.readAllLines(path).size());
        f.client.delete(scope);
        f.projector.drain();
        assertFalse(Files.exists(path));
        assertThrows(IllegalStateException.class, () -> f.client.events(scope, 0));
    }

    @Test void archiveRejectsNewTurnsAndConfigAndIdempotencyStayScoped() {
        Fixture f = new Fixture(directory);
        RunScope a = new RunScope("w", "u", "a"), b = new RunScope("w", "u", "b");
        RunId first = f.complete(a, "same", "a", null);
        RunId other = f.complete(b, "same", "b", null);
        assertNotEquals(first, other);
        assertEquals(first, f.runs.findByIdempotencyKey(a, "same").orElseThrow().snapshot().id());
        f.client.archive(a);
        assertThrows(IllegalStateException.class, () -> f.complete(a, "new", "x", null));
        assertEquals(List.of("b"), f.client.list("w", "u", false).stream().map(t -> t.id().value()).toList());
        f.client.resume(a);
        f.complete(a, "new", "resumed", null);
        assertEquals(2, f.client.turns(a).size());
    }

    @Test void legacyChatImportIsRestartableAndDoesNotInventSteps() {
        Fixture f = new Fixture(directory);
        f.jdbc.update("INSERT INTO chat_sessions(workspace_id,id,title,created_at) VALUES('w','old','old','2026-01-01 00:00:00')");
        f.jdbc.update("INSERT INTO chat_messages(workspace_id,session_id,position,role,content,timestamp,adopted) VALUES('w','old',0,'USER','old question','2026-01-01 00:00:00',FALSE)");
        f.jdbc.update("INSERT INTO chat_messages(workspace_id,session_id,position,role,content,timestamp,adopted) VALUES('w','old',1,'ASSISTANT','old answer','2026-01-01 00:01:00',FALSE)");
        var importer = new LegacyThreadImporter(f.jdbc, f.tx, f.json, f.runs);
        importer.migrate(); importer.migrate();
        var turns = f.client.turns(new RunScope("w", "local-user", "old"));
        assertEquals(1, turns.size());
        assertEquals("old answer", turns.getFirst().output().path("text").asText());
        assertTrue(new RunStepQuery(f.runs).steps(turns.getFirst().id()).isEmpty());
        assertTrue(f.runs.find(turns.getFirst().id()).orElseThrow().request().attributes()
                .get("framework.legacyStepHistoryUnavailable").asBoolean());
    }

    @Test void graphMutationsAreIdempotentAndForkHonorsTheChosenEventCutoff() {
        Fixture f = new Fixture(directory);
        RunScope scope = new RunScope("w", "u", "graph");
        f.complete(scope, "initial", "one", null);
        var before = f.json.createObjectNode().put("fact", "user corrected value");
        long sequence = f.threads.appendOnce(scope, "edit-1", "memory/graph-snapshot", before);
        assertEquals(sequence, f.threads.appendOnce(scope, "edit-1", "memory/graph-snapshot", before));
        RunId cutoff = f.complete(scope, "acknowledge", "two", null);
        f.threads.appendOnce(scope, "edit-2", "memory/graph-snapshot", f.json.createObjectNode().put("fact", "future edit"));
        var branch = f.client.fork(scope, TurnId.from(cutoff), "snapshot");
        List<ThreadEvent> mutations = f.client.events(branch.scope(), 0).stream()
                .filter(event -> event.type().equals("memory/graph-snapshot")).toList();
        assertEquals(1, mutations.size());
        assertEquals("user corrected value", mutations.getFirst().payload().path("fact").asText());
        f.client.delete(scope);
        assertThrows(IllegalStateException.class,
                () -> f.threads.appendOnce(scope, "late", "memory/graph-snapshot", before));
    }

    @Test void unfinishedForkAndDeletionCanResumeAfterProcessFailure() {
        Fixture f = new Fixture(directory);
        RunScope source = new RunScope("w", "u", "recover-lifecycle");
        RunId cutoff = f.complete(source, "question", "answer", null);
        var partial = f.threads.fork(source, TurnId.from(cutoff), "interrupted fork");
        assertEquals(ThreadStatus.FORKING, partial.status());
        assertFalse(f.client.list("w", "u", true).contains(partial));
        var replayed = new ArrayList<RunScope>();
        var cleaned = new ArrayList<RunScope>();
        f.lifecycle.register(new ThreadLifecycleListener() {
            @Override public boolean accepts(RunScope scope) { return true; }
            @Override public void forked(ThreadSnapshot target, List<ThreadEvent> events) {
                replayed.add(target.scope()); assertTrue(events.toString().contains("answer"));
            }
            @Override public void deleting(RunScope scope) { cleaned.add(scope); }
        });
        assertEquals(ThreadStatus.ACTIVE, f.client.resume(partial.scope()).status());
        assertEquals(List.of(partial.scope()), replayed);
        f.threads.markDeleting(source); // Simulate termination before resource cleanup.
        assertThrows(IllegalStateException.class, () -> f.complete(source, "late", "bad", null));
        f.client.delete(source);
        f.client.delete(source); // Also finish cleanup when the durable tombstone already exists.
        assertEquals(List.of(source, source), cleaned);
        assertEquals(ThreadStatus.DELETED, f.threads.require(source).status());
        assertEquals(ThreadStatus.ACTIVE, f.client.get(partial.scope()).status());
    }

    @Test void forkCannotCopyLaterResultsFromAnAlreadyQueuedTurn() {
        Fixture f = new Fixture(directory);
        RunScope source = new RunScope("w", "u", "queued-cutoff");
        RunId first = f.create(source, "first", null);
        RunId queued = f.create(source, "queued question", null);
        f.finish(first, "first answer");
        f.finish(queued, "future answer must stay private");
        var oldConfiguration = f.threads.require(source).configuration();
        f.client.configure(source, new ThreadConfiguration("future-model", oldConfiguration.workingDirectory(), "host",
                oldConfiguration.permissions(), oldConfiguration.budget(), Map.of()));
        var branch = f.client.fork(source, TurnId.from(first), "past");
        assertEquals(1, f.client.turns(branch.scope()).size());
        assertFalse(f.client.events(branch.scope(), 0).toString().contains("future answer"));
        assertFalse(f.client.events(branch.scope(), 0).toString().contains("queued question"));
        assertNotEquals("future-model", branch.configuration().modelPolicyRef());
    }

    @Test void childIdentityCannotBeReassignedAndDeletingAnotherUserPreservesDesktopHistory() {
        Fixture f = new Fixture(directory);
        RunScope parent = new RunScope("w", "local-user", "parent");
        RunScope other = new RunScope("w", "local-user", "other");
        RunScope child = new RunScope("w", "local-user", "child");
        RunId first = f.complete(parent, "parent", "one", null);
        RunId second = f.complete(other, "other", "two", null);
        f.complete(child, "work", "result", first);
        assertThrows(IllegalArgumentException.class, () -> f.complete(child, "hijack", "wrong", second));
        f.jdbc.update("INSERT INTO chat_sessions(workspace_id,id,title,created_at) VALUES('w','parent','local history','2026-01-01')");
        RunScope differentUser = new RunScope("w", "remote-user", "parent");
        f.complete(differentUser, "separate", "isolated", null);
        f.client.delete(differentUser);
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE id='parent'", Integer.class));
        assertEquals(ThreadStatus.ACTIVE, f.client.get(parent).status());
    }

    @Test void forkUsesTheTerminalCutoffEvenAfterACancelledModelSettlesItsBill() {
        Fixture f = new Fixture(directory);
        RunScope scope = new RunScope("w", "u", "late-model");
        RunId id = f.create(scope, "question", null);
        var sink = StepEvents.durableSink(f.runs, id);
        StepId step = StepId.random();
        StepEvents.started(sink, step, AgentStep.Kind.MODEL, f.json.createObjectNode().put("prompt", "question"), null);
        f.runs.append(id, Set.of(RunState.CREATED), RunState.CANCELLED,
                f.draft("core.run.cancelled", f.json.createObjectNode().put("detail", "cancelled")), null, "cancelled");
        long cutoff = f.runs.find(id).orElseThrow().snapshot().lastSequence();
        StepEvents.completed(sink, step, f.json.createObjectNode().put("text", "late answer"),
                f.json.createObjectNode().put("inputTokens", 7));
        assertTrue(f.runs.find(id).orElseThrow().snapshot().lastSequence() > cutoff);
        var branch = f.client.fork(scope, TurnId.from(id), "cancelled cutoff");
        var copied = f.client.turns(branch.scope()).getFirst();
        assertEquals(cutoff, copied.lastSequence());
        assertEquals(cutoff, f.runs.eventsAfter(copied.id(), 0).getLast().sequence());
        assertEquals(RunState.CANCELLED, copied.state());
        assertFalse(f.client.events(branch.scope(), 0).toString().contains("late answer"));
    }

    @Test void childCreationCannotCrossTheParentDeletionFence() throws Exception {
        Fixture f = new Fixture(directory);
        RunScope parent = new RunScope("w", "u", "delete-race");
        RunScope child = new RunScope("w", "u", "late-child");
        f.client.start(ThreadStartRequest.root(parent, "parent"));
        var started = new java.util.concurrent.CountDownLatch(1);
        var creation = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<ThreadSnapshot>>();
        new org.springframework.transaction.support.TransactionTemplate(f.tx).executeWithoutResult(transaction -> {
            f.threads.lock(parent);
            creation.set(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                started.countDown();
                return f.threads.create(new ThreadStartRequest(child, "child", ThreadConfiguration.DEFAULT, parent, null));
            }));
            try {
                assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> creation.get().get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            f.threads.markDeleting(parent);
        });
        var rejected = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> creation.get().get(3, java.util.concurrent.TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, rejected.getCause());
        f.client.delete(parent);
        assertTrue(f.threads.find(child).isEmpty());
    }

    private static final class Fixture {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager tx;
        final JdbcRunStore runs;
        final JdbcThreadStore threads;
        final ThreadProjectionRegistry projections = new ThreadProjectionRegistry();
        final ThreadLifecycleRegistry lifecycle = new ThreadLifecycleRegistry();
        final ThreadRolloutProjector projector;
        final ThreadClient client;
        Fixture(Path directory) {
            var data = new DriverManagerDataSource("jdbc:h2:mem:threads-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(data).initialize();
            jdbc = new JdbcTemplate(data); tx = new DataSourceTransactionManager(data);
            runs = new JdbcRunStore(jdbc, tx, json, Clock.systemUTC()); threads = runs.threads();
            projector = new ThreadRolloutProjector(jdbc, threads, json, directory, projections);
            AgentClient cancellation = new AgentClient() {
                @Override public RunHandle start(RunRequest request) { throw new UnsupportedOperationException(); }
                @Override public RunHandle resume(RunId id, ResumeCommand command) { throw new UnsupportedOperationException(); }
                @Override public RunSnapshot get(RunId id) { return runs.find(id).orElseThrow().snapshot(); }
                @Override public boolean cancel(RunId id, CancelReason reason) {
                    return runs.append(id, Set.of(RunState.CREATED, RunState.RUNNING), RunState.CANCELLED,
                            draft("core.run.cancelled", json.createObjectNode()), null, reason.detail()).isPresent();
                }
            };
            client = new DefaultThreadClient(threads, runs, cancellation, lifecycle, projector);
        }
        RunId complete(RunScope scope, String input, String output, RunId parent) {
            RunId id = create(scope, input, parent);
            finish(id, output);
            return id;
        }
        RunId create(RunScope scope, String input, RunId parent) {
            RunRequest request = runs.prepare(RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                    .profile(RunProfileRef.latest("chat")).scope(scope).source(InvocationSource.chat())
                    .input(InputBlock.text(input)).permissionCeiling(PermissionSet.UNRESTRICTED)
                    .linkage(new RunLinkage(parent, null, null)).idempotencyKey(input).build());
            RunId id = RunId.random();
            runs.create(id, request, "plan", draft("core.run.created", json.createObjectNode()));
            return id;
        }
        void finish(RunId id, String output) {
            var result = json.createObjectNode().put("text", output);
            runs.append(id, Set.of(RunState.CREATED), RunState.COMPLETED,
                    draft("core.run.completed", json.createObjectNode().set("output", result)), result, null);
        }
        private RunEventDraft draft(String type, com.fasterxml.jackson.databind.JsonNode payload) {
            return new RunEventDraft(type, 1, "test", null, null, payload);
        }
    }
}
