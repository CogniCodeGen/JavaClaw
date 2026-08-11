package com.javaclaw.application.task;

import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.ValidationException;
import com.javaclaw.task.sdd.run.SddManagedTask;
import com.javaclaw.task.sdd.run.SddTaskListener;
import com.javaclaw.task.sdd.run.SddTaskManager;
import com.javaclaw.task.sdd.spec.OpenSpecChange;

import java.util.Objects;
import java.util.Optional;

/** 校验并编排 SDD 任务命令，不保存 UI 状态。 */
public final class SddTaskUseCase implements SddTaskApplicationService {

    private final SddTaskManager tasks;

    public SddTaskUseCase(SddTaskManager tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot(tasks.list().stream().map(SddTaskUseCase::snapshotOf).toList());
    }

    @Override
    public Task require(String taskId) {
        return snapshotOf(requireManaged(taskId));
    }

    @Override
    public String generateTitle(String description) {
        return tasks.generateTitle(required(description, "任务描述"));
    }

    @Override
    public Task create(CreateCommand command) {
        Objects.requireNonNull(command, "command");
        String description = required(command.description(), "任务描述");
        String title = required(command.title(), "任务标题");
        SddManagedTask created = tasks.create(title, description,
                text(command.capabilities()).isBlank() ? "auto" : command.capabilities().strip(),
                nullableText(command.workDir()), Math.max(0, command.tokenBudget()),
                text(command.notificationChannel()).isBlank()
                        ? "none" : command.notificationChannel().strip(),
                required(command.createdAt(), "创建时间"));
        return snapshotOf(created);
    }

    @Override
    public void start(String taskId, String completionStamp) {
        tasks.start(requireManaged(taskId).id, required(completionStamp, "完成时间"));
    }

    @Override
    public void resume(String taskId, String completionStamp) {
        tasks.resume(requireManaged(taskId).id, required(completionStamp, "完成时间"));
    }

    @Override
    public void pause(String taskId) {
        tasks.pause(requireManaged(taskId).id);
    }

    @Override
    public void cancel(String taskId) {
        tasks.cancel(requireManaged(taskId).id);
    }

    @Override
    public void delete(String taskId) {
        tasks.delete(requireManaged(taskId).id);
    }

    @Override
    public Task updateTokenBudget(String taskId, long newBudget) {
        SddManagedTask task = requireManaged(taskId);
        tasks.updateTokenBudget(task.id, Math.max(0, newBudget));
        return require(task.id);
    }

    @Override
    public Optional<OpenSpecChange> specification(String taskId) {
        requireManaged(taskId);
        return tasks.readChange(taskId);
    }

    @Override
    public AutoCloseable observe(EventListener listener) {
        EventListener checked = Objects.requireNonNull(listener, "listener");
        return tasks.subscribe(new SddTaskListener() {
            @Override
            public void onTaskChanged(SddManagedTask task) {
                checked.onEvent(new Event.Changed(snapshotOf(task)));
            }

            @Override
            public void onLog(String taskId, String taskTitle, String message) {
                checked.onEvent(new Event.Log(taskId, taskTitle, message));
            }
        });
    }

    @Override
    public void suspendForRuntimeTransition() {
        tasks.suspendForRuntimeTransition();
    }

    private SddManagedTask requireManaged(String taskId) {
        String id = required(taskId, "任务 ID");
        SddManagedTask task = tasks.get(id);
        if (task == null) throw new NotFoundException("未找到托管任务：" + id);
        return task;
    }

    private static Task snapshotOf(SddManagedTask task) {
        return new Task(task.id, task.title, task.description, task.workDir, task.capabilities,
                task.tokenBudget, task.notificationChannel, task.createdAt, task.updatedAt,
                task.state, task.progress, task.result, task.totalInputTokens,
                task.totalOutputTokens, task.phaseInputTokens, task.phaseOutputTokens);
    }

    private static String required(String value, String label) {
        String checked = text(value).strip();
        if (checked.isEmpty()) throw new ValidationException(label + "不能为空");
        return checked;
    }

    private static String nullableText(String value) {
        String checked = text(value).strip();
        return checked.isEmpty() ? null : checked;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
