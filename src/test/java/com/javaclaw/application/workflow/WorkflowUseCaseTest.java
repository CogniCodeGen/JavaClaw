package com.javaclaw.application.workflow;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.workflow.WorkflowApplicationService.RunObserver;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.runtime.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowUseCaseTest {

    private final FakePort port = new FakePort();
    private final WorkflowUseCase useCase = new WorkflowUseCase(port);

    @Test
    void snapshotsDefinitionsAndSelectsCreatedOrClonedDraft() {
        var snapshot = useCase.snapshot();
        assertEquals(List.of("system", "custom"),
                snapshot.workflows().stream().map(item -> item.id()).toList());
        assertTrue(snapshot.workflows().getFirst().system());
        assertTrue(snapshot.find("custom").published());

        assertEquals("created", useCase.createDraft().selectedId());
        assertEquals("clone", useCase.cloneDraft(" custom ").selectedId());
        assertEquals("custom", port.clonedId);
        assertThrows(ValidationException.class, () -> useCase.cloneDraft(" "));
    }

    @Test
    void publishSavesLatestGraphBeforePublishingAndReturnsUpdatedSelection() {
        GraphDefinition graph = port.custom;

        var result = useCase.publish(graph);

        assertSame(graph, port.saved);
        assertEquals("custom", port.publishedId);
        assertEquals("custom", result.published().id());
    }

    @Test
    void validatesRunIdentifiersAndDelegatesObservers() {
        RunObserver observer = new RunObserver() {
            @Override public void onTrace(String trace) { }
            @Override public void onTerminal(WorkflowApplicationService.RunTerminal terminal) { }
        };

        useCase.testRun(port.custom, " input ", observer);
        useCase.resumeRun(" run-1 ", " answer ", true, observer);
        assertEquals("input", port.testInput);
        assertEquals("run-1", port.resumedId);
        assertEquals("answer", port.resumeInput);
        assertTrue(port.confirmed);
        assertSame(observer, port.observer);
        assertThrows(ValidationException.class,
                () -> useCase.resumeRun(" ", "", false, observer));
        assertThrows(ValidationException.class, () -> useCase.cancelRun(null));
    }

    private static final class FakePort implements WorkflowPort {
        private final GraphDefinition system = renamed(WorkflowEditorModel.blank("系统"), "system");
        private final GraphDefinition custom = renamed(WorkflowEditorModel.blank("自定义"), "custom");
        private GraphDefinition saved;
        private String clonedId;
        private String publishedId;
        private String testInput;
        private String resumedId;
        private String resumeInput;
        private boolean confirmed;
        private RunObserver observer;

        @Override public List<Definition> definitions() {
            List<Definition> values = new ArrayList<>();
            values.add(new Definition(system, true, true));
            values.add(new Definition(custom, false, true));
            if (saved != null && !"custom".equals(saved.id())) {
                values.add(new Definition(saved, false, false));
            }
            return values;
        }
        @Override public GraphDefinition createDraft() {
            saved = renamed(WorkflowEditorModel.blank("新建"), "created");
            return saved;
        }
        @Override public GraphDefinition cloneDraft(String workflowId) {
            clonedId = workflowId;
            saved = renamed(WorkflowEditorModel.blank("副本"), "clone");
            return saved;
        }
        @Override public void saveDraft(GraphDefinition graph) { saved = graph; }
        @Override public String publish(String workflowId) {
            publishedId = workflowId;
            return workflowId;
        }
        @Override public List<ValidationIssue> validate(GraphDefinition graph) { return List.of(); }
        @Override public List<GraphRun> runs(String workflowId, int limit) { return List.of(); }
        @Override public void testRun(GraphDefinition graph, String input, RunObserver observer) {
            testInput = input;
            this.observer = observer;
        }
        @Override public void resumeRun(
                String runId, String input, boolean unsafeRetryConfirmed, RunObserver observer) {
            resumedId = runId;
            resumeInput = input;
            confirmed = unsafeRetryConfirmed;
            this.observer = observer;
        }
        @Override public boolean cancelRun(String runId) { return true; }
    }

    private static GraphDefinition renamed(GraphDefinition graph, String id) {
        return new GraphDefinition(graph.schemaVersion(), id, graph.name(), graph.description(),
                graph.version(), graph.kind(), graph.startNodeId(), graph.nodes(), graph.edges(),
                graph.maxSteps());
    }
}
