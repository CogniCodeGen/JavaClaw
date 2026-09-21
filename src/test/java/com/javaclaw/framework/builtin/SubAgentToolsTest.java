package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentToolsTest {
    @Test void childIdentitySurvivesParentTurnsWhileOnlySelectedContextAndPermissionsCrossTheBoundary()
            throws Exception {
        Store store = new Store();
        Client agents = new Client(store);
        var provider = new SubAgentTools(() -> agents, store);
        RunScope scope = new RunScope("workspace", "user", "parent");
        ToolContext parent = parent(store, scope, "parent-turn-one");
        var input = JsonNodeFactory.instance.objectNode().put("childKey", "reviewer")
                .put("task", "Review this change").put("selectedContext", "selected evidence");
        JsonNode first = tool(provider, parent, "subagent_spawn").execute(input, execution(parent, "spawn-1"));
        RunRequest child = agents.requests.getFirst();
        assertEquals(parent.runId(), child.linkage().parentRunId());
        assertEquals(PermissionSet.of("tool.read"), child.permissionCeiling());
        assertFalse(child.inputs().toString().contains("private-parent-history"));
        assertTrue(child.inputs().toString().contains("selected evidence"));
        assertEquals("subagent", child.profile().id());

        ToolContext nextParent = parent(store, scope, "parent-turn-two");
        JsonNode second = tool(provider, nextParent, "subagent_send").execute(input, execution(nextParent, "send-1"));
        assertEquals(first.path("threadId"), second.path("threadId"));
        assertNotEquals(first.path("turnId"), second.path("turnId"));
        var resultInput = JsonNodeFactory.instance.objectNode().put("turnId", first.path("turnId").asText());
        assertEquals("done", tool(provider, nextParent, "subagent_result")
                .execute(resultInput, execution(nextParent, "result-1")).path("output").path("text").asText());
        ToolContext unrelated = parent(store, new RunScope("workspace", "user", "different-parent"), "other-turn");
        assertThrows(SecurityException.class, () -> tool(provider, unrelated, "subagent_result")
                .execute(resultInput, execution(unrelated, "result-2")));
        assertNotEquals(SubAgentTools.childScope(scope, "reviewer"),
                SubAgentTools.childScope(new RunScope("workspace", "other-user", "parent"), "reviewer"));
    }

    private static FrameworkTool tool(SubAgentTools provider, ToolContext context, String name) {
        return provider.create(context).stream().filter(tool -> tool.descriptor().name().equals(name)).findFirst().orElseThrow();
    }
    private static ToolExecutionContext execution(ToolContext parent, String invocation) {
        return new ToolExecutionContext(parent.runId(), invocation, () -> false, Instant.now().plusSeconds(60));
    }
    private static ToolContext parent(Store store, RunScope scope, String id) {
        RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat()).scope(scope)
                .input(InputBlock.text("private-parent-history")).permissionCeiling(PermissionSet.UNRESTRICTED).build();
        RunId run = new RunId(id);
        store.values.put(run, new StoredRun(snapshot(run, RunState.RUNNING), request));
        return new ToolContext(run, scope, PermissionSet.of("tool.read"), () -> false, Instant.now().plusSeconds(60), request);
    }
    private static RunSnapshot snapshot(RunId id, RunState state) {
        return new RunSnapshot(id, state, "plan", 1, Instant.now(), Instant.now(),
                JsonNodeFactory.instance.objectNode().put("text", "done"), null, 1);
    }
    private static final class Client implements AgentClient {
        private final Store store;
        private final List<RunRequest> requests = new ArrayList<>();
        private Client(Store store) { this.store = store; }
        @Override public RunHandle start(RunRequest request) {
            requests.add(request);
            RunId id = RunId.random();
            store.values.put(id, new StoredRun(snapshot(id, RunState.COMPLETED), request));
            return new RunHandle() {
                @Override public RunId id() { return id; }
                @Override public Flux<RunEventEnvelope> events(long after) { return Flux.empty(); }
                @Override public java.util.concurrent.CompletionStage<RunOutcome> completion() {
                    return CompletableFuture.completedFuture(new RunOutcome(id, RunState.COMPLETED, get(id).output(), null));
                }
            };
        }
        @Override public RunHandle resume(RunId id, ResumeCommand command) { throw new UnsupportedOperationException(); }
        @Override public boolean cancel(RunId id, CancelReason reason) { return false; }
        @Override public RunSnapshot get(RunId id) { return store.values.get(id).snapshot(); }
    }
    private static final class Store implements RunStore {
        private final Map<RunId, StoredRun> values = new HashMap<>();
        @Override public Optional<StoredRun> find(RunId id) { return Optional.ofNullable(values.get(id)); }
        @Override public List<StoredRun> nonTerminalRuns() { return values.values().stream().filter(run -> !run.snapshot().state().terminal()).toList(); }
        @Override public Optional<StoredRun> findByIdempotencyKey(String workspace, String key) { return Optional.empty(); }
        @Override public List<RunEventEnvelope> eventsAfter(RunId id, long after) { return List.of(); }
        @Override public CreateRunResult create(RunId id, RunRequest request, String plan, RunEventDraft event) { throw new UnsupportedOperationException(); }
        @Override public Optional<RunEventEnvelope> append(RunId id, Set<RunState> expected, RunState next,
                RunEventDraft event, JsonNode output, String error) { throw new UnsupportedOperationException(); }
    }
}
