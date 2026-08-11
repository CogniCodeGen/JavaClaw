package com.javaclaw.chat;

import javafx.fxml.FXML;
import javafx.scene.AccessibleRole;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/** Binds one virtualized ListCell to either a group or a conversation projection. */
public final class SidebarSessionCellController implements AutoCloseable {

    @FXML private StackPane root;
    @FXML private HBox conversationRow;
    @FXML private HBox groupRow;
    @FXML private CheckBox checkBox;
    @FXML private Label titleLabel;
    @FXML private Label timeLabel;
    @FXML private Label groupNameLabel;
    @FXML private Label groupCountLabel;
    @FXML private ContextMenu rowMenu;
    @FXML private MenuItem deleteItem;
    @FXML private Tooltip titleTooltip;

    private SidebarSessionItem.Conversation conversation;
    private SidebarSessionCellActions actions;
    private boolean updating;
    private boolean closed;

    @FXML
    private void initialize() {
        conversationRow.setAccessibleRole(AccessibleRole.BUTTON);
    }

    void show(SidebarSessionItem item, SidebarSessionCellActions actions) {
        this.actions = actions;
        if (item instanceof SidebarSessionItem.Group group) {
            showGroup(group);
        } else if (item instanceof SidebarSessionItem.Conversation value) {
            showConversation(value);
        } else {
            clear();
        }
    }

    private void showGroup(SidebarSessionItem.Group group) {
        conversation = null;
        showOnly(groupRow);
        groupNameLabel.setText(group.name());
        groupCountLabel.setText(group.count() > 0 ? Integer.toString(group.count()) : "");
    }

    private void showConversation(SidebarSessionItem.Conversation value) {
        conversation = value;
        showOnly(conversationRow);
        titleLabel.setText(value.title());
        titleTooltip.setText(value.title());
        timeLabel.setText(value.timeText());
        conversationRow.setAccessibleText("会话：" + value.title());
        conversationRow.getStyleClass().remove("sidebar-conv-selected");
        if (value.selected()) conversationRow.getStyleClass().add("sidebar-conv-selected");
        checkBox.setVisible(value.batchMode());
        checkBox.setManaged(value.batchMode());
        deleteItem.setDisable(value.batchMode());
        updating = true;
        try {
            checkBox.setSelected(value.checked());
        } finally {
            updating = false;
        }
    }

    private void showOnly(HBox visibleRow) {
        boolean conversationVisible = visibleRow == conversationRow;
        conversationRow.setVisible(conversationVisible);
        conversationRow.setManaged(conversationVisible);
        groupRow.setVisible(!conversationVisible);
        groupRow.setManaged(!conversationVisible);
    }

    void clear() {
        conversation = null;
        actions = null;
        if (conversationRow != null) {
            conversationRow.setVisible(false);
            conversationRow.setManaged(false);
        }
        if (groupRow != null) {
            groupRow.setVisible(false);
            groupRow.setManaged(false);
        }
        if (rowMenu != null) rowMenu.hide();
    }

    @FXML
    private void onRowClicked(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY) activate();
    }

    @FXML
    private void onRowKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
            activate();
            event.consume();
        }
    }

    @FXML
    private void onCheckBoxClicked(MouseEvent event) {
        event.consume();
    }

    @FXML
    private void onContextMenuRequested(ContextMenuEvent event) {
        if (conversation != null && !conversation.batchMode()) {
            rowMenu.show(conversationRow, event.getScreenX(), event.getScreenY());
            event.consume();
        }
    }

    @FXML
    private void onCheckChanged() {
        if (!updating && conversation != null && actions != null) {
            actions.checked(conversation.id(), checkBox.isSelected());
        }
    }

    @FXML
    private void onDeleteRequested() {
        if (conversation != null && actions != null && !conversation.batchMode()) {
            actions.delete(conversation.id());
        }
    }

    private void activate() {
        if (conversation != null && actions != null) actions.activate(conversation.id());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        clear();
        if (conversationRow != null) {
            conversationRow.setOnMouseClicked(null);
            conversationRow.setOnKeyPressed(null);
            conversationRow.setOnContextMenuRequested(null);
        }
        if (checkBox != null) {
            checkBox.setOnAction(null);
            checkBox.setOnMouseClicked(null);
        }
        if (deleteItem != null) deleteItem.setOnAction(null);
    }

    boolean isClosed() {
        return closed;
    }
}
