package com.javaclaw.chat;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * Virtualized cell whose FXML is loaded exactly once for the lifetime of the cell.
 */
final class SidebarSessionListCell extends ListCell<SidebarSessionItem> implements AutoCloseable {

    private final ViewHandle<StackPane> handle;
    private final SidebarSessionCellController controller;
    private final SidebarSessionCellActions actions;

    SidebarSessionListCell(SpringFxmlLoader loader, SidebarSessionCellActions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
        URL resource = SidebarSessionListCell.class.getResource(
                "/fxml/chat/sidebar-session-cell.fxml");
        try {
            handle = loader.load(Objects.requireNonNull(resource, "sidebar-session-cell.fxml"));
        } catch (IOException failure) {
            throw new IllegalStateException("无法加载侧边栏会话单元格", failure);
        }
        controller = handle.controller(SidebarSessionCellController.class);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setGraphic(handle.root());
    }

    @Override
    protected void updateItem(SidebarSessionItem item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            controller.clear();
            setGraphic(null);
        } else {
            controller.show(item, actions);
            setGraphic(handle.root());
        }
    }

    @Override
    public void close() {
        setGraphic(null);
        handle.close();
    }
}
