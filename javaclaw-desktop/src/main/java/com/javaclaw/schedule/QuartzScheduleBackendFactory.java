package com.javaclaw.schedule;

import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.DirectSchedulerFactory;
import org.quartz.simpl.RAMJobStore;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;

import java.util.Objects;
import java.util.UUID;

/** 创建绑定到单个工作区管理器的 Quartz RAM 调度后端。 */
final class QuartzScheduleBackendFactory {

    static final String TASK_ID_KEY = "taskId";

    private QuartzScheduleBackendFactory() { }

    static ScheduleBackend create(ScheduleManager manager) {
        try {
            SimpleThreadPool pool = new SimpleThreadPool(1, Thread.NORM_PRIORITY);
            pool.setThreadNamePrefix("javaclaw-schedule-worker");
            pool.setMakeThreadsDaemons(true);
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String name = "javaclaw-scheduler-" + suffix;
            DirectSchedulerFactory factory = DirectSchedulerFactory.getInstance();
            factory.createScheduler(name, "javaclaw-instance-" + suffix, pool, new RAMJobStore());
            Scheduler scheduler = factory.getScheduler(name);
            scheduler.setJobFactory(new ManagerJobFactory(manager));
            scheduler.start();
            return new QuartzScheduleBackend(scheduler);
        } catch (SchedulerException failure) {
            throw new IllegalStateException("初始化 Quartz Scheduler 失败", failure);
        }
    }

    static final class ScheduledTaskJob implements org.quartz.Job {
        private final ScheduleManager manager;

        private ScheduledTaskJob(ScheduleManager manager) {
            this.manager = Objects.requireNonNull(manager, "manager");
        }

        @Override
        public void execute(JobExecutionContext context) {
            manager.executeTask(context.getMergedJobDataMap().getString(TASK_ID_KEY));
        }
    }

    private static final class ManagerJobFactory implements JobFactory {
        private final ScheduledTaskJob job;

        private ManagerJobFactory(ScheduleManager manager) {
            job = new ScheduledTaskJob(manager);
        }

        @Override
        public org.quartz.Job newJob(TriggerFiredBundle bundle, Scheduler scheduler) {
            return job;
        }
    }
}
