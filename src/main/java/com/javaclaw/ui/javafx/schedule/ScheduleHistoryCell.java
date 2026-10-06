package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.application.schedule.ScheduleApplicationService.History;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;

/** 虚拟化执行历史 Cell；状态样式不依赖 Java 布局构造。 */
public final class ScheduleHistoryCell extends ListCell<History> {

    @FXML private GridPane root;
    @FXML private Label timeLabel;
    @FXML private Label statusLabel;
    @FXML private Label durationLabel;
    @FXML private Label noteLabel;
    private final Tooltip noteTooltip = new Tooltip();

    ScheduleHistoryCell() {
        GridPane content = EmbeddedFxmlLoader.load(ScheduleHistoryCell.class.getResource(
                "/fxml/schedule/schedule-history-cell.fxml"), this, GridPane.class);
        if (content != root) throw new IllegalStateException("ScheduleHistoryCell FXML 根节点不一致");
        // VirtualFlow supplies the viewport width, including scrollbar space.
        // Do not let a long note establish a wider preferred cell breadth.
        setMinWidth(0);
        setPrefWidth(0);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        noteTooltip.setWrapText(true);
        noteTooltip.setMaxWidth(480);
    }

    @Override
    protected void updateItem(History history, boolean empty) {
        super.updateItem(history, empty);
        setText(null);
        if (empty || history == null) {
            noteTooltip.setText("");
            noteLabel.setTooltip(null);
            setGraphic(null);
            return;
        }
        timeLabel.setText(history.time());
        statusLabel.setText(ScheduleOutcomePresentation.label(history.executionStatus(), history.taskResult()));
        durationLabel.setText(history.duration());
        noteLabel.setText(history.note().isBlank() ? "—" : history.note());
        noteTooltip.setText(history.note());
        noteLabel.setTooltip(history.note().isBlank() ? null : noteTooltip);
        statusLabel.getStyleClass().removeAll("jc-badge-ok", "jc-badge-fail", "jc-badge-stopped");
        statusLabel.getStyleClass().add(history.executionStatus()
                    == com.javaclaw.application.schedule.ScheduleExecutionStatus.FAILURE ? "jc-badge-fail"
                : history.executionStatus()
                    == com.javaclaw.application.schedule.ScheduleExecutionStatus.CANCELLED ? "jc-badge-stopped"
                : history.taskResult() != null
                    && history.taskResult().outcome() == com.javaclaw.framework.api.TaskOutcome.VERIFIED_COMPLETE
                    ? "jc-badge-ok" : "jc-badge-stopped");
        noteLabel.getStyleClass().remove("schedule-failure-text");
        if (history.executionStatus()
                == com.javaclaw.application.schedule.ScheduleExecutionStatus.FAILURE) {
            noteLabel.getStyleClass().add("schedule-failure-text");
        }
        setGraphic(root);
    }
}
