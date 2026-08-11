package com.javaclaw.ui.javafx.task;

import com.javaclaw.application.task.SddTaskApplicationService.Task;

import java.util.function.Consumer;

/** 创建构造时只加载一次 FXML 的 SDD 任务 Cell。 */
public final class SddTaskCellFactory {
    public SddTaskCell create(Consumer<Task> activation) {
        return new SddTaskCell(activation);
    }
}
