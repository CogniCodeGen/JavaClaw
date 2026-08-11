package com.javaclaw.schedule;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
import com.javaclaw.notification.NotificationTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends the optional completion notification without affecting a scheduled run's outcome. */
final class ScheduleCompletionNotifier {

    private static final Logger log = LoggerFactory.getLogger(ScheduleCompletionNotifier.class);
    private static final Logger taskLog = LoggerFactory.getLogger("com.javaclaw.schedule.TaskExecution");

    private final NotificationConfig notifications;
    private final EmailConfig email;

    ScheduleCompletionNotifier(NotificationConfig notifications, EmailConfig email) {
        this.notifications = notifications;
        this.email = email;
    }

    void notifyCompletion(ScheduledTask task, boolean success, String detail) {
        if (!task.isNotifyEnabled()) return;
        String channel = task.getNotifyChannel();
        if (channel == null || channel.isBlank() || "none".equalsIgnoreCase(channel)) return;
        if (notifications == null || email == null) {
            log.debug("测试调度器未配置通知服务，跳过完成通知");
            return;
        }
        try {
            String title = "定时任务「" + task.getName() + "」" + (success ? "执行完成" : "执行失败");
            String body = (success ? "✅ " : "⚠️ ") + title + "\n"
                    + (detail == null || detail.isBlank() ? "" : detail);
            String result = new NotificationTools(ToolCallOrigin.SCHEDULED, notifications, email)
                    .sendByChannel(channel, title, body);
            taskLog.info("[{}] 完成通知（{}）: {}", task.getName(), channel, result);
        } catch (Exception failure) {
            log.warn("定时任务完成通知发送失败: {}", task.getName(), failure);
        }
    }
}
