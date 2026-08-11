package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 创建只加载一次 FXML 的 key/value 编辑行。 */
public final class McpKeyValueRowFactory {
    private static final URL VIEW = Objects.requireNonNull(
            McpKeyValueRowFactory.class.getResource("/fxml/mcp/mcp-key-value-row.fxml"),
            "缺少 mcp-key-value-row.fxml");
    private final SpringFxmlLoader loader;

    public McpKeyValueRowFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    Row create(String key, String value, boolean secret, Runnable removeAction) {
        try {
            ViewHandle<HBox> handle = loader.load(VIEW);
            McpKeyValueRowController controller = handle.controller(McpKeyValueRowController.class);
            Row row = new Row(handle.root(), controller, handle);
            controller.configure(key, value, secret, removeAction);
            return row;
        } catch (IOException failure) {
            throw new UncheckedIOException("加载 MCP 参数编辑行失败", failure);
        }
    }

    record Row(HBox root, McpKeyValueRowController controller, ViewHandle<HBox> handle)
            implements AutoCloseable {
        @Override public void close() { handle.close(); }
    }
}
