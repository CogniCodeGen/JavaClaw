package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.FactItem;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;

/** 单条事实的显示、行内编辑和动作入口；不访问服务或数据库。 */
public final class MemoryFactRowController {
    @FXML private Button selectionButton;
    @FXML private VBox displayBox;
    @FXML private VBox editBox;
    @FXML private Label factText;
    @FXML private FlowPane metadata;
    @FXML private Label updated;
    @FXML private Label hits;
    @FXML private Label mentions;
    @FXML private Label pending;
    @FXML private Label protectedBadge;
    @FXML private Label asserted;
    @FXML private Label superseded;
    @FXML private Label contested;
    @FXML private Label pinned;
    @FXML private TextArea editArea;
    @FXML private HBox tools;
    @FXML private Button restoreButton;
    @FXML private Button pinButton;

    private FactItem fact;
    private MemoryFactActions actions;

    void configure(
            FactItem fact,
            boolean batchMode,
            boolean selected,
            MemoryFactActions actions) {
        this.fact = Objects.requireNonNull(fact, "fact");
        this.actions = Objects.requireNonNull(actions, "actions");
        selectionButton.setVisible(batchMode);
        selectionButton.setManaged(batchMode);
        selectionButton.setText(selected ? "✓" : "");
        selectionButton.getStyleClass().remove("mc-check-on");
        if (selected) selectionButton.getStyleClass().add("mc-check-on");
        tools.setVisible(!batchMode);
        tools.setManaged(!batchMode);
        factText.setText(fact.text());
        editArea.setText(fact.text());
        updated.setText("更新 " + MemoryUiText.formatTime(fact.updatedAt()));
        hits.setText("· 命中 " + fact.hitCount());
        mentions.setText("· 提及 " + fact.mergeCount());
        visible(mentions, fact.mergeCount() > 0);
        visible(pending, fact.pending());
        visible(protectedBadge, fact.userEdited());
        visible(asserted, !fact.userEdited() && fact.userAsserted());
        visible(superseded, fact.superseded());
        visible(contested, !fact.superseded() && fact.contested());
        visible(pinned, fact.pinned());
        visible(restoreButton, (fact.superseded() || fact.contested()) && !fact.pending());
        pinButton.setText(fact.pinned() ? "★" : "☆");
        pinButton.setTooltip(new Tooltip(fact.pinned() ? "取消置顶" : "置顶"));
        showEditing(false);
    }

    @FXML private void selectionRequested() { actions.toggleSelected(fact.id()); }
    @FXML private void editRequested() { showEditing(true); }
    @FXML private void cancelEdit() { showEditing(false); }
    @FXML private void togglePin() { actions.togglePin(fact.id()); }
    @FXML private void restoreRequested() { actions.restore(fact.id()); }
    @FXML private void deleteRequested() { actions.delete(fact.id(), fact.text()); }

    @FXML
    private void saveEdit() {
        String value = editArea.getText() == null ? "" : editArea.getText().strip();
        if (!value.isBlank() && !value.equals(fact.text())) actions.edit(fact.id(), value);
        else showEditing(false);
    }

    private void showEditing(boolean editing) {
        displayBox.setVisible(!editing);
        displayBox.setManaged(!editing);
        editBox.setVisible(editing);
        editBox.setManaged(editing);
        tools.setVisible(!editing && !selectionButton.isManaged());
        tools.setManaged(tools.isVisible());
        if (editing) {
            editArea.requestFocus();
            editArea.positionCaret(editArea.getLength());
        }
    }

    private static void visible(javafx.scene.Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }
}
