package com.javaclaw.schedule;

/** 任务快照版本落后于数据库，拒绝用旧状态覆盖较新的修改。 */
public final class ScheduleConflictException extends SchedulePersistenceException {
    public ScheduleConflictException(String taskId) {
        super("定时任务已被其他窗口或进程更新，请刷新后重试：" + taskId);
    }
}
