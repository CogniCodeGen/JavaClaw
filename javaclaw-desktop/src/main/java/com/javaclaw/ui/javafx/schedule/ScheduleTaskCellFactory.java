package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.application.schedule.ScheduleApplicationService.Task;

import java.util.function.Consumer;

/** 创建构造时只加载一次 FXML 的定时任务列表 Cell。 */
public final class ScheduleTaskCellFactory {
    public ScheduleTaskCell create(Consumer<Task> activation) {
        return new ScheduleTaskCell(activation);
    }
}
