package com.javaclaw.schedule;

import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/** 将领域触发配置确定性翻译为 Quartz Trigger，不持有调度器或运行状态。 */
final class ScheduleTriggerFactory {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTriggerFactory.class);
    private static final Logger taskLog = LoggerFactory.getLogger(
            "com.javaclaw.schedule.TaskExecution");
    private static final DateTimeFormatter ONCE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAILY_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final String group;

    ScheduleTriggerFactory(String group) {
        this.group = group;
    }

    Trigger create(ScheduledTask task) {
        TriggerBuilder<Trigger> base = TriggerBuilder.newTrigger()
                .withIdentity(task.getId(), group);
        return switch (task.getTriggerType()) {
            case "once" -> once(task, base);
            case "daily" -> daily(task, base);
            case "cron" -> cron(task, base);
            default -> interval(task, base);
        };
    }

    private Trigger once(ScheduledTask task, TriggerBuilder<Trigger> base) {
        String source = task.getOnceDateTime();
        if (source == null || source.isBlank()) {
            log.warn("一次性任务 {} 未设置运行时间，跳过", task.getName());
            return null;
        }
        try {
            LocalDateTime when = LocalDateTime.parse(source.trim(), ONCE_FORMAT);
            Date fireAt = Date.from(when.atZone(ZoneId.systemDefault()).toInstant());
            if (fireAt.before(new Date())) {
                taskLog.info("[{}] 一次性时间已过：{}，启动后补触发一次", task.getName(), source);
                fireAt = new Date();
            }
            return base.startAt(fireAt)
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withMisfireHandlingInstructionFireNow())
                    .build();
        } catch (RuntimeException failure) {
            log.warn("解析一次性运行时间失败: {}（任务 {}）", source, task.getName());
            return null;
        }
    }

    private Trigger interval(ScheduledTask task, TriggerBuilder<Trigger> base) {
        int minutes = task.getIntervalMinutes();
        if (minutes <= 0) minutes = 60;
        Date firstFire = new Date(System.currentTimeMillis() + minutes * 60_000L);
        return base.startAt(firstFire)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(minutes)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    private Trigger daily(ScheduledTask task, TriggerBuilder<Trigger> base) {
        String source = task.getDailyTime();
        if (source == null || source.isBlank()) source = "09:00";
        try {
            LocalTime time = LocalTime.parse(source, DAILY_FORMAT);
            String expression = "0 %d %d * * ?".formatted(time.getMinute(), time.getHour());
            return base.withSchedule(CronScheduleBuilder.cronSchedule(expression)
                    .withMisfireHandlingInstructionDoNothing()).build();
        } catch (RuntimeException failure) {
            log.warn("解析每日时间失败: {}，跳过", source);
            return null;
        }
    }

    private Trigger cron(ScheduledTask task, TriggerBuilder<Trigger> base) {
        String expression = task.getCronExpression();
        if (expression == null || expression.isBlank()) return null;
        if (!CronExpression.isValidExpression(expression)) {
            log.warn("Cron 表达式非法: 「{}」（任务 {}）。Quartz 要求 6 段：秒 分 时 日 月 周（日/周二选一用 ?）",
                    expression, task.getName());
            taskLog.warn("[{}] Cron 非法「{}」 — Quartz 需 6 段，旧 5 段表达式需重写",
                    task.getName(), expression);
            return null;
        }
        return base.withSchedule(CronScheduleBuilder.cronSchedule(expression)
                .withMisfireHandlingInstructionDoNothing()).build();
    }
}
