package com.javaclaw.schedule;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 工作区内只读系统任务目录及其可选手动动作。 */
final class BuiltinScheduleRegistry {

    static final String PREFIX = "sys:";

    private final List<ScheduledTask> tasks = buildTasks();
    private final ConcurrentHashMap<String, ScheduleManager.BuiltinRunner> actions =
            new ConcurrentHashMap<>();

    boolean contains(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    List<ScheduledTask> snapshots() {
        return tasks.stream().map(ScheduledTask::copy).toList();
    }

    ScheduledTask find(String id) {
        if (id == null) return null;
        return tasks.stream().filter(task -> id.equals(task.getId())).findFirst().orElse(null);
    }

    ScheduledTask snapshot(String id) {
        ScheduledTask task = find(id);
        return task == null ? null : task.copy();
    }

    AutoCloseable register(String id, ScheduleManager.BuiltinRunner runner) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runner, "runner");
        if (find(id) == null) {
            throw new IllegalArgumentException("未知的系统内置任务：" + id);
        }
        actions.put(id, runner);
        return () -> actions.remove(id, runner);
    }

    ScheduleManager.BuiltinRunner action(String id) {
        return actions.get(id);
    }

    boolean hasAction(String id) {
        return actions.containsKey(id);
    }

    boolean record(String id, boolean success, long durationMs, String note) {
        ScheduledTask task = find(id);
        if (task == null) return false;
        task.recordExecution(success);
        String duration = durationMs <= 0 ? "—"
                : durationMs < 1000 ? durationMs + "ms" : "%.1fs".formatted(durationMs / 1000.0);
        task.setLastDuration(duration);
        String summary = note == null || note.isBlank() ? "—"
                : note.length() > 60 ? note.substring(0, 60) + "…" : note;
        task.addExecRecord(new ScheduledTask.ExecRecord(
                LocalDateTime.now().format(ScheduledTask.FORMATTER),
                success ? "成功" : "失败", duration, summary));
        return true;
    }

    private static List<ScheduledTask> buildTasks() {
        List<ScheduledTask> result = new ArrayList<>();
        result.add(task(PREFIX + "cmd-session-cleanup", "命令会话清理",
                "定期清理空闲的命令行会话，回收 PTY/进程资源",
                "每 5 分钟", "CommandSessionManager"));
        result.add(task(PREFIX + "habit-review", "习惯回顾",
                "跨轮归纳重复模式为「习惯偏好」事实，补逐轮蒸馏只见单轮的盲区",
                "每轮对话后检查（间隔 ≥24h 且情景数达标才归纳）", "HabitReviewer"));
        return result;
    }

    private static ScheduledTask task(
            String id, String name, String description, String trigger, String source) {
        ScheduledTask task = new ScheduledTask(id, name);
        task.setDescription(description);
        task.setBuiltin(true);
        task.setEnabled(true);
        task.setTriggerSummary(trigger);
        task.setSourceModule(source);
        task.setPrompt("");
        return task;
    }
}
