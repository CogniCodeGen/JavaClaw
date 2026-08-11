package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.app.UIHelper;
import com.javaclaw.application.mcp.McpManagementApplicationService.LogSnapshot;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 创建只读 FXML MCP 日志弹窗。 */
public final class McpLogDialogFactory {
    private static final URL VIEW = Objects.requireNonNull(
            McpLogDialogFactory.class.getResource("/fxml/mcp/mcp-log-dialog.fxml"),
            "缺少 mcp-log-dialog.fxml");
    private final SpringFxmlLoader loader;
    private final UIHelper ui;

    public McpLogDialogFactory(SpringFxmlLoader loader, UIHelper ui) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    void show(Window owner, LogSnapshot log) {
        ViewHandle<VBox> handle;
        try { handle = loader.load(VIEW); }
        catch (IOException failure) { throw new UncheckedIOException("加载 MCP 日志弹窗失败", failure); }
        try {
            handle.controller(McpLogDialogController.class).configure(log);
            Dialog<Void> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle("MCP 服务器日志：" + log.serverName());
            dialog.setHeaderText(null);
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
            dialog.getDialogPane().setPrefWidth(640);
            ui.styleDialog(dialog);
            dialog.showAndWait();
        } finally { handle.close(); }
    }
}
