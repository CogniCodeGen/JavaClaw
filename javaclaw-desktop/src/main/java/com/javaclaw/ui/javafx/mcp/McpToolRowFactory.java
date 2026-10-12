package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.Tool;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 创建可关闭的 MCP 工具 FXML 行。 */
public final class McpToolRowFactory {
    private static final URL VIEW = Objects.requireNonNull(
            McpToolRowFactory.class.getResource("/fxml/mcp/mcp-tool-row.fxml"),
            "缺少 mcp-tool-row.fxml");
    private final SpringFxmlLoader loader;

    public McpToolRowFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    Row create(Tool tool) {
        try {
            ViewHandle<VBox> handle = loader.load(VIEW);
            handle.controller(McpToolRowController.class).configure(tool);
            return new Row(handle.root(), handle);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载 MCP 工具行失败", failure);
        }
    }

    record Row(VBox root, ViewHandle<VBox> handle) implements AutoCloseable {
        @Override public void close() { handle.close(); }
    }
}
