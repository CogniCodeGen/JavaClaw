package com.javaclaw.application.schedule;

import com.javaclaw.application.schedule.ScheduleApplicationService.SaveCommand;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;

import java.util.Objects;

/** 定时任务不可变快照与写命令之间的无状态映射。 */
public final class ScheduleCommands {

    private ScheduleCommands() { }

    public static SaveCommand copyOf(Task task) {
        Objects.requireNonNull(task, "task");
        return new SaveCommand(task.id(), task.name(), task.description(), task.triggerType(),
                task.intervalValue(), task.intervalUnit(), task.dailyTime(), task.cronExpression(),
                task.onceDateTime(), task.prompt(), task.enabled(), task.version(),
                task.notifyEnabled(), task.notifyChannel(), task.unattendedToolsAuthorized(), false);
    }
}
