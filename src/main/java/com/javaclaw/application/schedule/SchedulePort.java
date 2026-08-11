package com.javaclaw.application.schedule;

import com.javaclaw.application.schedule.ScheduleApplicationService.EventListener;
import com.javaclaw.application.schedule.ScheduleApplicationService.RunResult;
import com.javaclaw.application.schedule.ScheduleApplicationService.SaveCommand;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;

import java.util.List;

/** Application 层所需的定时任务持久化、调度和运行端口。 */
public interface SchedulePort {

    List<Task> list();

    Task createDraft(String name);

    Task save(SaveCommand command);

    Task setEnabled(SaveCommand command, boolean enabled);

    void delete(String taskId);

    RunResult runNow(String taskId, boolean allowDisabled);

    AutoCloseable observe(EventListener listener);
}
