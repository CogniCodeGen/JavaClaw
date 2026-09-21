package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ThreadStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** H2 is the authoritative thread journal; filesystem and graph data are projections. */
public final class JdbcThreadStore implements ThreadStore, com.javaclaw.framework.spi.ThreadJournal {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcThreadStore(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                           ObjectMapper json, Clock clock) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions); this.json = json; this.clock = clock;
    }

    @Override public ThreadSnapshot create(ThreadStartRequest request) {
        try { return tx.execute(status -> {
            RunScope scope = request.scope();
            Optional<ThreadSnapshot> existing = find(scope);
            if (existing.isPresent()) {
                requireUsable(existing.get());
                if (request.parentScope() != null && !new ThreadId(request.parentScope().sessionId()).equals(existing.get().parentThreadId()))
                    throw new IllegalArgumentException("child thread belongs to a different parent");
                return existing.get();
            }
            if (request.parentScope() != null) requireUsable(lock(request.parentScope()));
            if (request.parentTurnId() != null) {
                RunScope parent = request.parentScope();
                int count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE run_id=? AND workspace_id=? AND user_id=? AND session_id=?",
                        Integer.class, request.parentTurnId().value(), parent.workspaceId(), parent.userId(), parent.sessionId());
                if (count != 1) throw new IllegalArgumentException("parent turn does not belong to parent thread");
            }
            long now = clock.millis();
            jdbc.update("""
                    INSERT INTO agent_threads(workspace_id,user_id,thread_id,title,status,
                      configuration_json,parent_thread_id,parent_turn_id,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, scope.workspaceId(), scope.userId(), scope.sessionId(), request.title(),
                    ThreadStatus.ACTIVE.name(), write(request.configuration()),
                    request.parentScope() == null ? null : request.parentScope().sessionId(),
                    request.parentTurnId() == null ? null : request.parentTurnId().value(), now, now);
            append(scope, "thread/started", null, json.valueToTree(request));
            return require(scope);
        }); } catch (org.springframework.dao.DuplicateKeyException race) {
            ThreadSnapshot existing = find(request.scope()).orElseThrow(() -> race);
            requireUsable(existing);
            if (request.parentScope() != null && !new ThreadId(request.parentScope().sessionId()).equals(existing.parentThreadId()))
                throw new IllegalArgumentException("child thread belongs to a different parent", race);
            return existing;
        }
    }

    @Override public Optional<ThreadSnapshot> find(RunScope scope) {
        return jdbc.query("SELECT * FROM agent_threads WHERE workspace_id=? AND user_id=? AND thread_id=?",
                this::readThread, scope.workspaceId(), scope.userId(), scope.sessionId()).stream().findFirst();
    }
    public ThreadSnapshot require(RunScope scope) {
        return find(scope).orElseThrow(() -> new NoSuchElementException("thread not found: " + scope));
    }
    public ThreadSnapshot lock(RunScope scope) {
        return jdbc.query("SELECT * FROM agent_threads WHERE workspace_id=? AND user_id=? AND thread_id=? FOR UPDATE",
                this::readThread, scope.workspaceId(), scope.userId(), scope.sessionId()).stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("thread not found: " + scope));
    }
    public static void requireUsable(ThreadSnapshot thread) {
        if (thread.status() == ThreadStatus.DELETING || thread.status() == ThreadStatus.DELETED)
            throw new IllegalStateException("thread has been deleted: " + thread.id());
    }
    @Override public List<ThreadSnapshot> list(String workspace, String user, boolean archived) {
        return jdbc.query("SELECT * FROM agent_threads WHERE workspace_id=? AND user_id=? AND status "
                        + (archived ? "IN ('ACTIVE','ARCHIVED')" : "= 'ACTIVE'") + " ORDER BY updated_at DESC,thread_id",
                this::readThread, workspace, user);
    }
    /** Hidden lifecycle operations are reconciled when the owning workspace opens again. */
    public List<ThreadSnapshot> pendingLifecycle(String workspace, String user) {
        return jdbc.query("SELECT * FROM agent_threads WHERE workspace_id=? AND user_id=? "
                        + "AND status IN ('FORKING','DELETING','DELETED') ORDER BY created_at,thread_id",
                this::readThread, workspace, user);
    }
    @Override public ThreadSnapshot setStatus(RunScope scope, ThreadStatus next) {
        return tx.execute(status -> {
            ThreadSnapshot current = lock(scope); requireUsable(current);
            if (current.status() == ThreadStatus.FORKING && next != ThreadStatus.ACTIVE)
                throw new IllegalStateException("finish restoring the fork before changing its lifecycle");
            if (next == ThreadStatus.ARCHIVED && hasActive(scope))
                throw new IllegalStateException("finish or interrupt the active turn before archiving");
            jdbc.update("UPDATE agent_threads SET status=?,updated_at=? WHERE workspace_id=? AND user_id=? AND thread_id=?",
                    next.name(), clock.millis(), scope.workspaceId(), scope.userId(), scope.sessionId());
            append(scope, next == ThreadStatus.ACTIVE ? "thread/resumed" : "thread/archived", null,
                    json.createObjectNode().put("status", next.name()));
            return require(scope);
        });
    }
    @Override public ThreadSnapshot configure(RunScope scope, ThreadConfiguration configuration) {
        return tx.execute(status -> {
            requireUsable(lock(scope));
            jdbc.update("UPDATE agent_threads SET configuration_json=?,updated_at=? WHERE workspace_id=? AND user_id=? AND thread_id=?",
                    write(configuration), clock.millis(), scope.workspaceId(), scope.userId(), scope.sessionId());
            append(scope, "thread/configured", null, json.valueToTree(configuration));
            return require(scope);
        });
    }
    public boolean hasActive(RunScope scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE workspace_id=? AND user_id=? AND session_id=? "
                + "AND state NOT IN ('COMPLETED','FAILED','CANCELLED')", Integer.class,
                scope.workspaceId(), scope.userId(), scope.sessionId()) > 0;
    }
    @Override public List<RunSnapshot> turns(RunScope scope) {
        requireUsable(require(scope));
        return jdbc.query("SELECT * FROM agent_runs WHERE workspace_id=? AND user_id=? AND session_id=? ORDER BY created_at,run_id",
                (row, index) -> new RunSnapshot(new RunId(row.getString("run_id")), RunState.valueOf(row.getString("state")),
                        row.getString("execution_plan_id"), row.getLong("last_sequence"),
                        Instant.ofEpochMilli(row.getLong("created_at")), Instant.ofEpochMilli(row.getLong("updated_at")),
                        tree(row.getString("output_json")), row.getString("error_text"), row.getLong("version")),
                scope.workspaceId(), scope.userId(), scope.sessionId());
    }
    @Override public List<ThreadEvent> events(RunScope scope, long afterSequence) {
        requireUsable(require(scope));
        return journal(scope, afterSequence, Long.MAX_VALUE);
    }
    public List<ThreadEvent> journal(RunScope scope, long after, long through) {
        return jdbc.query("SELECT * FROM agent_thread_events WHERE workspace_id=? AND user_id=? AND thread_id=? "
                        + "AND event_sequence>? AND event_sequence<=? ORDER BY event_sequence",
                (row, index) -> new ThreadEvent(scope, row.getLong("event_sequence"),
                        Instant.ofEpochMilli(row.getLong("timestamp_ms")), row.getString("type"),
                        row.getString("turn_id") == null ? null : new TurnId(row.getString("turn_id")),
                        tree(row.getString("payload_json"))), scope.workspaceId(), scope.userId(), scope.sessionId(), after, through);
    }
    /** Caller must share the surrounding JDBC transaction. */
    public void append(RunScope scope, String type, TurnId turn, JsonNode payload) {
        append(scope, type, turn, payload, clock.instant());
    }
    private void append(RunScope scope, String type, TurnId turn, JsonNode payload, Instant occurredAt) {
        ThreadSnapshot current = lock(scope);
        requireUsable(current);
        long sequence = current.lastSequence() + 1;
        jdbc.update("INSERT INTO agent_thread_events VALUES(?,?,?,?,?,?,?,?)", scope.workspaceId(), scope.userId(),
                scope.sessionId(), sequence, occurredAt.toEpochMilli(), type, turn == null ? null : turn.value(), write(payload));
        jdbc.update("INSERT INTO agent_thread_outbox VALUES(?,?,?,?,?,NULL)", scope.workspaceId(), scope.userId(),
                scope.sessionId(), sequence, current.generation());
        jdbc.update("UPDATE agent_threads SET last_sequence=?,updated_at=? WHERE workspace_id=? AND user_id=? AND thread_id=?",
                sequence, clock.millis(), scope.workspaceId(), scope.userId(), scope.sessionId());
    }
    public void appendRun(RunScope scope, RunEventEnvelope event, RunRequest createdRequest) {
        var payload = json.createObjectNode();
        payload.set("event", json.valueToTree(event));
        if (createdRequest != null) payload.set("request", json.valueToTree(createdRequest));
        String type = event.type().startsWith("core.run.")
                ? "turn/" + event.type().substring("core.run.".length())
                : event.type().startsWith("core.step.") ? "step/" + event.type().substring("core.step.".length()) : event.type();
        append(scope, type, new TurnId(event.runId()), payload);
    }
    @Override public long appendOnce(RunScope scope, String mutationId, String type, JsonNode payload) {
        if (mutationId == null || mutationId.isBlank() || !type.startsWith("memory/"))
            throw new IllegalArgumentException("invalid graph mutation identity or type");
        return tx.execute(status -> {
            requireUsable(lock(scope));
            List<Long> existing = jdbc.queryForList("SELECT event_sequence FROM agent_thread_mutations WHERE workspace_id=? "
                            + "AND user_id=? AND thread_id=? AND mutation_id=?", Long.class,
                    scope.workspaceId(), scope.userId(), scope.sessionId(), mutationId);
            if (!existing.isEmpty()) return existing.getFirst();
            append(scope, type, null, payload);
            long sequence = require(scope).lastSequence();
            jdbc.update("INSERT INTO agent_thread_mutations VALUES(?,?,?,?,?)", scope.workspaceId(), scope.userId(),
                    scope.sessionId(), mutationId, sequence);
            return sequence;
        });
    }
    @Override public ThreadSnapshot fork(RunScope source, TurnId throughTurn, String title) {
        return tx.execute(status -> {
            ThreadSnapshot original = lock(source); requireUsable(original);
            RunSnapshot cutoffTurn = turns(source).stream().filter(turn -> turn.id().value().equals(throughTurn.value()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("fork turn does not belong to source thread"));
            if (!cutoffTurn.state().terminal()) throw new IllegalArgumentException("fork requires a terminal turn");
            Long cutoff = jdbc.queryForObject("SELECT MAX(event_sequence) FROM agent_thread_events WHERE workspace_id=? "
                            + "AND user_id=? AND thread_id=? AND turn_id=? AND type IN ('turn/completed','turn/failed','turn/cancelled')",
                    Long.class, source.workspaceId(), source.userId(), source.sessionId(), throughTurn.value());
            if (cutoff == null) throw new IllegalStateException("legacy turn has no forkable event history");
            List<ThreadEvent> history = journal(source, 0, cutoff);
            ThreadConfiguration forkConfiguration = original.configuration();
            for (ThreadEvent event : history) {
                JsonNode config = event.type().equals("thread/started") ? event.payload().get("configuration")
                        : event.type().equals("thread/configured") ? event.payload() : null;
                if (config != null && !config.isNull()) forkConfiguration = read(config.toString(), ThreadConfiguration.class);
            }
            RunScope target = new RunScope(source.workspaceId(), source.userId(), ThreadId.random().value());
            create(new ThreadStartRequest(target, title, forkConfiguration, null, null));
            jdbc.update("UPDATE agent_threads SET status='FORKING',fork_source_thread_id=?,fork_sequence=? "
                            + "WHERE workspace_id=? AND user_id=? AND thread_id=?",
                    source.sessionId(), cutoff, target.workspaceId(), target.userId(), target.sessionId());
            for (ThreadEvent event : new ThreadForkCopier(jdbc, json).copy(history, target))
                append(target, event.type(), event.turnId(), event.payload(), event.timestamp());
            append(target, "thread/forked", null, json.createObjectNode().put("sourceThreadId", source.sessionId()).put("cutoff", cutoff));
            return require(target);
        });
    }
    @Override public List<RunScope> markDeleting(RunScope root) {
        return tx.execute(status -> {
            ThreadSnapshot current = lock(root);
            if (current.status() == ThreadStatus.DELETED) return List.of();
            List<RunScope> pending = new ArrayList<>(); pending.add(root);
            for (int index = 0; index < pending.size(); index++) {
                RunScope scope = pending.get(index);
                lock(scope); // Serializes descendant creation with enumeration and tombstoning.
                jdbc.query("SELECT thread_id FROM agent_threads WHERE workspace_id=? AND user_id=? AND parent_thread_id=? "
                                + "AND status<>'DELETED'", (row, rowIndex) -> new RunScope(scope.workspaceId(), scope.userId(), row.getString(1)),
                        scope.workspaceId(), scope.userId(), scope.sessionId()).forEach(pending::add);
                jdbc.update("UPDATE agent_threads SET status='DELETING',generation=generation+1,updated_at=? "
                                + "WHERE workspace_id=? AND user_id=? AND thread_id=?",
                        clock.millis(), scope.workspaceId(), scope.userId(), scope.sessionId());
            }
            return List.copyOf(pending);
        });
    }
    @Override public void purge(RunScope scope) {
        tx.executeWithoutResult(status -> {
            ThreadSnapshot thread = lock(scope);
            if (thread.status() != ThreadStatus.DELETING && thread.status() != ThreadStatus.DELETED)
                throw new IllegalStateException("thread must be tombstoned before purging");
            List<String> ids = jdbc.queryForList("SELECT run_id FROM agent_runs WHERE workspace_id=? AND user_id=? AND session_id=?",
                    String.class, scope.workspaceId(), scope.userId(), scope.sessionId());
            for (String id : ids) {
                for (String table : List.of("agent_run_events", "agent_run_outbox", "agent_extension_state", "agent_runs"))
                    jdbc.update("DELETE FROM " + table + " WHERE run_id=?", id);
            }
            for (String table : List.of("agent_thread_events", "agent_thread_outbox", "agent_thread_mutations"))
                jdbc.update("DELETE FROM " + table + " WHERE workspace_id=? AND user_id=? AND thread_id=?",
                        scope.workspaceId(), scope.userId(), scope.sessionId());
            // Legacy desktop business tables belong to local-user and have no user column.
            if (scope.userId().equals("local-user")) {
                jdbc.update("DELETE FROM chat_messages WHERE workspace_id=? AND session_id=?", scope.workspaceId(), scope.sessionId());
                jdbc.update("DELETE FROM chat_sessions WHERE workspace_id=? AND id=?", scope.workspaceId(), scope.sessionId());
                jdbc.update("DELETE FROM workflow_checkpoints WHERE workspace_id=? AND run_id IN "
                                + "(SELECT id FROM workflow_runs WHERE workspace_id=? AND thread_id=?)",
                        scope.workspaceId(), scope.workspaceId(), scope.sessionId());
                jdbc.update("DELETE FROM workflow_runs WHERE workspace_id=? AND thread_id=?", scope.workspaceId(), scope.sessionId());
                jdbc.update("DELETE FROM workflow_threads WHERE workspace_id=? AND thread_id=?", scope.workspaceId(), scope.sessionId());
            }
            jdbc.update("UPDATE agent_threads SET status='DELETED',title='',configuration_json=?,active_turn_id=NULL "
                            + "WHERE workspace_id=? AND user_id=? AND thread_id=?", write(ThreadConfiguration.DEFAULT),
                    scope.workspaceId(), scope.userId(), scope.sessionId());
        });
    }
    private ThreadSnapshot readThread(ResultSet row, int index) throws SQLException {
        RunScope scope = new RunScope(row.getString("workspace_id"), row.getString("user_id"), row.getString("thread_id"));
        return new ThreadSnapshot(new ThreadId(scope.sessionId()), scope, row.getString("title"),
                ThreadStatus.valueOf(row.getString("status")), read(row.getString("configuration_json"), ThreadConfiguration.class),
                threadId(row.getString("parent_thread_id")), row.getString("parent_turn_id") == null ? null : new TurnId(row.getString("parent_turn_id")),
                threadId(row.getString("fork_source_thread_id")), row.getLong("fork_sequence"), row.getLong("generation"),
                row.getLong("last_sequence"), Instant.ofEpochMilli(row.getLong("created_at")), Instant.ofEpochMilli(row.getLong("updated_at")));
    }
    private static ThreadId threadId(String id) { return id == null ? null : new ThreadId(id); }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private JsonNode tree(String value) {
        if (value == null) return null;
        try { return json.readTree(value); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
