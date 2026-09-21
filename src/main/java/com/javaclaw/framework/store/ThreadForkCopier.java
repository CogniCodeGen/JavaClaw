package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies the cutoff journal and its runs; a fork never needs a live source thread. */
final class ThreadForkCopier {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    ThreadForkCopier(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    List<ThreadEvent> copy(List<ThreadEvent> history, RunScope target) {
        // Requests may have been queued before the selected turn ended. Their mutable run rows
        // can contain results produced after the cutoff, and must never enter the branch.
        java.util.Set<String> terminalTurns = history.stream()
                .filter(event -> event.turnId() != null && java.util.Set.of("turn/completed", "turn/failed", "turn/cancelled").contains(event.type()))
                .map(event -> event.turnId().value()).collect(java.util.stream.Collectors.toSet());
        Map<String, RunId> ids = new LinkedHashMap<>();
        List<ThreadEvent> copies = new ArrayList<>();
        for (ThreadEvent event : history) {
            if (event.type().startsWith("thread/")) continue;
            if (event.turnId() == null) {
                copies.add(new ThreadEvent(target, event.sequence(), event.timestamp(), event.type(), null, event.payload()));
                continue;
            }
            String originalId = event.turnId().value();
            if (!terminalTurns.contains(originalId)) continue;
            RunId id = ids.computeIfAbsent(originalId, ignored -> RunId.random());
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) event.payload();
            if (payload.has("request")) {
                RunRequest original = read(payload.get("request"), RunRequest.class);
                Map<String, com.fasterxml.jackson.databind.JsonNode> attributes = new LinkedHashMap<>(original.attributes());
                attributes.putIfAbsent("memory.originThreadId", json.getNodeFactory().textNode(original.scope().sessionId()));
                attributes.putIfAbsent("memory.originTurnId", json.getNodeFactory().textNode(originalId));
                RunRequest request = new RunRequest(original.agent(), original.profile(), original.source(), target,
                        original.inputs(), RunLinkage.root(original.linkage().correlationId()), original.permissionCeiling(),
                        original.budget(), null, attributes);
                payload.set("request", json.valueToTree(request));
                jdbc.update("""
                        INSERT INTO agent_runs(run_id,workspace_id,user_id,session_id,idempotency_key,request_json,
                          execution_plan_id,state,last_sequence,output_json,error_text,version,created_at,updated_at)
                        SELECT ?,?,?,?,NULL,?,execution_plan_id,state,last_sequence,output_json,error_text,version,created_at,updated_at
                          FROM agent_runs WHERE run_id=?
                        """, id.value(), target.workspaceId(), target.userId(), target.sessionId(), write(request), originalId);
            }
            if (payload.has("event")) {
                RunEventEnvelope original = read(payload.get("event"), RunEventEnvelope.class);
                RunEventEnvelope copied = new RunEventEnvelope(id.value(), original.sequence(), original.timestamp(), original.type(),
                        original.schemaVersion(), original.producer(), original.correlationId(), original.causationId(), original.payload());
                payload.set("event", json.valueToTree(copied));
                jdbc.update("INSERT INTO agent_run_events VALUES(?,?,?,?,?,?,?,?,?)", id.value(), copied.sequence(),
                        copied.timestamp().toEpochMilli(), copied.type(), copied.schemaVersion(), copied.producer(),
                        copied.correlationId(), copied.causationId(), write(copied.payload()));
            }
            copies.add(new ThreadEvent(target, event.sequence(), event.timestamp(), event.type(), TurnId.from(id), payload));
        }
        for (RunId id : ids.values()) {
            // A cancelled source may settle a previously started step after the fork cutoff.
            // The copied snapshot must describe only its actual copied event stream.
            jdbc.update("UPDATE agent_runs SET last_sequence=(SELECT MAX(event_sequence) FROM agent_run_events WHERE run_id=?),"
                            + "version=(SELECT COUNT(*)-1 FROM agent_run_events WHERE run_id=?),"
                            + "updated_at=(SELECT MAX(timestamp_ms) FROM agent_run_events WHERE run_id=?) WHERE run_id=?",
                    id.value(), id.value(), id.value(), id.value());
        }
        return copies;
    }
    private <T> T read(com.fasterxml.jackson.databind.JsonNode value, Class<T> type) {
        try { return json.treeToValue(value, type); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
