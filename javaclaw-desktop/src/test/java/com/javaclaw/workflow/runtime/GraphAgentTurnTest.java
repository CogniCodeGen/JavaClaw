package com.javaclaw.workflow.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.support.CapturingLifecycleClient;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.model.StatePatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GraphAgentTurnTest {
    @Test void graphOwnerSurvivesHumanInputAndPersistsAtomicStepsUnderTheSameTurn() {
        var agents = new CapturingLifecycleClient();
        GraphRun graph = new GraphRun(WorkflowEditorModel.blank("task"), "workflow:task",
                new GraphState().apply(StatePatch.builder().set("input", "goal").build()));
        GraphAgentTurn owner = GraphAgentTurn.begin(agents, "workspace", graph);
        var managed = agents.turns.values().iterator().next();
        assertEquals(managed.id.value(), graph.state().get(GraphAgentTurn.OWNER_KEY).asText());
        owner.onEvent(new GraphEvent.NodeStarted(graph.id(), "human", "review", 1));
        owner.onEvent(new GraphEvent.RunFinished(graph.id(), RunStatus.WAITING_INPUT, null, null));
        assertEquals(RunState.WAITING_INPUT, managed.state);
        assertFalse(managed.done.isDone());
        GraphAgentTurn resumed = GraphAgentTurn.begin(agents, "workspace", graph);
        assertEquals(1, agents.turns.size());
        resumed.onEvent(new GraphEvent.NodeStarted(graph.id(), "human", "review", 2));
        resumed.onEvent(new GraphEvent.NodeCompleted(graph.id(), "human", "review", 2));
        resumed.onEvent(new GraphEvent.RunFinished(graph.id(), RunStatus.COMPLETED, "done", null));
        assertEquals("done", managed.done.join().output().path("text").asText());
        assertEquals(2, managed.events.stream().filter(event -> event.type().equals("core.step.started")).count());
        assertEquals(2, managed.events.stream().filter(event -> event.type().equals("core.step.completed")).count());
        assertThrows(IllegalStateException.class, () -> GraphAgentTurn.begin(agents, "workspace", graph));
    }

    @Test void frameworkCancellationInterruptsGraphAndInvocationIdentityDistinguishesRevisits() {
        var agents = new CapturingLifecycleClient();
        GraphRun graph = new GraphRun(WorkflowEditorModel.blank("task"), "workflow:task", new GraphState());
        GraphAgentTurn owner = GraphAgentTurn.begin(agents, "workspace", graph);
        var managed = agents.turns.values().iterator().next();
        CancellationToken cancellation = new CancellationToken();
        owner.observeCancellation(cancellation);
        var node = graph.definition().nodes().getFirst();
        var first = new NodeExecutionContext(graph.id(), graph.threadId(), node, graph.state(), cancellation,
                GraphListener.NOOP, WorkflowExecutionServices.EMPTY, 0);
        var retry = new NodeExecutionContext(graph.id(), graph.threadId(), node, graph.state(), cancellation,
                GraphListener.NOOP, WorkflowExecutionServices.EMPTY, 0);
        var revisit = new NodeExecutionContext(graph.id(), graph.threadId(), node, graph.state(), cancellation,
                GraphListener.NOOP, WorkflowExecutionServices.EMPTY, 3);
        assertEquals(managed.id, first.ownerRunId());
        assertEquals(first.invocationId(), retry.invocationId());
        assertNotEquals(first.invocationId(), revisit.invocationId());
        agents.cancel(managed.id, new com.javaclaw.framework.api.CancelReason("deleted", "deleted"));
        assertTrue(cancellation.isCancelled());
    }
}
