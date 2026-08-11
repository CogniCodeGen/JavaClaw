package com.javaclaw.application.schedule;

import com.javaclaw.application.error.ValidationException;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

/** 定时任务查询、编辑和运行流程；不保存页面状态。 */
public final class ScheduleUseCase implements ScheduleApplicationService {

    private static final DateTimeFormatter ONCE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final SchedulePort schedules;

    public ScheduleUseCase(SchedulePort schedules) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot(schedules.list());
    }

    @Override
    public Task createDraft(String name) {
        return schedules.createDraft(required(name, "任务名称"));
    }

    @Override
    public OperationResult save(SaveCommand command) {
        SaveCommand checked = validate(command);
        Task saved = schedules.save(checked);
        return result(null, "已保存", saved.id());
    }

    @Override
    public OperationResult setEnabled(SaveCommand command, boolean enabled) {
        SaveCommand checked = validate(new SaveCommand(command.id(), command.name(),
                command.description(), command.triggerType(), command.intervalValue(),
                command.intervalUnit(), command.dailyTime(), command.cronExpression(),
                command.onceDateTime(), command.prompt(), enabled, command.version(),
                command.notifyEnabled(), command.notifyChannel(),
                command.unattendedToolsAuthorized(), command.draft()));
        if (checked.draft()) {
            throw new ValidationException("草稿必须先保存，之后才能切换运行状态");
        }
        Task changed = schedules.setEnabled(checked, enabled);
        return result(null, enabled ? "已启用" : "已暂停", changed.id());
    }

    @Override
    public OperationResult delete(String taskId) {
        String id = required(taskId, "任务 ID");
        schedules.delete(id);
        return new OperationResult(snapshot(), null, "任务已删除");
    }

    @Override
    public OperationResult runNow(String taskId, boolean allowDisabled) {
        String id = required(taskId, "任务 ID");
        RunResult run = schedules.runNow(id, allowDisabled);
        String message = switch (run) {
            case STARTED -> "已加入执行队列…";
            case ALREADY_ACTIVE -> "任务已在运行或排队";
            case DISABLED -> "任务已暂停";
            case NOT_FOUND -> "任务不存在";
            case UNSUPPORTED -> "当前任务不支持手动执行";
        };
        return new OperationResult(snapshot(), run, message);
    }

    @Override
    public AutoCloseable observe(EventListener listener) {
        return schedules.observe(Objects.requireNonNull(listener, "listener"));
    }

    private OperationResult result(RunResult run, String message, String expectedId) {
        Snapshot current = snapshot();
        current.require(expectedId);
        return new OperationResult(current, run, message);
    }

    private static SaveCommand validate(SaveCommand command) {
        Objects.requireNonNull(command, "command");
        String id = required(command.id(), "任务 ID");
        String name = required(command.name(), "任务名称");
        String prompt = required(command.prompt(), "任务提示词");
        String trigger = command.triggerType().isBlank() ? "interval" : command.triggerType();
        if (!List.of("once", "interval", "daily", "cron").contains(trigger)) {
            throw new ValidationException("不支持的触发方式：" + trigger);
        }
        int interval = Math.max(1, command.intervalValue());
        String unit = List.of("minute", "hour", "day").contains(command.intervalUnit())
                ? command.intervalUnit() : "minute";
        if (command.enabled()) {
            validateTrigger(trigger, interval, command.dailyTime(), command.cronExpression(),
                    command.onceDateTime());
        }
        return new SaveCommand(id, name, command.description(), trigger, interval, unit,
                command.dailyTime().strip(), command.cronExpression().strip(),
                command.onceDateTime().strip(), prompt, command.enabled(), command.version(),
                command.notifyEnabled(), command.notifyChannel().isBlank()
                        ? "none" : command.notifyChannel(),
                command.unattendedToolsAuthorized(), command.draft());
    }

    private static void validateTrigger(String trigger, int interval, String daily,
                                        String cron, String once) {
        try {
            switch (trigger) {
                case "once" -> LocalDateTime.parse(required(once, "一次性运行时间"), ONCE_FORMAT);
                case "daily" -> LocalTime.parse(required(daily, "每日运行时间"));
                case "cron" -> {
                    String expression = required(cron, "Cron 表达式");
                    if (!org.quartz.CronExpression.isValidExpression(expression)) {
                        throw new ValidationException("Cron 表达式无效，需使用 Quartz 6 段格式");
                    }
                }
                case "interval" -> {
                    if (interval < 1) throw new ValidationException("运行间隔必须大于 0");
                }
                default -> throw new ValidationException("不支持的触发方式：" + trigger);
            }
        } catch (DateTimeParseException failure) {
            throw new ValidationException("once".equals(trigger)
                    ? "一次性运行时间格式应为 yyyy-MM-dd HH:mm"
                    : "每日运行时间格式应为 HH:mm");
        }
    }

    private static String required(String value, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) throw new ValidationException(label + "不能为空");
        return normalized;
    }
}
