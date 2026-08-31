package com.javaclaw.agent.automation;

import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.TimeZone;

import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

/** H2-authoritative schedule projection backed by Quartz RAMJobStore only. */
public final class AutomationScheduler implements AutoCloseable {
    private static final String CONTEXT_KEY = "javaclaw.automation.trigger";
    private static final String REPOSITORY_KEY = "javaclaw.automation.repository";
    private final AutomationRepository repository;
    private final ScheduleTrigger trigger;
    private final Scheduler scheduler;

    /** 建立独立 RAMJobStore 调度器并绑定持久仓库与回调；close 等待调度任务结束后关闭资源。 */
    public AutomationScheduler(AutomationRepository repository, ScheduleTrigger trigger) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        try {
            Properties properties = new Properties();
            properties.setProperty(
                    "org.quartz.scheduler.instanceName", "JavaClawScheduleProjection-" + System.identityHashCode(this));
            properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
            properties.setProperty("org.quartz.threadPool.threadCount", "2");
            properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
            properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
            properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
            scheduler = new StdSchedulerFactory(properties).getScheduler();
            scheduler.getContext().put(CONTEXT_KEY, trigger);
            scheduler.getContext().put(REPOSITORY_KEY, repository);
        } catch (SchedulerException failure) {
            throw new IllegalStateException("cannot initialize Quartz RAM scheduler", failure);
        }
    }

    /** 清空可重建的 Quartz 投影，从持久定义重建启用项，再启动触发器。 */
    public void start() {
        try {
            scheduler.clear();
            for (var schedule : repository.listSchedules()) {
                if (schedule.enabled()) {
                    schedule(schedule);
                }
            }
            scheduler.start();
        } catch (SchedulerException failure) {
            throw new IllegalStateException("cannot start schedule projection", failure);
        }
    }

    /** 按已提交定义替换对应 Quartz Job；禁用时仅移除触发器，不删除 H2 定义。 */
    public void reconcile(AutomationRepository.ScheduleDefinition schedule) {
        try {
            scheduler.deleteJob(jobKey(schedule.id()));
            if (schedule.enabled()) {
                schedule(schedule);
            }
        } catch (SchedulerException failure) {
            throw new IllegalStateException("cannot update schedule projection", failure);
        }
    }

    /** 移除指定 Schedule 的 Quartz 投影；H2 记录由调用方先完成删除。 */
    public void remove(String id) {
        try {
            scheduler.deleteJob(jobKey(id));
        } catch (SchedulerException failure) {
            throw new IllegalStateException("cannot remove schedule projection", failure);
        }
    }

    private void schedule(AutomationRepository.ScheduleDefinition value) throws SchedulerException {
        CronScheduleBuilder cron = CronScheduleBuilder.cronSchedule(value.cronExpression())
                .inTimeZone(TimeZone.getTimeZone(value.zoneId()))
                .withMisfireHandlingInstructionDoNothing();
        CronTrigger cronTrigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger_" + value.id(), "javaclaw-schedules")
                .forJob(jobKey(value.id()))
                .withSchedule(cron)
                .build();
        scheduler.scheduleJob(
                JobBuilder.newJob(ScheduleJob.class)
                        .withIdentity(jobKey(value.id()))
                        .usingJobData("scheduleId", value.id())
                        .build(),
                cronTrigger);
        repository.recordScheduleFire(
                value.id(),
                null,
                cronTrigger.getNextFireTime() == null
                        ? null
                        : cronTrigger.getNextFireTime().toInstant(),
                "SCHEDULED");
    }

    private static JobKey jobKey(String id) {
        return new JobKey("schedule_" + id, "javaclaw-schedules");
    }

    /** Quartz 触发适配器；同一 Job 禁止重入，执行结果写回权威 Repository。 */
    @DisallowConcurrentExecution
    public static final class ScheduleJob implements Job {
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            String id = context.getMergedJobDataMap().getString("scheduleId");
            try {
                ScheduleTrigger callback =
                        (ScheduleTrigger) context.getScheduler().getContext().get(CONTEXT_KEY);
                Instant scheduled = context.getScheduledFireTime().toInstant();
                String result = callback.trigger(id, scheduled);
                AutomationRepository repository = (AutomationRepository)
                        context.getScheduler().getContext().get(REPOSITORY_KEY);
                repository.recordScheduleFire(
                        id,
                        scheduled,
                        context.getNextFireTime() == null
                                ? null
                                : context.getNextFireTime().toInstant(),
                        result);
            } catch (Exception failure) {
                throw new JobExecutionException(failure, false);
            }
        }
    }

    /** 将定时触发转为统一 Turn 的回调边界。 */
    @FunctionalInterface
    public interface ScheduleTrigger {
        /**
         * 在 scheduledAt 对应的调度点触发一次执行，返回可持久化状态；失败由调度器记录，不立即重跑。
         *
         * @throws Exception 配置解析或 Turn 启动失败
         */
        String trigger(String scheduleId, Instant scheduledAt) throws Exception;
    }

    @Override
    public void close() {
        try {
            if (!scheduler.isShutdown()) {
                scheduler.shutdown(true);
            }
        } catch (SchedulerException failure) {
            throw new IllegalStateException("cannot stop schedule projection", failure);
        }
    }
}
