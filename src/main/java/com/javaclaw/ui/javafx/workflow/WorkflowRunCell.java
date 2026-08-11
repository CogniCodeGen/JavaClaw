package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import com.javaclaw.workflow.runtime.GraphRun;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

/** 虚拟化运行记录 Cell；构造时加载模板，复用时只刷新值。 */
public final class WorkflowRunCell extends ListCell<GraphRun> {

    @FXML private HBox root;
    @FXML private Label statusLabel;
    @FXML private Label idLabel;
    @FXML private Label metaLabel;

    WorkflowRunCell() {
        HBox loaded = EmbeddedFxmlLoader.load(
                WorkflowRunCell.class.getResource("/fxml/workflow/workflow-run-cell.fxml"),
                this, HBox.class);
        if (loaded != root) throw new IllegalStateException("工作流运行 Cell FXML 根节点不一致");
    }

    @Override
    protected void updateItem(GraphRun run, boolean empty) {
        super.updateItem(run, empty);
        setText(null);
        if (empty || run == null) {
            setGraphic(null);
            return;
        }
        statusLabel.setText(WorkflowLabels.runStatus(run.status()));
        statusLabel.getStyleClass().removeAll(
                "jc-badge-running", "jc-badge-amber", "jc-badge-ok",
                "jc-badge-failed", "jc-badge-stopped");
        statusLabel.getStyleClass().add(WorkflowLabels.runStyle(run.status()));
        idLabel.setText("#" + run.id().substring(0, Math.min(8, run.id().length())));
        metaLabel.setText(run.stepCount() + " 步 · v" + run.workflowVersion());
        setAccessibleText(statusLabel.getText() + "，运行 " + idLabel.getText()
                + "，" + metaLabel.getText());
        setGraphic(root);
    }
}
