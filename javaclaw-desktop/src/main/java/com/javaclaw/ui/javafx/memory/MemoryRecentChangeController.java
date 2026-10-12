package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.ChangeItem;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** 总览中的紧凑近期变更行。 */
public final class MemoryRecentChangeController {
    @FXML private Label badge;
    @FXML private Label detail;
    @FXML private Label time;

    void configure(ChangeItem item) {
        MemoryChangePresentation presentation = MemoryChangePresentation.of(item.operation());
        badge.setText(presentation.label());
        badge.getStyleClass().add(presentation.styleClass());
        String value = item.detail().isBlank()
                ? item.type() + " " + item.targetId() : item.detail();
        detail.setText(MemoryUiText.oneLine(value, 48));
        time.setText(MemoryUiText.formatTime(item.timestamp()));
    }
}
