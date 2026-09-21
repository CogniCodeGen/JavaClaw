package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.framework.store.JdbcThreadStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Additive, idempotent import; original chat rows remain the migration backup. */
public final class LegacyThreadImporter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final JdbcRunStore runs;
    private final JdbcThreadStore threads;
    public LegacyThreadImporter(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                ObjectMapper json, JdbcRunStore runs) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions); this.json = json;
        this.runs = runs; this.threads = runs.threads();
    }
    public void migrate() {
        Map<RunScope, RunRequest> sources = new LinkedHashMap<>();
        for (RunRequest request : jdbc.query("SELECT request_json FROM agent_runs ORDER BY created_at,run_id",
                (row, index) -> readRequest(row.getString(1)))) sources.putIfAbsent(request.scope(), request);
        for (RunRequest request : sources.values()) {
            tx.executeWithoutResult(status -> {
                RunScope scope = request.scope();
                var existing = threads.find(scope);
                if (existing.isPresent() && existing.get().status() != ThreadStatus.ACTIVE) return;
                ensureThread(request, new HashSet<>());
                List<String> ids = jdbc.queryForList("SELECT run_id FROM agent_runs WHERE workspace_id=? AND user_id=? AND session_id=? ORDER BY created_at,run_id",
                        String.class, scope.workspaceId(), scope.userId(), scope.sessionId());
                for (String id : ids) {
                    int count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_thread_events WHERE workspace_id=? AND user_id=? AND thread_id=? AND turn_id=?",
                            Integer.class, scope.workspaceId(), scope.userId(), scope.sessionId(), id);
                    if (count > 0) continue;
                    RunRequest storedRequest = runs.find(new RunId(id)).orElseThrow().request();
                    for (RunEventEnvelope event : runs.eventsAfter(new RunId(id), 0))
                        threads.appendRun(scope, event, event.type().equals("core.run.created") ? storedRequest : null);
                }
            });
        }
        List<LegacySession> sessions = jdbc.query("SELECT workspace_id,id,title FROM chat_sessions ORDER BY created_at,id",
                (row, index) -> new LegacySession(new RunScope(row.getString(1), "local-user", row.getString(2)), row.getString(3)));
        for (LegacySession session : sessions) importChat(session);
    }
    private void ensureThread(RunRequest request, Set<RunScope> visited) {
        if (threads.find(request.scope()).isPresent()) return;
        if (!visited.add(request.scope())) throw new IllegalStateException("cyclic legacy parent linkage");
        RunId parentId = request.linkage().parentRunId();
        RunScope parent = null;
        if (parentId != null) {
            var stored = runs.find(parentId).orElse(null);
            if (stored != null) {
                RunScope candidate = stored.request().scope();
                if (!candidate.equals(request.scope()) && candidate.workspaceId().equals(request.scope().workspaceId())
                        && candidate.userId().equals(request.scope().userId())) {
                    ensureThread(stored.request(), visited);
                    parent = candidate;
                }
            }
        }
        threads.create(new ThreadStartRequest(request.scope(), request.source().kind(), ThreadConfiguration.DEFAULT,
                parent, parent == null ? null : TurnId.from(parentId)));
    }
    private void importChat(LegacySession session) {
        tx.executeWithoutResult(status -> {
            var existing = threads.find(session.scope());
            if (existing.isPresent() && existing.get().status() != ThreadStatus.ACTIVE) return;
            threads.create(ThreadStartRequest.root(session.scope(), session.title()));
            List<LegacyMessage> messages = jdbc.query("SELECT position,role,content,delivery_state FROM chat_messages WHERE workspace_id=? AND session_id=? ORDER BY position",
                    (row, index) -> new LegacyMessage(row.getInt(1), row.getString(2), row.getString(3), row.getString(4)),
                    session.scope().workspaceId(), session.scope().sessionId());
            Map<MessagePair, Integer> imported = new HashMap<>();
            for (RunSnapshot turn : threads.turns(session.scope())) {
                if (turn.output() == null) continue;
                var request = runs.find(turn.id()).orElseThrow().request();
                String prompt = request.inputs().stream().filter(input -> input.type().equals("core.text"))
                        .map(input -> input.data().path("text").asText()).collect(java.util.stream.Collectors.joining("\n"));
                imported.merge(new MessagePair(prompt, turn.output().path("text").asText()), 1, Integer::sum);
            }
            LegacyMessage user = null;
            for (LegacyMessage message : messages) {
                if (message.role().equals("USER")) { user = message; continue; }
                if (!message.role().equals("ASSISTANT") || user == null || message.content() == null) continue;
                MessagePair pair = new MessagePair(user.content() == null ? "" : user.content(), message.content());
                int matching = imported.getOrDefault(pair, 0);
                if (matching > 0) { imported.put(pair, matching - 1); user = null; continue; }
                String key = "legacy-chat:" + user.position();
                if (runs.findByIdempotencyKey(session.scope(), key).isPresent()) { user = null; continue; }
                RunId id = new RunId(UUID.nameUUIDFromBytes((session.scope() + ":" + key).getBytes(StandardCharsets.UTF_8)).toString());
                RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                        .profile(RunProfileRef.latest("chat")).source(new InvocationSource("legacy", "chat-history"))
                        .scope(session.scope()).input(InputBlock.text(user.content() == null ? "" : user.content()))
                        .idempotencyKey(key).permissionCeiling(PermissionSet.NONE)
                        .attributes(Map.of("framework.legacyStepHistoryUnavailable", json.getNodeFactory().booleanNode(true)))
                        .build();
                runs.create(id, request, "legacy-history-only", event("core.run.created", json.createObjectNode().put("legacy", true)));
                var output = json.createObjectNode().put("text", message.content());
                RunState state = "FAILED".equals(message.delivery()) ? RunState.FAILED
                        : "CANCELLED".equals(message.delivery()) ? RunState.CANCELLED : RunState.COMPLETED;
                runs.append(id, Set.of(RunState.CREATED), state,
                        event("core.run." + state.name().toLowerCase(Locale.ROOT), json.createObjectNode().set("output", output)), output, null);
                user = null;
            }
        });
    }
    private RunEventDraft event(String type, com.fasterxml.jackson.databind.JsonNode payload) {
        return new RunEventDraft(type, 1, "legacy.migration", null, null, payload);
    }
    private RunRequest readRequest(String value) {
        try { return json.readValue(value, RunRequest.class); } catch (Exception failure) { throw new IllegalStateException("cannot migrate run", failure); }
    }
    private record LegacySession(RunScope scope, String title) { }
    private record MessagePair(String input, String output) { }
    private record LegacyMessage(int position, String role, String content, String delivery) { }
}
