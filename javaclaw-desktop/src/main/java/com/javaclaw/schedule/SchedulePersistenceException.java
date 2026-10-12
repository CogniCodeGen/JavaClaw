package com.javaclaw.schedule;

/** 定时任务持久化失败；调用方必须向用户显示失败，不得假装保存成功。 */
public class SchedulePersistenceException extends RuntimeException {
    public SchedulePersistenceException(String message) {
        super(message);
    }

    public SchedulePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
