package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.application.workflow.WorkflowApplicationService.Snapshot;
import com.javaclaw.application.workflow.WorkflowApplicationService.WorkflowItem;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.ui.javafx.workflow.WorkflowViewModel.SaveStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowViewModelTest {

    @Test
    void selectionCreatesIndependentEditorAndTracksDirtyState() {
        var graph = WorkflowEditorModel.blank("草稿");
        var item = new WorkflowItem(graph.id(), graph.name(), graph, false, false);
        WorkflowViewModel viewModel = new WorkflowViewModel();

        viewModel.apply(new Snapshot(List.of(item)));
        viewModel.select(item);
        assertFalse(viewModel.readOnlyProperty().get());
        assertEquals("已保存", viewModel.saveStateProperty().get());
        assertEquals(SaveStatus.SAVED, viewModel.saveStatusProperty().get());

        viewModel.editor().addNode(NodeType.AGENT, 300, 200);
        viewModel.changed();
        assertTrue(viewModel.dirtyProperty().get());
        assertEquals("保存中…", viewModel.saveStateProperty().get());
        assertEquals(SaveStatus.SAVING, viewModel.saveStatusProperty().get());
        assertEquals(4, viewModel.graphProperty().get().nodes().size());

        viewModel.markSaved();
        assertFalse(viewModel.dirtyProperty().get());
        assertEquals("已保存", viewModel.saveStateProperty().get());
        assertEquals(SaveStatus.SAVED, viewModel.saveStatusProperty().get());
    }

    @Test
    void systemSelectionIsReadOnlyAndClearingSelectionResetsPage() {
        var graph = WorkflowEditorModel.blank("系统流程");
        var item = new WorkflowItem(graph.id(), graph.name(), graph, true, true);
        WorkflowViewModel viewModel = new WorkflowViewModel();

        viewModel.select(item);
        assertTrue(viewModel.readOnlyProperty().get());
        assertEquals("只读", viewModel.saveStateProperty().get());
        assertEquals(SaveStatus.READ_ONLY, viewModel.saveStatusProperty().get());
        assertEquals("系统编排 · 只读，可复制为自定义草稿",
                viewModel.titleHintProperty().get());

        viewModel.select(null);
        assertNull(viewModel.currentGraph());
        assertEquals("工作流中心", viewModel.titleProperty().get());
        assertFalse(viewModel.dirtyProperty().get());
        assertEquals(SaveStatus.NONE, viewModel.saveStatusProperty().get());
    }

    @Test
    void saveFailureUsesTypedStatusIndependentOfDisplayText() {
        WorkflowViewModel viewModel = new WorkflowViewModel();
        viewModel.markSaveFailed();
        assertEquals(SaveStatus.FAILED, viewModel.saveStatusProperty().get());
        assertEquals("保存失败", viewModel.saveStateProperty().get());
        viewModel.saveStateProperty().set("任意提示文字");
        assertEquals(SaveStatus.FAILED, viewModel.saveStatusProperty().get());
    }
}
