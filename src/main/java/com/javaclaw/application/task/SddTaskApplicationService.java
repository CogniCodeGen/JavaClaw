package com.javaclaw.application.task;

import com.javaclaw.task.sdd.run.SddTaskState;
import com.javaclaw.task.sdd.spec.OpenSpecChange;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SDD 托管任务的工作区业务入口。
 *
 * <p>查询只返回不可变快照，调用方不得保留内部运行模型。标题生成、持久化与启动可能阻塞，
 * JavaFX 与 Shell 必须从工作区托管 I/O 任务调用。取消与暂停幂等；运行时切换会使在途结果失效。</p>
 */
public interface SddTaskApplicationService {

    Snapshot snapshot();

    Task require(String taskId);

    String generateTitle(String description);

    Task create(CreateCommand command);

    void start(String taskId, String completionStamp);

    void resume(String taskId, String completionStamp);

    void pause(String taskId);

    void cancel(String taskId);

    void delete(String taskId);

    Task updateTokenBudget(String taskId, long newBudget);

    Optional<OpenSpecChange> specification(String taskId);

    /** 订阅任务快照和日志；句柄关闭后不再回调。 */
    AutoCloseable observe(EventListener listener);

    /** 工作区替换前暂停所有在途任务。 */
    void suspendForRuntimeTransition();

    @FunctionalInterface
    interface EventListener {
        void onEvent(Event event);
    }

    sealed interface Event permits Event.Changed, Event.Log {
        record Changed(Task task) implements Event {}
        record Log(String taskId, String taskTitle, String message) implements Event {}
    }

    record CreateCommand(
            String title,
            String description,
            String capabilities,
            String workDir,
            long tokenBudget,
            String notificationChannel,
            String createdAt) {
    }

    record Snapshot(List<Task> tasks) {
        public Snapshot {
            tasks = List.copyOf(tasks == null ? List.of() : tasks);
        }

        public long activeCount() {
            return tasks.stream().filter(task -> task.state() == SddTaskState.RUNNING).count();
        }
    }

    record Task(
            String id,
            String title,
            String description,
            String workDir,
            String capabilities,
            long tokenBudget,
            String notificationChannel,
            String createdAt,
            String updatedAt,
            SddTaskState state,
            int progress,
            String result,
            long totalInputTokens,
            long totalOutputTokens,
            Map<String, Long> phaseInputTokens,
            Map<String, Long> phaseOutputTokens) {
        public Task {
            phaseInputTokens = Map.copyOf(phaseInputTokens == null ? Map.of() : phaseInputTokens);
            phaseOutputTokens = Map.copyOf(phaseOutputTokens == null ? Map.of() : phaseOutputTokens);
        }

        public long totalTokens() {
            return totalInputTokens + totalOutputTokens;
        }
    }
}
