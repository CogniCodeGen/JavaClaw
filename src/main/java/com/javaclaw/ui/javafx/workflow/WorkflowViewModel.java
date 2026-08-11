package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.application.workflow.WorkflowApplicationService.Snapshot;
import com.javaclaw.application.workflow.WorkflowApplicationService.WorkflowItem;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.runtime.GraphRun;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** 工作流页面状态；不持有应用服务、仓储或运行时对象。 */
public final class WorkflowViewModel {

    private final ObservableList<WorkflowItem> workflows = FXCollections.observableArrayList();
    private final ObservableList<GraphRun> runs = FXCollections.observableArrayList();
    private final ObjectProperty<WorkflowItem> selectedWorkflow = new SimpleObjectProperty<>();
    private final ObjectProperty<NodeDefinition> selectedNode = new SimpleObjectProperty<>();
    private final ObjectProperty<GraphDefinition> graph = new SimpleObjectProperty<>();
    private final BooleanProperty readOnly = new SimpleBooleanProperty();
    private final BooleanProperty dirty = new SimpleBooleanProperty();
    private final StringProperty title = new SimpleStringProperty("工作流中心");
    private final StringProperty titleHint = new SimpleStringProperty("选择一个工作流开始编排");
    private final StringProperty saveState = new SimpleStringProperty("");
    private final StringProperty canvasHint = new SimpleStringProperty(
            "拖拽节点调整流程 · 右键节点创建连线");
    private final StringProperty console = new SimpleStringProperty("");
    private final DoubleProperty zoom = new SimpleDoubleProperty(1.0);
    private WorkflowEditorModel editor;

    public ObservableList<WorkflowItem> workflows() { return workflows; }

    public ObservableList<GraphRun> runs() { return runs; }

    public ObjectProperty<WorkflowItem> selectedWorkflowProperty() { return selectedWorkflow; }

    public ObjectProperty<NodeDefinition> selectedNodeProperty() { return selectedNode; }

    public ObjectProperty<GraphDefinition> graphProperty() { return graph; }

    public BooleanProperty readOnlyProperty() { return readOnly; }

    public BooleanProperty dirtyProperty() { return dirty; }

    public StringProperty titleProperty() { return title; }

    public StringProperty titleHintProperty() { return titleHint; }

    public StringProperty saveStateProperty() { return saveState; }

    public StringProperty canvasHintProperty() { return canvasHint; }

    public StringProperty consoleProperty() { return console; }

    public DoubleProperty zoomProperty() { return zoom; }

    public WorkflowEditorModel editor() { return editor; }

    public GraphDefinition currentGraph() { return editor == null ? null : editor.current(); }

    public boolean hasSelection() { return selectedWorkflow.get() != null; }

    public void apply(Snapshot snapshot) {
        workflows.setAll(snapshot.workflows());
    }

    public void select(WorkflowItem item) {
        selectedWorkflow.set(item);
        selectedNode.set(null);
        if (item == null) {
            editor = null;
            graph.set(null);
            readOnly.set(false);
            title.set("工作流中心");
            titleHint.set("选择一个工作流开始编排");
            saveState.set("");
            dirty.set(false);
            runs.clear();
            return;
        }
        editor = new WorkflowEditorModel(item.graph());
        graph.set(editor.current());
        readOnly.set(item.system());
        title.set(item.name());
        titleHint.set(item.system() ? "系统编排 · 只读，可复制为自定义草稿"
                : item.published() ? "已发布版本可在聊天工作流模式中运行"
                : "草稿仅保存在当前工作区");
        markSaved();
    }

    public void changed() {
        if (editor == null) return;
        graph.set(editor.current());
        if (!readOnly.get()) {
            dirty.set(true);
            saveState.set("保存中…");
        }
    }

    public void refreshGraph() {
        graph.set(editor == null ? null : editor.current());
    }

    public void markSaved() {
        dirty.set(false);
        saveState.set(readOnly.get() ? "只读" : "已保存");
    }

    public void markSaveFailed() {
        dirty.set(true);
        saveState.set("保存失败");
    }

    public void selectNode(NodeDefinition node) {
        selectedNode.set(node);
    }

    public void beginConnection(NodeDefinition source, boolean error) {
        canvasHint.set(error
                ? "正在创建错误出口：请选择目标节点 · Esc 取消"
                : "正在创建出口：请选择目标节点 · Esc 取消");
        selectedNode.set(source);
    }

    public void endConnection() {
        canvasHint.set("拖拽节点调整流程 · 右键节点创建连线");
    }

    public void appendLog(String line) {
        if (line == null || line.isBlank()) return;
        console.set(console.get() + line + System.lineSeparator());
    }

    public void clearRuns() { runs.clear(); }

    public void setRuns(java.util.List<GraphRun> values) { runs.setAll(values); }
}
