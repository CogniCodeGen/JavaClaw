package com.javaclaw.application.schedule;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.schedule.ScheduleApplicationService.EventListener;
import com.javaclaw.application.schedule.ScheduleApplicationService.DisablePolicy;
import com.javaclaw.application.schedule.ScheduleApplicationService.RunResult;
import com.javaclaw.application.schedule.ScheduleApplicationService.RuntimeState;
import com.javaclaw.application.schedule.ScheduleApplicationService.SaveCommand;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertFalse(port.saved);
    }

    @Test
    void pausedTaskMayKeepIncompleteTriggerUntilItIsEnabled() {
        Task draft = useCase.createDraft("稍后配置");
        SaveCommand command = command(draft, "cron", "", "", "", false);

        assertEquals("已保存", useCase.save(command).message());
    }

    @Test
    void validatesEveryEnabledTriggerAndNormalizesDefaults() {
        Task draft = useCase.createDraft("触发校验");

        SaveCommand interval = new SaveCommand(draft.id(), " 间隔 ", "", "", 0, "invalid",
                "", "", "", " 执行 ", true, 0, false, "", false, true);
        Task normalized = useCase.save(interval).snapshot().require(draft.id());
        assertEquals("interval", normalized.triggerType());
        assertEquals(1, port.lastCommand.intervalValue());
        assertEquals("minute", port.lastCommand.intervalUnit());
        assertEquals("none", port.lastCommand.notifyChannel());

        assertEquals("已保存", useCase.save(command(draft, "once", "", "",
                "2026-08-12 09:30", true)).message());
        assertEquals("已保存", useCase.save(command(draft, "cron", "",
                "0 0/5 * * * ?", "", true)).message());

        assertThrows(ValidationException.class, () -> useCase.save(
                command(draft, "once", "", "", "2026/08/12", true)));
        assertThrows(ValidationException.class, () -> useCase.save(
                command(draft, "daily", "25:00", "", "", true)));
        assertThrows(ValidationException.class, () -> useCase.save(
                command(draft, "weekly", "", "", "", true)));
    }

    @Test
    void enableDisableDeleteObserveAndRunResultsShareFreshSnapshots() throws Exception {
        Task draft = useCase.createDraft("运行控制");
        Task saved = useCase.save(command(draft, "interval", "", "", "", false))
                .snapshot().require(draft.id());
        SaveCommand source = command(saved, "interval", "", "", "", false);
        SaveCommand persisted = new SaveCommand(
                source.id(), source.name(), source.description(), source.triggerType(),
                source.intervalValue(), source.intervalUnit(), source.dailyTime(),
                source.cronExpression(), source.onceDateTime(), source.prompt(), source.enabled(),
                source.version(), source.notifyEnabled(), source.notifyChannel(),
                source.unattendedToolsAuthorized(), false);

        assertEquals("已启用", useCase.setEnabled(persisted, true).message());
        assertEquals(DisablePolicy.CANCEL_ACTIVE, port.lastDisablePolicy);
        assertEquals("已暂停", useCase.setEnabled(persisted, false,
                DisablePolicy.AFTER_CURRENT_RUN).message());
        assertEquals(DisablePolicy.AFTER_CURRENT_RUN, port.lastDisablePolicy);
        assertThrows(NullPointerException.class,
                () -> useCase.setEnabled(persisted, false, null));

        for (RunResult run : RunResult.values()) {
            port.nextRun = run;
            var result = useCase.runNow(saved.id(), true);
            assertEquals(run, result.runResult());
            assertEquals(expectedMessage(run), result.message());
        }

        try (AutoCloseable observation = useCase.observe(event -> { })) {
            assertTrue(port.observed);
        }
        assertThrows(NullPointerException.class, () -> useCase.observe(null));
        assertEquals("任务已删除", useCase.delete(saved.id()).message());
        assertTrue(useCase.snapshot().tasks().isEmpty());
    }

    @Test
    void draftsAndRequiredFieldsFailBeforeMutation() {
        Task draft = useCase.createDraft("草稿");
        SaveCommand command = command(draft, "interval", "", "", "", false);

        assertThrows(ValidationException.class,
                () -> useCase.setEnabled(command, true, DisablePolicy.CANCEL_ACTIVE));
        assertThrows(ValidationException.class, () -> useCase.createDraft(" "));
        assertThrows(ValidationException.class, () -> useCase.delete(null));
        assertThrows(ValidationException.class, () -> useCase.runNow(" ", false));
        assertThrows(NullPointerException.class, () -> useCase.save(null));
        assertThrows(ValidationException.class, () -> useCase.save(new SaveCommand(
                "", "name", "", "interval", 1, "minute", "", "", "",
                "prompt", false, 0, false, "none", false, false)));
        assertThrows(ValidationException.class, () -> useCase.save(new SaveCommand(
                "id", " ", "", "interval", 1, "minute", "", "", "",
                "prompt", false, 0, false, "none", false, false)));
        assertThrows(ValidationException.class, () -> useCase.save(new SaveCommand(
                "id", "name", "", "interval", 1, "minute", "", "", "",
                " ", false, 0, false, "none", false, false)));
        assertThrows(NullPointerException.class, () -> new ScheduleUseCase(null));
    }

    @Test
    void taskValueObjectDescribesAllRuntimeAndTriggerStates() {
        assertTrue(FakePort.task("running", "Running", true, 1, "interval", "", "", "",
                RuntimeState.RUNNING, "").active());
        assertTrue(FakePort.task("queued", "Queued", true, 1, "interval", "", "", "",
                RuntimeState.QUEUED, "").active());
        assertFalse(FakePort.task("paused", "Paused", false, 1, "interval", "", "", "",
                RuntimeState.PAUSED, "").active());

        assertEquals("自定义摘要", FakePort.task("custom", "Custom", true, 1, "interval", "", "", "",
                RuntimeState.ENABLED, "自定义摘要").describeTrigger());
        assertEquals("一次性 未设置", FakePort.task("once", "Once", true, 1, "once", "", "", "",
                RuntimeState.ENABLED, "").describeTrigger());
        assertEquals("每天 09:00", FakePort.task("daily", "Daily", true, 1, "daily", "", "", "",
                RuntimeState.ENABLED, "").describeTrigger());
        assertEquals("Cron 0 0 * * * ?", FakePort.task("cron", "Cron", true, 1, "cron", "",
                "0 0 * * * ?", "", RuntimeState.ENABLED, "").describeTrigger());
        assertTrue(FakePort.task("hour", "Hour", true, 1, "interval", "", "", "",
                RuntimeState.ENABLED, "", 2, "hour").describeTrigger().endsWith("小时"));
        assertTrue(FakePort.task("day", "Day", true, 1, "interval", "", "", "",
                RuntimeState.ENABLED, "", 3, "day").describeTrigger().endsWith("天"));
        assertTrue(FakePort.task("minute", "Minute", true, 1, "interval", "", "", "",
                RuntimeState.ENABLED, "", 0, "unknown").describeTrigger().endsWith("分钟"));
    }

    private static String expectedMessage(RunResult run) {
        return switch (run) {
            case STARTED -> "已加入执行队列…";
            case ALREADY_ACTIVE -> "任务已在运行或排队";
            case DISABLED -> "任务已暂停";
            case NOT_FOUND -> "任务不存在";
            case UNSUPPORTED -> "当前任务不支持手动执行";
        };
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
        private boolean observed;
        private SaveCommand lastCommand;
        private DisablePolicy lastDisablePolicy;
        private RunResult nextRun = RunResult.STARTED;

        @Override public List<Task> list() { return List.copyOf(tasks); }

        @Override public Task createDraft(String name) {
            return task("draft", name, false, 0, "interval", "09:00", "", "");
        }

        @Override public Task save(SaveCommand command) {
            saved = true;
            lastCommand = command;
            Task task = task(command.id(), command.name(), command.enabled(), 1,
                    command.triggerType(), command.dailyTime(), command.cronExpression(),
                    command.onceDateTime());
            tasks.removeIf(existing -> existing.id().equals(task.id()));
            tasks.add(task);
            return task;
        }

        @Override public Task setEnabled(
                SaveCommand command, boolean enabled, DisablePolicy disablePolicy) {
            lastDisablePolicy = disablePolicy;
            return save(command);
        }

        @Override public void delete(String taskId) { tasks.removeIf(task -> task.id().equals(taskId)); }
        @Override public RunResult runNow(String taskId, boolean allowDisabled) { return nextRun; }
        @Override public AutoCloseable observe(EventListener listener) {
            observed = true;
            return () -> observed = false;
        }

        private static Task task(String id, String name, boolean enabled, long version,
                                 String trigger, String daily, String cron, String once) {
            return task(id, name, enabled, version, trigger, daily, cron, once,
                    enabled ? RuntimeState.ENABLED : RuntimeState.PAUSED, "");
        }

        private static Task task(String id, String name, boolean enabled, long version,
                                 String trigger, String daily, String cron, String once,
                                 RuntimeState state, String summary) {
            return task(id, name, enabled, version, trigger, daily, cron, once,
                    state, summary, 15, "minute");
        }

        private static Task task(String id, String name, boolean enabled, long version,
                                 String trigger, String daily, String cron, String once,
                                 RuntimeState state, String summary, int interval, String unit) {
            return new Task(id, name, "", trigger, interval, interval, unit, daily, cron,
                    once, "执行检查", enabled, version, "", "", "—", 0, 0,
                    false, "none", false, false, summary, "", state, null, true, List.of());
        }
    }
}
