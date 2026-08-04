package com.javaclaw.schedule;

import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;

/** Quartz 调度器的最小可替换边界；单元测试使用内存假实现。 */
interface ScheduleBackend {
    void clear() throws SchedulerException;
    void scheduleJob(JobDetail job, Trigger trigger) throws SchedulerException;
    boolean deleteJob(JobKey key) throws SchedulerException;
    Trigger getTrigger(TriggerKey key) throws SchedulerException;
    void shutdown(boolean waitForJobsToComplete) throws SchedulerException;
}

/** 生产 Quartz 适配器。 */
final class QuartzScheduleBackend implements ScheduleBackend {
    private final Scheduler delegate;

    QuartzScheduleBackend(Scheduler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void clear() throws SchedulerException {
        delegate.clear();
    }

    @Override
    public void scheduleJob(JobDetail job, Trigger trigger) throws SchedulerException {
        delegate.scheduleJob(job, trigger);
    }

    @Override
    public boolean deleteJob(JobKey key) throws SchedulerException {
        return delegate.deleteJob(key);
    }

    @Override
    public Trigger getTrigger(TriggerKey key) throws SchedulerException {
        return delegate.getTrigger(key);
    }

    @Override
    public void shutdown(boolean waitForJobsToComplete) throws SchedulerException {
        delegate.shutdown(waitForJobsToComplete);
    }
}
