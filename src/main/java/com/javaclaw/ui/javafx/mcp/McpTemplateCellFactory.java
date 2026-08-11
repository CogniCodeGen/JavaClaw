package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.Template;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 创建构造时只加载一次 FXML、更新时只替换值的模板 ListCell。 */
public final class McpTemplateCellFactory {
    private static final URL VIEW = Objects.requireNonNull(
            McpTemplateCellFactory.class.getResource("/fxml/mcp/mcp-template-cell.fxml"),
            "缺少 mcp-template-cell.fxml");
    private final SpringFxmlLoader loader;

    public McpTemplateCellFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    Cell create() { return new Cell(loader); }

    static final class Cell extends ListCell<Template> implements AutoCloseable {
        private final ViewHandle<VBox> handle;
        private final McpTemplateCellController controller;

        Cell(SpringFxmlLoader loader) {
            try {
                handle = loader.load(VIEW);
                controller = handle.controller(McpTemplateCellController.class);
            } catch (IOException failure) {
                throw new UncheckedIOException("加载 MCP 模板列表项失败", failure);
            }
        }

        @Override
        protected void updateItem(Template template, boolean empty) {
            super.updateItem(template, empty);
            if (empty || template == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            controller.apply(template);
            setGraphic(handle.root());
            setText(null);
        }

        @Override public void close() { handle.close(); }
    }
}
