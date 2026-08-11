package com.javaclaw.infrastructure.schedule;

import com.javaclaw.application.error.ConflictException;
import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.schedule.ScheduleApplicationService.Event;
import com.javaclaw.application.schedule.ScheduleApplicationService.EventKind;
import com.javaclaw.application.schedule.ScheduleApplicationService.History;
import com.javaclaw.application.schedule.ScheduleApplicationService.RunResult;
import com.javaclaw.application.schedule.ScheduleApplicationService.RuntimeState;
import com.javaclaw.application.schedule.ScheduleApplicationService.SaveCommand;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import com.javaclaw.application.schedule.SchedulePort;
import com.javaclaw.schedule.ScheduleConflictException;
import com.javaclaw.schedule.ScheduleManager;
import com.javaclaw.schedule.ScheduledTask;

import java.util.List;
import java.util.Objects;

/** 将现有 Quartz 定时任务运行时适配到 Application 端口。 */
public final class ScheduleManagerAdapter implements SchedulePort {

    private final ScheduleManager manager;

    public ScheduleManagerAdapter(ScheduleManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public List<Task> list() {
        return manager.getAllTasks().stream().map(this::toTask).toList();
    }

    @Override
    public Task createDraft(String name) {
        return toTask(manager.createDraft(name));
    }

    @Override
    public Task save(SaveCommand command) {
        try {
            ScheduledTask mapped = toScheduledTask(command);
            return toTask(command.draft()
                    ? manager.saveNewTask(mapped) : manager.updateTask(mapped));
        } catch (ScheduleConflictException conflict) {
            throw new ConflictException("定时任务已被其他操作修改，请刷新后重试");
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException(invalid.getMessage());
        }
    }

    @Override
    public Task setEnabled(SaveCommand command, boolean enabled) {
        try {
            // updateTask 同时保存页面编辑并协调调度；停用时会取消当前运行。
            return toTask(manager.updateTask(toScheduledTask(command)));
        } catch (ScheduleConflictException conflict) {
            throw new ConflictException("定时任务已被其他操作修改，请刷新后重试");
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException(invalid.getMessage());
        }
    }

    @Override
    public void delete(String taskId) {
        ScheduledTask existing = manager.getTask(taskId);
        if (existing == null) throw new NotFoundException("未找到定时任务：" + taskId);
        try {
            manager.deleteTask(taskId);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException(invalid.getMessage());
        }
    }

    @Override
    public RunResult runNow(String taskId, boolean allowDisabled) {
        return switch (manager.runNow(taskId, allowDisabled)) {
            case STARTED -> RunResult.STARTED;
            case ALREADY_ACTIVE -> RunResult.ALREADY_ACTIVE;
            case DISABLED -> RunResult.DISABLED;
            case NOT_FOUND -> RunResult.NOT_FOUND;
            case UNSUPPORTED -> RunResult.UNSUPPORTED;
        };
    }

    @Override
    public AutoCloseable observe(com.javaclaw.application.schedule.ScheduleApplicationService.EventListener listener) {
        return manager.subscribe(new ScheduleManager.TaskListener() {
            @Override public void onLog(String taskName, String message) {
                listener.onEvent(new Event(EventKind.LOG, taskName, message));
            }

            @Override public void onExecutionStarted(String taskId) {
                listener.onEvent(new Event(EventKind.STARTED, taskId, ""));
            }

            @Override public void onExecutionCompleted(String taskId) {
                listener.onEvent(new Event(EventKind.COMPLETED, taskId, ""));
            }
        });
    }

    private Task toTask(ScheduledTask source) {
        boolean running = manager.isRunning(source.getId());
        boolean active = manager.isActive(source.getId());
        RuntimeState state = source.isBuiltin() ? RuntimeState.BUILTIN
                : running ? RuntimeState.RUNNING
                : active ? RuntimeState.QUEUED
                : source.isEnabled() ? RuntimeState.ENABLED : RuntimeState.PAUSED;
        List<History> history = source.getExecRecords() == null ? List.of()
                : source.getExecRecords().stream().map(record -> new History(
                        record.getTime(), record.getStatus(), record.getDuration(), record.getNote()))
                .toList();
        return new Task(source.getId(), source.getName(), source.getDescription(),
                source.getTriggerType(), source.getIntervalMinutes(), source.getIntervalValue(),
                source.getIntervalUnit(), source.getDailyTime(), source.getCronExpression(),
                source.getOnceDateTime(), source.getPrompt(), source.isEnabled(), source.getVersion(),
                source.getLastRunTime(), source.getLastRunStatus(), source.getLastDuration(),
                source.getRunCount(), source.getFailCount(), source.isNotifyEnabled(),
                source.getNotifyChannel(), source.isUnattendedToolsAuthorized(), source.isBuiltin(),
                source.getTriggerSummary(), source.getSourceModule(), state,
                source.isBuiltin() ? null : manager.getNextFireTime(source.getId()),
                source.isBuiltin() ? manager.hasBuiltinAction(source.getId()) : !active, history);
    }

    private static ScheduledTask toScheduledTask(SaveCommand source) {
        ScheduledTask target = new ScheduledTask(source.id(), source.name());
        target.setDescription(source.description());
        target.setTriggerType(source.triggerType());
        target.setIntervalValue(source.intervalValue());
        target.setIntervalUnit(source.intervalUnit());
        target.recomputeIntervalMinutes();
        target.setDailyTime(source.dailyTime());
        target.setCronExpression(source.cronExpression());
        target.setOnceDateTime(source.onceDateTime());
        target.setPrompt(source.prompt());
        target.setEnabled(source.enabled());
        target.setVersion(source.version());
        target.setNotifyEnabled(source.notifyEnabled());
        target.setNotifyChannel(source.notifyChannel());
        target.setUnattendedToolsAuthorized(source.unattendedToolsAuthorized());
        return target;
    }
}
