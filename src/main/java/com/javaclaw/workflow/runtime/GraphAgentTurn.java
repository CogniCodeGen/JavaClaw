package com.javaclaw.workflow.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.ManagedTurn;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.workflow.model.StatePatch;

import java.util.Map;

/** Durable Agent owner of an existing graph execution; never creates a second scheduler. */
final class GraphAgentTurn {
    static final String OWNER_KEY = "_agent.turnId";
    private final AgentClient agents;
    private final ManagedTurn turn;
    private String activeStep;

    private GraphAgentTurn(AgentClient agents, ManagedTurn turn) {
        this.agents = agents;
        this.turn = turn;
    }

    static GraphAgentTurn begin(AgentClient agents, String workspaceId, GraphRun run) {
        if (agents == null || !agents.supportsManagedTurns()) return null;
        var json = JsonNodeFactory.instance;
        var request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat"))
                .source(InvocationSource.workflow(run.id()))
                .scope(new RunScope(workspaceId, "local-user", run.threadId()))
                .input(InputBlock.text(run.state().get("input").asText("")))
                .permissionCeiling(PermissionSet.UNRESTRICTED)
                .idempotencyKey("graph:" + run.id())
                .attributes(Map.of("framework.managedTaskId", json.textNode(run.id())))
                .build();
        ManagedTurn turn = agents.beginTurn(request);
        try {
            turn.ready().toCompletableFuture().join();
            if (turn.completion().toCompletableFuture().isDone()) {
                throw new IllegalStateException("工作流协调轮次已结束，不能重新执行: " + run.id());
            }
        } catch (RuntimeException | Error failure) {
            turn.close();
            throw failure;
        }
        run.state(run.state().apply(StatePatch.builder()
                .set(OWNER_KEY, turn.id().value()).build()));
        return new GraphAgentTurn(agents, turn);
    }

    void onEvent(GraphEvent event) {
        if (turn.cancelled()) return;
        var payload = GraphEventJson.encode(event);
        if (event instanceof GraphEvent.NodeStarted started) {
            activeStep = "graph:" + event.runId() + ":visit:" + started.step();
            turn.emit("core.step.started", JsonNodeFactory.instance.objectNode()
                    .put("stepId", activeStep).put("kind", "ORCHESTRATION").set("input", payload));
        } else if (event instanceof GraphEvent.NodeCompleted) {
            finishStep(payload, null);
        } else if (event instanceof GraphEvent.NodeRetry) {
            turn.emit("core.orchestration.retry", payload);
        } else if (event instanceof GraphEvent.RunFinished finished) {
            finishStep(payload, finished.error());
            switch (finished.status()) {
                case COMPLETED -> turn.complete(JsonNodeFactory.instance.objectNode()
                        .put("text", finished.output() == null ? "" : finished.output()));
                case FAILED -> turn.fail(new IllegalStateException(finished.error()));
                case CANCELLED -> agents.cancel(turn.id(),
                        new CancelReason("WORKFLOW_CANCELLED", event.runId()));
                case WAITING_INPUT -> turn.waitingInput(payload, "工作流等待输入");
                case PAUSED, RECOVERY_REQUIRED, RECOVERY_BLOCKED_MISSING_EXTENSION ->
                        turn.pause("工作流已暂停");
                default -> { }
            }
        } else {
            turn.emit("core.orchestration.progress", payload);
        }
    }

    void close() { turn.close(); }

    void failedNode(String nodeId, Throwable failure) {
        if (!turn.cancelled()) finishStep(JsonNodeFactory.instance.objectNode().put("nodeId", nodeId),
                failure.getMessage() == null ? failure.toString() : failure.getMessage());
    }

    void observeCancellation(CancellationToken cancellation) {
        turn.completion().whenComplete((outcome, failure) -> {
            if (outcome != null && outcome.state() == com.javaclaw.framework.api.RunState.CANCELLED) {
                cancellation.cancel();
            }
        });
    }

    private void finishStep(com.fasterxml.jackson.databind.JsonNode output, String error) {
        if (activeStep == null) return;
        var payload = JsonNodeFactory.instance.objectNode().put("stepId", activeStep);
        payload.set("output", output);
        if (error != null) payload.put("message", error);
        turn.emit(error == null ? "core.step.completed" : "core.step.failed", payload);
        activeStep = null;
    }
}
