package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.Tool;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** MCP 工具行 Controller；仅把不可变工具值映射到 FXML。 */
public final class McpToolRowController {
    @FXML private Label nameLabel;
    @FXML private Label descriptionLabel;

    void configure(Tool tool) {
        nameLabel.setText(tool.name());
        descriptionLabel.setText(tool.description());
        boolean described = !tool.description().isBlank();
        descriptionLabel.setVisible(described);
        descriptionLabel.setManaged(described);
    }
}
