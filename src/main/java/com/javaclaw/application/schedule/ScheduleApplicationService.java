package com.javaclaw.application.schedule;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作区定时任务的唯一业务入口。
 *
 * <p>实现线程安全，返回值均为不可变快照。查询和变更可能访问数据库或 Quartz，调用方必须
 * 显式使用托管 I/O 执行器。变更一旦持久化不会因调用线程随后取消而回滚；乐观锁冲突、
 * 校验失败和对象不存在分别以 Application 层异常报告。</p>
 */
public interface ScheduleApplicationService {

    Snapshot snapshot();

    Task createDraft(String name);

    OperationResult save(SaveCommand command);

    default OperationResult setEnabled(SaveCommand command, boolean enabled) {
        return setEnabled(command, enabled, DisablePolicy.CANCEL_ACTIVE);
    }

    OperationResult setEnabled(SaveCommand command, boolean enabled, DisablePolicy disablePolicy);

    OperationResult delete(String taskId);

    OperationResult runNow(String taskId, boolean allowDisabled);

    /** 订阅执行事件；回调线程不固定且不得阻塞，关闭句柄后不再接收事件。 */
    AutoCloseable observe(EventListener listener);

    @FunctionalInterface
    interface EventListener {
        void onEvent(Event event);
    }

    enum RuntimeState { PAUSED, ENABLED, QUEUED, RUNNING, BUILTIN }

    enum RunResult { STARTED, ALREADY_ACTIVE, DISABLED, NOT_FOUND, UNSUPPORTED }

    /** 停用时对当前排队/运行实例的处理策略。 */
    enum DisablePolicy { CANCEL_ACTIVE, AFTER_CURRENT_RUN }

    enum EventKind { LOG, STARTED, COMPLETED }

    record Event(EventKind kind, String taskId, String message) {
        public Event {
            kind = java.util.Objects.requireNonNull(kind, "kind");
            taskId = text(taskId);
            message = text(message);
        }
    }

    record History(String time, String status, String duration, String note) {
        public History {
            time = text(time);
            status = text(status);
            duration = text(duration);
            note = text(note);
        }
    }

    record Task(
            String id,
            String name,
            String description,
            String triggerType,
            int intervalMinutes,
            int intervalValue,
            String intervalUnit,
            String dailyTime,
            String cronExpression,
            String onceDateTime,
            String prompt,
            boolean enabled,
            long version,
            String lastRunTime,
            String lastRunStatus,
            String lastDuration,
            int runCount,
            int failCount,
            boolean notifyEnabled,
            String notifyChannel,
            boolean unattendedToolsAuthorized,
            boolean builtin,
            String triggerSummary,
            String sourceModule,
            RuntimeState runtimeState,
            LocalDateTime nextFireTime,
            boolean manuallyRunnable,
            List<History> history) {
        public Task {
            id = text(id);
            name = text(name);
            description = text(description);
            triggerType = text(triggerType);
            intervalUnit = text(intervalUnit);
            dailyTime = text(dailyTime);
            cronExpression = text(cronExpression);
            onceDateTime = text(onceDateTime);
            prompt = text(prompt);
            lastRunTime = text(lastRunTime);
            lastRunStatus = text(lastRunStatus);
            lastDuration = text(lastDuration);
            notifyChannel = text(notifyChannel);
            triggerSummary = text(triggerSummary);
            sourceModule = text(sourceModule);
            runtimeState = runtimeState == null ? RuntimeState.PAUSED : runtimeState;
            history = List.copyOf(history == null ? List.of() : history);
        }

        public boolean active() {
            return runtimeState == RuntimeState.RUNNING || runtimeState == RuntimeState.QUEUED;
        }

        public String describeTrigger() {
            if (!triggerSummary.isBlank()) return triggerSummary;
            return switch (triggerType) {
                case "once" -> "一次性 " + (onceDateTime.isBlank() ? "未设置" : onceDateTime);
                case "daily" -> "每天 " + (dailyTime.isBlank() ? "09:00" : dailyTime);
                case "cron" -> "Cron " + cronExpression;
                default -> "每 " + Math.max(1, intervalValue > 0
                        ? intervalValue : intervalMinutes) + " " + switch (intervalUnit) {
                    case "hour" -> "小时";
                    case "day" -> "天";
                    default -> "分钟";
                };
            };
        }
    }

    record Snapshot(List<Task> tasks) {
        public Snapshot {
            tasks = List.copyOf(tasks == null ? List.of() : tasks);
        }

        public Task require(String id) {
            String wanted = text(id);
            return tasks.stream().filter(task -> task.id().equals(wanted)).findFirst()
                    .orElseThrow(() -> new com.javaclaw.application.error.NotFoundException(
                            "未找到定时任务：" + wanted));
        }
    }

    record SaveCommand(
            String id,
            String name,
            String description,
            String triggerType,
            int intervalValue,
            String intervalUnit,
            String dailyTime,
            String cronExpression,
            String onceDateTime,
            String prompt,
            boolean enabled,
            long version,
            boolean notifyEnabled,
            String notifyChannel,
            boolean unattendedToolsAuthorized,
            boolean draft) {
        public SaveCommand {
            id = text(id);
            name = text(name);
            description = text(description);
            triggerType = text(triggerType);
            intervalUnit = text(intervalUnit);
            dailyTime = text(dailyTime);
            cronExpression = text(cronExpression);
            onceDateTime = text(onceDateTime);
            prompt = text(prompt);
            notifyChannel = text(notifyChannel);
        }
    }

    record OperationResult(Snapshot snapshot, RunResult runResult, String message) {
        public OperationResult {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            message = text(message);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
