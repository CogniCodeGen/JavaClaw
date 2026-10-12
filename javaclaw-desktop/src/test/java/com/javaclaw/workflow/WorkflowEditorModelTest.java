package com.javaclaw.workflow;

import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.EdgeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowEditorModelTest {
    @Test void 编辑操作支持撤销重做并清理关联边() {
        WorkflowEditorModel model = new WorkflowEditorModel(WorkflowEditorModel.blank("测试"));
        var node = model.addNode(NodeType.OUTPUT, 200, 200);
        assertTrue(model.canUndo());
        int edgeCount = model.current().edges().size();
        model.deleteNode(node.id());
        assertTrue(model.current().edges().size() < edgeCount);
        model.undo();
        assertTrue(model.current().nodes().stream().anyMatch(n -> n.id().equals(node.id())));
        model.redo();
        assertFalse(model.current().nodes().stream().anyMatch(n -> n.id().equals(node.id())));
    }

    @Test void 不能删除唯一的结束节点() {
        WorkflowEditorModel model = new WorkflowEditorModel(WorkflowEditorModel.blank("测试"));
        assertThrows(IllegalArgumentException.class, () -> model.deleteNode("end"));
        assertTrue(model.current().nodes().stream().anyMatch(n -> n.type() == NodeType.END));
    }

    @Test void 新错误出口会替换旧错误出口() {
        WorkflowEditorModel model = new WorkflowEditorModel(WorkflowEditorModel.blank("测试"));
        var first = model.addNode(NodeType.OUTPUT, 200, 200);
        var second = model.addNode(NodeType.OUTPUT, 400, 200);
        model.connect("start", first.id(), EdgeKind.ERROR, null, 0, false);
        model.connect("start", second.id(), EdgeKind.ERROR, null, 0, false);
        var errors = model.current().edges().stream()
                .filter(edge -> edge.source().equals("start") && edge.kind() == EdgeKind.ERROR).toList();
        assertEquals(1, errors.size());
        assertEquals(second.id(), errors.getFirst().target());
    }
}
