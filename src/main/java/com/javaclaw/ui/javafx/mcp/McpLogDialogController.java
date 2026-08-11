package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.LogSnapshot;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;

/** MCP stderr 弹窗 Controller。 */
public final class McpLogDialogController {
    @FXML private Label errorTitle;
    @FXML private Label errorLabel;
    @FXML private Separator errorSeparator;
    @FXML private Label tailTitle;
    @FXML private TextArea tailArea;

    void configure(LogSnapshot log) {
        boolean hasError = !log.startupError().isBlank();
        errorLabel.setText(log.startupError());
        show(errorTitle, hasError);
        show(errorLabel, hasError);
        show(errorSeparator, hasError);
        tailTitle.setText("stderr 最近输出（" + log.stderrLines().size() + " 行）");
        tailArea.setText(log.stderrLines().isEmpty()
                ? "（无 stderr 输出）" : String.join("\n", log.stderrLines()));
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
