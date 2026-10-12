package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.ChangeItem;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/** 审计日志表格行。 */
public final class MemoryChangeRowController {
    @FXML private HBox root;
    @FXML private Label badge;
    @FXML private Label type;
    @FXML private Label detail;
    @FXML private Label time;

    void configure(ChangeItem item, boolean first) {
        MemoryChangePresentation presentation = MemoryChangePresentation.of(item.operation());
        badge.setText(presentation.label());
        badge.getStyleClass().add(presentation.styleClass());
        type.setText(item.type());
        detail.setText(MemoryUiText.oneLine(item.detail(), 120));
        time.setText(MemoryUiText.formatTime(item.timestamp()));
        if (first) root.setStyle("-fx-border-width: 0;");
    }
}
