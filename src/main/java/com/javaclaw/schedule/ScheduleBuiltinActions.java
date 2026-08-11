package com.javaclaw.schedule;

import com.javaclaw.memory.MemoryService;
import com.javaclaw.system.CommandSessionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作区内置任务与其真实能力的生命周期接线。
 *
 * <p>该对象随工作区 Context 创建和关闭，不使用静态回查；关闭按注册逆序撤销监听和动作，
 * 防止旧工作区回调写入新页面状态。</p>
 */
public final class ScheduleBuiltinActions implements AutoCloseable {

    private static final String COMMAND_CLEANUP = "sys:cmd-session-cleanup";
    private static final String HABIT_REVIEW = "sys:habit-review";

    private final List<AutoCloseable> registrations = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ScheduleBuiltinActions(
            ScheduleManager schedules,
            CommandSessionManager commandSessions,
            MemoryService memory) {
        Objects.requireNonNull(schedules, "schedules");
        Objects.requireNonNull(commandSessions, "commandSessions");
        Objects.requireNonNull(memory, "memory");
        registrations.add(schedules.registerBuiltinAction(COMMAND_CLEANUP, () -> {
            int count = commandSessions.cleanupIdleNow();
            return "回收 " + count + " 个空闲/失效会话";
        }));
        registrations.add(schedules.registerBuiltinAction(HABIT_REVIEW, memory::reviewHabitsNow));
        registrations.add(commandSessions.subscribeCleanup(count -> {
            if (count > 0) {
                schedules.recordBuiltinRun(COMMAND_CLEANUP, true, 0,
                        "自动回收 " + count + " 个空闲/失效会话");
            }
        }));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (Exception ignored) {
                // 其余注册仍必须释放；各关闭动作均为本地幂等移除。
            }
        }
        registrations.clear();
    }
}
