package com.javaclaw.application.schedule;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.schedule.ScheduleApplicationService.EventListener;
import com.javaclaw.application.schedule.ScheduleApplicationService.RunResult;
import com.javaclaw.application.schedule.ScheduleApplicationService.RuntimeState;
import com.javaclaw.application.schedule.ScheduleApplicationService.SaveCommand;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleUseCaseTest {

    private final FakePort port = new FakePort();
    private final ScheduleUseCase useCase = new ScheduleUseCase(port);

    @Test
    void validatesAndSavesImmutableDraft() {
        Task draft = useCase.createDraft("日报");
        SaveCommand command = command(draft, "daily", "09:30", "", "", true);

        var result = useCase.save(command);

        assertEquals("已保存", result.message());
        assertEquals("09:30", result.snapshot().require(draft.id()).dailyTime());
        assertTrue(port.saved);
    }

    @Test
    void rejectsInvalidEnabledCronBeforeCallingPort() {
        Task draft = useCase.createDraft("巡检");
        SaveCommand command = command(draft, "cron", "", "bad cron", "", true);

        assertThrows(ValidationException.class, () -> useCase.save(command));
        assertTrue(!port.saved);
    }

    @Test
    void pausedTaskMayKeepIncompleteTriggerUntilItIsEnabled() {
        Task draft = useCase.createDraft("稍后配置");
        SaveCommand command = command(draft, "cron", "", "", "", false);

        assertEquals("已保存", useCase.save(command).message());
    }

    private static SaveCommand command(Task task, String trigger, String daily, String cron,
                                       String once, boolean enabled) {
        return new SaveCommand(task.id(), task.name(), "", trigger, 15, "minute",
                daily, cron, once, "执行检查", enabled, task.version(), false,
                "none", false, true);
    }

    private static final class FakePort implements SchedulePort {
        private final List<Task> tasks = new ArrayList<>();
        private boolean saved;

        @Override public List<Task> list() { return List.copyOf(tasks); }

        @Override public Task createDraft(String name) {
            return task("draft", name, false, 0, "interval", "09:00", "", "");
        }

        @Override public Task save(SaveCommand command) {
            saved = true;
            Task task = task(command.id(), command.name(), command.enabled(), 1,
                    command.triggerType(), command.dailyTime(), command.cronExpression(),
                    command.onceDateTime());
            tasks.removeIf(existing -> existing.id().equals(task.id()));
            tasks.add(task);
            return task;
        }

        @Override public Task setEnabled(SaveCommand command, boolean enabled) {
            return save(command);
        }

        @Override public void delete(String taskId) { tasks.removeIf(task -> task.id().equals(taskId)); }
        @Override public RunResult runNow(String taskId, boolean allowDisabled) { return RunResult.STARTED; }
        @Override public AutoCloseable observe(EventListener listener) { return () -> { }; }

        private static Task task(String id, String name, boolean enabled, long version,
                                 String trigger, String daily, String cron, String once) {
            return new Task(id, name, "", trigger, 15, 15, "minute", daily, cron,
                    once, "执行检查", enabled, version, "", "", "—", 0, 0,
                    false, "none", false, false, "", "", enabled
                    ? RuntimeState.ENABLED : RuntimeState.PAUSED, null, true, List.of());
        }
    }
}
