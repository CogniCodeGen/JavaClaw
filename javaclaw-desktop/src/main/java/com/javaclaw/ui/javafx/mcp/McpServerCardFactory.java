package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.Server;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.BiConsumer;

/** 创建 MCP 服务器卡片及其完整 Controller 生命周期。 */
public final class McpServerCardFactory {
    private static final URL VIEW = Objects.requireNonNull(
            McpServerCardFactory.class.getResource("/fxml/mcp/mcp-server-card.fxml"),
            "缺少 mcp-server-card.fxml");
    private final SpringFxmlLoader loader;

    public McpServerCardFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    Card create(Server server, BiConsumer<String, McpServerCardController.Action> action) {
        try {
            ViewHandle<VBox> handle = loader.load(VIEW);
            handle.controller(McpServerCardController.class).configure(server, action);
            return new Card(handle.root(), handle);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载 MCP 服务器卡片失败", failure);
        }
    }

    record Card(VBox root, ViewHandle<VBox> handle) implements AutoCloseable {
        @Override public void close() { handle.close(); }
    }
}
