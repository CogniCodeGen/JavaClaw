package com.javaclaw.ui.javafx.task;

import com.javaclaw.application.task.SddTaskApplicationService.Task;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import com.javaclaw.task.sdd.run.SddTaskState;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Consumer;

/** 虚拟化 SDD 任务 Cell；FXML 只在构造时加载一次。 */
public final class SddTaskCell extends ListCell<Task> {

    private final Consumer<Task> activation;
    @FXML private VBox root;
    @FXML private Label stateBadge;
    @FXML private Label progressLabel;
    @FXML private Label titleLabel;
    @FXML private ProgressBar progressBar;

    SddTaskCell(Consumer<Task> activation) {
        this.activation = Objects.requireNonNull(activation, "activation");
        VBox content = EmbeddedFxmlLoader.load(SddTaskCell.class.getResource(
                "/fxml/task/sdd-task-cell.fxml"), this, VBox.class);
        if (content != root) throw new IllegalStateException("SddTaskCell FXML 根节点不一致");
        setOnMouseClicked(event -> activate());
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                activate();
                event.consume();
            }
        });
        selectedProperty().addListener((ignored, oldValue, selected) -> selectedStyle(selected));
    }

    @Override
    protected void updateItem(Task task, boolean empty) {
        super.updateItem(task, empty);
        setText(null);
        if (empty || task == null) {
            setGraphic(null);
            return;
        }
        stateBadge.setText(SddTaskFormat.badgeLabel(task.state()));
        stateBadge.getStyleClass().setAll(
                "jc-badge", "sdd-card-badge", SddTaskFormat.badgeStyle(task.state()));
        progressLabel.setText(task.progress() + "%");
        titleLabel.setText(task.title());
        progressBar.setProgress(task.progress() / 100.0);
        progressBar.getStyleClass().remove("sdd-card-bar-amber");
        if (task.state() == SddTaskState.NEEDS_HUMAN) {
            progressBar.getStyleClass().add("sdd-card-bar-amber");
        }
        root.getStyleClass().remove("sdd-task-card-terminal");
        if (task.state().isTerminal()) root.getStyleClass().add("sdd-task-card-terminal");
        setAccessibleText(task.title() + "，" + task.state().label()
                + "，进度 " + task.progress() + "%");
        selectedStyle(isSelected());
        setGraphic(root);
    }

    private void activate() {
        Task task = getItem();
        if (!isEmpty() && task != null) activation.accept(task);
    }

    private void selectedStyle(boolean selected) {
        root.getStyleClass().remove("sdd-task-card-selected");
        if (selected) root.getStyleClass().add("sdd-task-card-selected");
    }
}
