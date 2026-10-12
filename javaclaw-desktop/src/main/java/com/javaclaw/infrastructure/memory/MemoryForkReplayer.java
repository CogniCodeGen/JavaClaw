package com.javaclaw.infrastructure.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.memory.MemoryGraphScope;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.graph.MemoryGraphSnapshot;
import com.javaclaw.memory.model.Episode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ordered replay freezes maintenance until raw turns and versioned graph mutations agree. */
final class MemoryForkReplayer {
    private MemoryForkReplayer() {}
    static void replay(MemoryService memory, ObjectMapper json, ThreadMemoryProjectionAdapter projection,
                       ThreadSnapshot target, List<ThreadEvent> history) {
        replayEvents(memory, json, projection, target, history, true);
    }
    static void catchup(MemoryService memory, ObjectMapper json, ThreadMemoryProjectionAdapter projection,
                        ThreadSnapshot target, List<ThreadEvent> history) {
        replayEvents(memory, json, projection, target, history, false);
    }
    private static void replayEvents(MemoryService memory, ObjectMapper json, ThreadMemoryProjectionAdapter projection,
                                     ThreadSnapshot target, List<ThreadEvent> history, boolean restoreSnapshots) {
        MemoryGraphScope scope = MemoryGraphScope.thread(target.scope());
        Map<String, com.fasterxml.jackson.databind.node.ArrayNode> traces = new HashMap<>();
        long observed = restoreSnapshots ? 0 : memory.inScope(scope).store().root().observedThreadSequence;
        Map<String, RunRequest> requests = new HashMap<>();
        Map<String, TargetTurn> origins = new HashMap<>();
        if (restoreSnapshots) memory.beginForkReplay(scope);
        for (ThreadEvent event : history) {
            if (event.type().equals("memory/graph-snapshot")) {
                if (!restoreSnapshots) continue;
                try {
                    MemoryGraphSnapshot snapshot = json.treeToValue(event.payload().get("snapshot"), MemoryGraphSnapshot.class);
                    snapshot.episodes().forEach(episode -> rebind(episode, target.scope(), origins));
                    snapshot.pendingEpisodes().forEach(episode -> rebind(episode, target.scope(), origins));
                    memory.restoreForkSnapshot(scope, snapshot);
                } catch (Exception failure) { throw new IllegalStateException("无法回放分支图谱变更", failure); }
                continue;
            }
            if (event.turnId() == null) continue;
            if (event.payload().has("request")) {
                try {
                    RunRequest original = json.treeToValue(event.payload().get("request"), RunRequest.class);
                    String origin = original.attributes().containsKey("memory.originThreadId")
                            ? original.attributes().get("memory.originThreadId").asText() : original.scope().sessionId();
                    original = original.withAttribute("memory.originThreadId", json.getNodeFactory().textNode(origin));
                    requests.put(event.turnId().value(), original);
                } catch (Exception failure) { throw new IllegalStateException("无法恢复分支原始输入", failure); }
            }
            if (event.type().startsWith("core.tool."))
                traces.computeIfAbsent(event.turnId().value(), ignored -> json.createArrayNode()).add(event.payload().path("event"));
            if (java.util.Set.of("turn/completed", "turn/failed", "turn/cancelled").contains(event.type())
                    && event.sequence() > observed) {
                RunRequest request = requests.get(event.turnId().value());
                if (request == null) throw new IllegalStateException("历史缺少对应 Turn 的输入");
                if (traces.containsKey(event.turnId().value()))
                    request = request.withAttribute("framework.toolTrace", traces.get(event.turnId().value()));
                String originThread = request.attributes().get("memory.originThreadId").asText();
                String originTurn = request.attributes().containsKey("memory.originTurnId")
                        ? request.attributes().get("memory.originTurnId").asText() : event.turnId().value();
                origins.put(originThread + ":" + originTurn, new TargetTurn(event.turnId().value(), event.sequence()));
                projection.project(request, new ThreadEvent(target.scope(), event.sequence(), event.timestamp(),
                        event.type(), event.turnId(), event.payload()));
            }
        }
        if (restoreSnapshots) memory.endForkReplay(scope);
    }

    private static void rebind(Episode episode, RunScope target, Map<String, TargetTurn> origins) {
        TargetTurn turn = origins.get(episode.evidenceKey());
        if (episode.originThreadId == null) episode.originThreadId = episode.sessionId;
        if (episode.originTurnId == null) episode.originTurnId = episode.turnId;
        boolean copied = !target.sessionId().equals(episode.sessionId);
        episode.sessionId = target.sessionId();
        if (copied) episode.habitEvidence = false;
        if (turn != null) {
            episode.turnId = turn.id();
            episode.ownerRunId = turn.id();
            episode.sourceEventSequence = turn.sequence();
        } else if (copied) {
            episode.turnId = "fork:" + target.sessionId() + ":" + episode.turnId;
            episode.ownerRunId = null;
        }
    }
    private record TargetTurn(String id, long sequence) {}
}
