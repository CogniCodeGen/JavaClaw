package com.javaclaw.ui.javafx.task;

import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import com.javaclaw.task.sdd.spec.TaskItem;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

final class SddChecklistCell extends ListCell<TaskItem> {
    @FXML private HBox root;
    @FXML private Label checkboxLabel;
    @FXML private Label actionLabel;
    @FXML private Label runningBadge;

    SddChecklistCell() {
        HBox content = EmbeddedFxmlLoader.load(SddChecklistCell.class.getResource(
                "/fxml/task/sdd-checklist-cell.fxml"), this, HBox.class);
        if (content != root) throw new IllegalStateException("SddChecklistCell FXML 根节点不一致");
    }

    @Override protected void updateItem(TaskItem item, boolean empty) {
        super.updateItem(item, empty);
        setText(null);
        if (empty || item == null) { setGraphic(null); return; }
        boolean active = !item.done() && firstPendingIndex() == getIndex();
        checkboxLabel.setText(item.done() ? "✓" : "");
        checkboxLabel.getStyleClass().setAll("sdd-checkbox",
                item.done() ? "sdd-checkbox-done" : "sdd-checkbox-pending");
        actionLabel.setText(item.index() + ". " + item.action());
        actionLabel.getStyleClass().setAll(item.done() ? "sdd-check-text-done"
                : active ? "sdd-check-text-active" : "sdd-check-text-pending");
        root.getStyleClass().setAll("sdd-check-row");
        if (item.done()) root.getStyleClass().add("sdd-check-row-done");
        else if (active) root.getStyleClass().add("sdd-check-row-active");
        runningBadge.setVisible(active);
        runningBadge.setManaged(active);
        setGraphic(root);
    }

    private int firstPendingIndex() {
        for (int i = 0; i < getListView().getItems().size(); i++) {
            if (!getListView().getItems().get(i).done()) return i;
        }
        return -1;
    }
}
