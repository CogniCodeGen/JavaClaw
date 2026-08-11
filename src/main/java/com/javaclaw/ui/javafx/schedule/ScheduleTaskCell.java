package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.application.schedule.ScheduleApplicationService.RuntimeState;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;

/** 虚拟化任务列表 Cell；FXML 仅在构造时加载，复用时只更新文本和样式。 */
public final class ScheduleTaskCell extends ListCell<Task> {

    private static final DateTimeFormatter NEXT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private final Consumer<Task> activation;
    @FXML private VBox root;
    @FXML private Label stateDot;
    @FXML private Label nameLabel;
    @FXML private Label tagLabel;
    @FXML private Label triggerLabel;
    @FXML private Label nextLabel;

    ScheduleTaskCell(Consumer<Task> activation) {
        this.activation = Objects.requireNonNull(activation, "activation");
        VBox content = EmbeddedFxmlLoader.load(ScheduleTaskCell.class.getResource(
                "/fxml/schedule/schedule-task-cell.fxml"), this, VBox.class);
        if (content != root) throw new IllegalStateException("ScheduleTaskCell FXML 根节点不一致");
        setOnMouseClicked(event -> {
            Task item = getItem();
            if (!isEmpty() && item != null) activation.accept(item);
        });
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                Task item = getItem();
                if (!isEmpty() && item != null) activation.accept(item);
                event.consume();
            }
        });
        selectedProperty().addListener((ignored, previous, selected) -> applySelectedStyle(selected));
    }

    @Override
    protected void updateItem(Task task, boolean empty) {
        super.updateItem(task, empty);
        setText(null);
        if (empty || task == null) {
            setGraphic(null);
            return;
        }
        boolean active = task.active();
        stateDot.setText(task.enabled() || active ? "●" : "○");
        stateDot.getStyleClass().removeAll(
                "status-dot-running", "status-dot-enabled", "status-dot-disabled");
        stateDot.getStyleClass().add(task.runtimeState() == RuntimeState.RUNNING
                ? "status-dot-running" : task.enabled()
                ? "status-dot-enabled" : "status-dot-disabled");
        nameLabel.setText(task.name());
        tagLabel.getStyleClass().removeAll("jc-badge-running", "jc-badge-stopped", "jc-badge-fail");
        if (task.builtin()) showTag("系统内置", "jc-badge-stopped");
        else if (active) showTag(task.runtimeState() == RuntimeState.RUNNING ? "运行中" : "排队中",
                "jc-badge-running");
        else if ("失败".equals(task.lastRunStatus())) showTag("⚠", "jc-badge-fail");
        else hideTag();
        triggerLabel.setText("⏱ " + task.describeTrigger());
        nextLabel.setText(nextText(task));
        root.setOpacity(task.enabled() || task.builtin() ? 1.0 : 0.65);
        setAccessibleText(task.name() + "，" + stateText(task) + "，" + task.describeTrigger());
        applySelectedStyle(isSelected());
        setGraphic(root);
    }

    private void showTag(String text, String style) {
        tagLabel.setText(text);
        tagLabel.getStyleClass().add(style);
        tagLabel.setVisible(true);
        tagLabel.setManaged(true);
    }

    private void hideTag() {
        tagLabel.setVisible(false);
        tagLabel.setManaged(false);
    }

    private void applySelectedStyle(boolean selected) {
        root.getStyleClass().remove("schedule-list-row-selected");
        if (selected) root.getStyleClass().add("schedule-list-row-selected");
    }

    private static String nextText(Task task) {
        if (task.runtimeState() == RuntimeState.RUNNING) return "执行中…";
        if (task.runtimeState() == RuntimeState.QUEUED) return "排队中…";
        if (task.builtin()) return "系统常驻 · " + task.sourceModule();
        if (!task.enabled()) return "已暂停";
        LocalDateTime next = task.nextFireTime();
        return next == null ? "等待调度…" : "下次 " + next.format(NEXT) + " · " + relative(next);
    }

    static String relative(LocalDateTime next) {
        long seconds = Duration.between(LocalDateTime.now(), next).getSeconds();
        if (seconds <= 0) return "即将运行";
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        long minutes = seconds % 3_600 / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return String.format("%dh %02dm", hours, minutes);
        return minutes > 0 ? minutes + "m" : seconds + "s";
    }

    private static String stateText(Task task) {
        return switch (task.runtimeState()) {
            case RUNNING -> "正在执行";
            case QUEUED -> "正在排队";
            case ENABLED -> "已启用";
            case BUILTIN -> "系统常驻";
            case PAUSED -> "已暂停";
        };
    }
}
