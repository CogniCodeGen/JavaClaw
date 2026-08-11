package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.application.schedule.ScheduleApplicationService.History;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.GridPane;

/** 虚拟化执行历史 Cell；状态样式不依赖 Java 布局构造。 */
public final class ScheduleHistoryCell extends ListCell<History> {

    @FXML private GridPane root;
    @FXML private Label timeLabel;
    @FXML private Label statusLabel;
    @FXML private Label durationLabel;
    @FXML private Label noteLabel;

    ScheduleHistoryCell() {
        GridPane content = EmbeddedFxmlLoader.load(ScheduleHistoryCell.class.getResource(
                "/fxml/schedule/schedule-history-cell.fxml"), this, GridPane.class);
        if (content != root) throw new IllegalStateException("ScheduleHistoryCell FXML 根节点不一致");
    }

    @Override
    protected void updateItem(History history, boolean empty) {
        super.updateItem(history, empty);
        setText(null);
        if (empty || history == null) {
            setGraphic(null);
            return;
        }
        timeLabel.setText(history.time());
        statusLabel.setText(history.status());
        durationLabel.setText(history.duration());
        noteLabel.setText(history.note().isBlank() ? "—" : history.note());
        statusLabel.getStyleClass().removeAll("jc-badge-ok", "jc-badge-fail", "jc-badge-stopped");
        statusLabel.getStyleClass().add("失败".equals(history.status()) ? "jc-badge-fail"
                : "已取消".equals(history.status()) ? "jc-badge-stopped" : "jc-badge-ok");
        noteLabel.getStyleClass().remove("schedule-failure-text");
        if ("失败".equals(history.status())) noteLabel.getStyleClass().add("schedule-failure-text");
        setGraphic(root);
    }
}
