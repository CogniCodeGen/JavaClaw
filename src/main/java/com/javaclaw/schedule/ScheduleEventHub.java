package com.javaclaw.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** 隔离定时任务观察者故障，并提供可撤销的多订阅生命周期。 */
final class ScheduleEventHub {

    private static final Logger log = LoggerFactory.getLogger(ScheduleEventHub.class);
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final CopyOnWriteArrayList<ScheduleManager.TaskListener> listeners =
            new CopyOnWriteArrayList<>();

    void log(String taskName, String message) {
        String formatted = "[" + LocalDateTime.now().format(LOG_TIME) + "] " + message;
        listeners.forEach(listener -> safely(
                () -> listener.onLog(taskName, formatted), "日志"));
    }

    void started(String taskId) {
        listeners.forEach(listener -> safely(() -> listener.onExecutionStarted(taskId), "开始"));
    }

    void completed(String taskId) {
        listeners.forEach(listener -> safely(() -> listener.onExecutionCompleted(taskId), "完成"));
    }

    AutoCloseable subscribe(ScheduleManager.TaskListener listener) {
        Objects.requireNonNull(listener, "listener");
        AtomicBoolean active = new AtomicBoolean(true);
        ScheduleManager.TaskListener guarded = new ScheduleManager.TaskListener() {
            @Override public void onLog(String taskName, String message) {
                if (active.get()) listener.onLog(taskName, message);
            }
            @Override public void onExecutionStarted(String taskId) {
                if (active.get()) listener.onExecutionStarted(taskId);
            }
            @Override public void onExecutionCompleted(String taskId) {
                if (active.get()) listener.onExecutionCompleted(taskId);
            }
        };
        listeners.add(guarded);
        return () -> {
            active.set(false);
            listeners.remove(guarded);
        };
    }

    private static void safely(Runnable notification, String kind) {
        try {
            notification.run();
        } catch (RuntimeException failure) {
            log.warn("定时任务{}监听器失败（已隔离）", kind, failure);
        }
    }
}
