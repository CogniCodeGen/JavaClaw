package com.javaclaw.ui.javafx.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RetryPolicy;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 节点属性检查器 Controller。 */
public final class WorkflowInspectorController implements AutoCloseable {

    private final JsonCodec json;

    @FXML private Label titleLabel;
    @FXML private Label typeLabel;
    @FXML private StackPane body;
    @FXML private VBox emptyPanel;
    @FXML private VBox formPanel;
    @FXML private VBox actionPanel;
    @FXML private TextField nodeLabel;
    @FXML private Spinner<Integer> retryAttempts;
    @FXML private Spinner<Integer> retryBackoff;
    @FXML private ComboBox<ResumeSafety> resumeSafety;
    @FXML private TextArea configEditor;

    private WorkflowViewModel viewModel;
    private Runnable changed = () -> { };
    private BiConsumer<NodeDefinition, EdgeKind> connect = (node, kind) -> { };
    private Consumer<String> logger = ignored -> { };
    private ChangeListener<NodeDefinition> selectionListener;
    private ChangeListener<Boolean> readOnlyListener;

    public WorkflowInspectorController(JsonCodec json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    @FXML
    private void initialize() {
        retryAttempts.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 1));
        retryBackoff.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 300_000, 0, 250));
        resumeSafety.getItems().setAll(ResumeSafety.values());
    }

    void configure(
            WorkflowViewModel viewModel,
            Runnable changed,
            BiConsumer<NodeDefinition, EdgeKind> connect,
            Consumer<String> logger) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.changed = Objects.requireNonNull(changed, "changed");
        this.connect = Objects.requireNonNull(connect, "connect");
        this.logger = Objects.requireNonNull(logger, "logger");
        selectionListener = (ignored, previous, node) -> show(node);
        readOnlyListener = (ignored, previous, value) -> show(viewModel.selectedNodeProperty().get());
        viewModel.selectedNodeProperty().addListener(selectionListener);
        viewModel.readOnlyProperty().addListener(readOnlyListener);
        show(viewModel.selectedNodeProperty().get());
    }

    @FXML
    private void applyRequested() {
        NodeDefinition selected = selected();
        if (selected == null || viewModel.readOnlyProperty().get()) return;
        try {
            NodeDefinition current = viewModel.currentGraph().nodes().stream()
                    .filter(node -> node.id().equals(selected.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "节点已不存在: " + selected.id()));
            JsonNode config = current.type() == NodeType.SYSTEM
                    ? current.config() : json.tree(configEditor.getText());
            NodeDefinition updated = new NodeDefinition(
                    current.id(), current.type(), current.executorType(),
                    nodeLabel.getText().isBlank() ? current.label() : nodeLabel.getText(),
                    config, current.x(), current.y(),
                    new RetryPolicy(retryAttempts.getValue(), retryBackoff.getValue(),
                            current.retryPolicy().multiplier()),
                    resumeSafety.getValue() == null
                            ? current.resumeSafety() : resumeSafety.getValue());
            viewModel.editor().updateNode(updated);
            viewModel.selectNode(updated);
            changed.run();
        } catch (Exception failure) {
            logger.accept("配置 JSON 无效：" + failure.getMessage());
        }
    }

    @FXML private void connectRequested() { connect.accept(selected(), EdgeKind.NORMAL); }

    @FXML private void errorConnectionRequested() { connect.accept(selected(), EdgeKind.ERROR); }

    @FXML
    private void deleteRequested() {
        NodeDefinition selected = selected();
        if (selected == null || viewModel.readOnlyProperty().get()) return;
        if (selected.type() == NodeType.END) {
            logger.accept("END 节点是工作流必需出口，不能删除");
            return;
        }
        try {
            viewModel.editor().deleteNode(selected.id());
            viewModel.selectNode(null);
            changed.run();
        } catch (RuntimeException failure) {
            logger.accept(failure.getMessage());
        }
    }

    private NodeDefinition selected() {
        return viewModel == null ? null : viewModel.selectedNodeProperty().get();
    }

    private void show(NodeDefinition node) {
        boolean selected = node != null;
        boolean editable = selected && !viewModel.readOnlyProperty().get();
        emptyPanel.setVisible(!selected);
        emptyPanel.setManaged(!selected);
        formPanel.setVisible(selected);
        formPanel.setManaged(selected);
        actionPanel.setVisible(editable);
        actionPanel.setManaged(editable);
        if (!selected) {
            titleLabel.setText("未选择节点");
            typeLabel.setText("等待选择");
            nodeLabel.clear();
            configEditor.clear();
            return;
        }
        titleLabel.setText(node.label());
        typeLabel.setText(WorkflowLabels.nodeName(node.type()));
        nodeLabel.setText(node.label());
        retryAttempts.getValueFactory().setValue(node.retryPolicy().maxAttempts());
        retryBackoff.getValueFactory().setValue((int) node.retryPolicy().initialBackoffMillis());
        resumeSafety.setValue(node.resumeSafety());
        configEditor.setDisable(viewModel.readOnlyProperty().get()
                || node.type() == NodeType.SYSTEM);
        try {
            configEditor.setText(json.encodePretty(node.config()));
        } catch (Exception failure) {
            configEditor.setText(node.config().toString());
        }
    }

    @Override
    public void close() {
        if (viewModel == null) return;
        if (selectionListener != null) {
            viewModel.selectedNodeProperty().removeListener(selectionListener);
        }
        if (readOnlyListener != null) {
            viewModel.readOnlyProperty().removeListener(readOnlyListener);
        }
    }
}
