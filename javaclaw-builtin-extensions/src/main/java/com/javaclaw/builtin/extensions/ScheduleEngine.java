package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ScheduleLifecyclePort;
import com.javaclaw.extension.spi.ScheduledCommand;
import com.javaclaw.extension.spi.ScheduledCommandPort;

/**
 * Schedule 的 Quartz 投递投影与 H2 Occurrence Reconciler；模型工作只由 Extension Job 执行。
 *
 * @implNote 首次命令投递、启动恢复与 Quartz Reconcile 必须持有同一个 Occurrence 领取锁。该锁不能复用实例监视器，否则 {@code close()} 持锁等待 Quartz 停止时可能和正在进入
 *     Reconcile 的线程互相等待。跨崩溃恢复仍由稳定 Job 身份和 H2 revision 收敛。
 */
final class ScheduleEngine {
    static final String DELIVERY_OPERATION = "occurrence/deliver";

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleEngine.class);
    private static final String ENGINE_KEY = "javaclaw.schedule.engine";
    private static final String RECONCILE_GROUP = "javaclaw.schedule.internal";
    private static final ExtensionId OWNER = new ExtensionId(BuiltinExtensionIds.SCHEDULE);

    private final Object occurrenceClaimLock = new Object();
    private final Map<WorkspaceId, ExtensionExecutionContext> contexts = new ConcurrentHashMap<>();
    private ExtensionPayloadCodec payloads;
    private ScheduledCommandPort scheduledCommands;
    private ScheduleLifecyclePort scheduleLifecycle;
    private Scheduler scheduler;

    synchronized void bindRuntime(
            ExtensionPayloadCodec codec, ScheduledCommandPort commands, ScheduleLifecyclePort lifecycle) {
        if (payloads != null
                && (payloads != codec || scheduledCommands != commands || scheduleLifecycle != lifecycle)) {
            throw new IllegalStateException("Schedule runtime ports cannot be rebound");
        }
        payloads = java.util.Objects.requireNonNull(codec, "codec");
        scheduledCommands = java.util.Objects.requireNonNull(commands, "commands");
        scheduleLifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
    }

    synchronized void restore(ExtensionExecutionContext context, List<ScheduleContracts.Definition> definitions) {
        requireRuntime();
        contexts.put(context.workspaceId(), context);
        synchronized (occurrenceClaimLock) {
            ensureStarted();
            reconcileTriggers(
                    context.workspaceId(), definitions, context.clock().instant());
            reconcileOccurrences(context);
        }
        scheduleLifecycle.synchronize(
                context.workspaceId(), definitions.stream().anyMatch(ScheduleContracts.Definition::enabled));
    }

    synchronized void changed(ExtensionExecutionContext context, List<ScheduleContracts.Definition> definitions) {
        restore(context, definitions);
    }

    synchronized ScheduleContracts.Occurrence dispatchRecorded(
            ExtensionExecutionContext context, ScheduleContracts.Occurrence occurrence) throws Exception {
        requireRuntime();
        contexts.put(context.workspaceId(), context);
        synchronized (occurrenceClaimLock) {
            ensureStarted();
            if (occurrence.status().state() == ScheduleContracts.OccurrenceState.PENDING) {
                return dispatch(context, occurrence);
            }
            return occurrence;
        }
    }

    synchronized void close() {
        if (scheduler == null) {
            clearRuntime();
            return;
        }
        try {
            scheduler.shutdown(true);
        } catch (SchedulerException failure) {
            throw new IllegalStateException("Schedule Quartz shutdown failed", failure);
        } finally {
            scheduler = null;
            contexts.clear();
            clearRuntime();
        }
    }

    private void clearRuntime() {
        payloads = null;
        scheduledCommands = null;
        scheduleLifecycle = null;
    }

    private void ensureStarted() {
        if (scheduler != null) {
            return;
        }
        try {
            scheduler = new StdSchedulerFactory(properties()).getScheduler();
            scheduler.getContext().put(ENGINE_KEY, this);
            scheduleReconciler();
            scheduler.start();
        } catch (SchedulerException failure) {
            throw new IllegalStateException("Schedule Quartz startup failed", failure);
        }
    }

    private void reconcileTriggers(
            WorkspaceId workspaceId, List<ScheduleContracts.Definition> definitions, Instant now) {
        try {
            String group = workspaceId.toString();
            Set<JobKey> stale = new HashSet<>(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(group)));
            for (ScheduleContracts.Definition definition : definitions) {
                JobKey key = jobKey(workspaceId, definition.id());
                stale.remove(key);
                projectDefinition(workspaceId, definition, now, key);
            }
            for (JobKey key : stale) {
                scheduler.deleteJob(key);
            }
        } catch (SchedulerException failure) {
            throw new IllegalStateException("Schedule trigger projection failed", failure);
        }
    }

    private void projectDefinition(
            WorkspaceId workspaceId, ScheduleContracts.Definition definition, Instant now, JobKey key)
            throws SchedulerException {
        if (definition.enabled()) {
            replaceTrigger(workspaceId, definition, now);
        } else {
            scheduler.deleteJob(key);
        }
    }

    private void replaceTrigger(WorkspaceId workspaceId, ScheduleContracts.Definition definition, Instant now)
            throws SchedulerException {
        JobKey key = jobKey(workspaceId, definition.id());
        scheduler.deleteJob(key);
        JobDataMap data = new JobDataMap(Map.of(
                "workspaceId", workspaceId.toString(),
                "scheduleId", definition.id(),
                "scheduleRevision", definition.revision()));
        JobDetail job = JobBuilder.newJob(DeliveryJob.class)
                .withIdentity(key)
                .usingJobData(data)
                .build();
        scheduler.scheduleJob(job, trigger(workspaceId, definition, now));
    }

    private Trigger trigger(WorkspaceId workspaceId, ScheduleContracts.Definition definition, Instant now) {
        TriggerBuilder<Trigger> builder = TriggerBuilder.newTrigger()
                .withIdentity(new TriggerKey(definition.id(), workspaceId.toString()))
                .forJob(jobKey(workspaceId, definition.id()));
        return switch (definition.timing().kind()) {
            case CRON ->
                builder.withSchedule(CronScheduleBuilder.cronSchedule(
                                        definition.timing().cronExpression().orElseThrow())
                                .inTimeZone(java.util.TimeZone.getTimeZone(
                                        definition.timing().zoneId()))
                                .withMisfireHandlingInstructionDoNothing())
                        .build();
            case FIXED_INTERVAL -> fixedTrigger(builder, definition, now);
        };
    }

    private Trigger fixedTrigger(
            TriggerBuilder<Trigger> builder, ScheduleContracts.Definition definition, Instant now) {
        ScheduleContracts.Timing timing = definition.timing();
        Instant first = ScheduleTimes.preview(timing, now).instants().getFirst();
        long milliseconds = timing.interval().orElseThrow().toMillis();
        return builder.startAt(Date.from(first))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMilliseconds(milliseconds)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    private void scheduleReconciler() throws SchedulerException {
        JobDetail job = JobBuilder.newJob(ReconcileJob.class)
                .withIdentity("reconciler", RECONCILE_GROUP)
                .storeDurably()
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("reconciler", RECONCILE_GROUP)
                .forJob(job)
                .startNow()
                .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever(15)
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
        scheduler.scheduleJob(job, trigger);
    }

    private void fire(WorkspaceId workspaceId, String scheduleId, long scheduleRevision, Instant scheduledFor) {
        ExtensionExecutionContext context = contexts.get(workspaceId);
        if (context == null) {
            return;
        }
        try {
            ScheduleContracts.DeliveryRequest delivery =
                    new ScheduleContracts.DeliveryRequest(scheduleId, scheduleRevision, scheduledFor);
            scheduledCommands()
                    .execute(
                            new ScheduledCommand(
                                    workspaceId,
                                    BuiltinExtensionIds.SCHEDULE,
                                    DELIVERY_OPERATION,
                                    payloads().encode(delivery),
                                    "schedule-delivery-" + deliveryKey(payloads(), delivery),
                                    scheduleRevision,
                                    Optional.empty(),
                                    Optional.empty()),
                            context.cancellation());
        } catch (Exception failure) {
            LOGGER.error("Schedule {} occurrence delivery failed", scheduleId, failure);
        }
    }

    private ScheduleContracts.Occurrence dispatch(
            ExtensionExecutionContext context, ScheduleContracts.Occurrence occurrence) throws Exception {
        CanonicalPayload identity = payloads()
                .encode(new JobSubmissionIdentity(
                        OWNER,
                        context.workspaceId(),
                        "occurrence/dispatch",
                        0,
                        payloads().encode(occurrence)));
        ExtensionJob job = context.jobs()
                .submit(
                        identity,
                        new ExtensionJobMutation(
                                "schedule-occurrence-" + occurrence.identity().id(), 0),
                        () -> occurrenceSubmission(context, occurrence));
        return ScheduleOccurrenceStore.transition(
                context.managedStore(),
                payloads(),
                context.workspaceId(),
                occurrence.identity().id(),
                new ScheduleContracts.OccurrenceStatus(
                        ScheduleContracts.OccurrenceState.DISPATCHED, Optional.of(job.id()), Optional.empty()),
                context.clock().instant());
    }

    private ExtensionJobSubmission occurrenceSubmission(
            ExtensionExecutionContext context, ScheduleContracts.Occurrence occurrence) {
        ScheduleContracts.Definition definition = occurrence.definition();
        Optional<AutomationExecutionSnapshot> snapshot = executionSnapshot(context, occurrence);
        ScheduleContracts.ScheduledExecution frozen = new ScheduleContracts.ScheduledExecution(
                definition, occurrence.identity().id(), snapshot);
        OrchestrationContracts.ExecutionCheckpoint checkpoint = new OrchestrationContracts.ExecutionCheckpoint(
                payloads().encode(new ScheduleContracts.OccurrenceCheckpoint(false)),
                OrchestrationContracts.ExecutionConsumption.zero());
        return new ExtensionJobSubmission(
                OWNER,
                context.workspaceId(),
                ScheduleOccurrenceJobExecutor.JOB_TYPE,
                definition.id(),
                definition.revision(),
                payloads().encode(frozen),
                payloads().encode(checkpoint));
    }

    private Optional<AutomationExecutionSnapshot> executionSnapshot(
            ExtensionExecutionContext context, ScheduleContracts.Occurrence occurrence) {
        ScheduleContracts.Definition definition = occurrence.definition();
        UnattendedExecutionScope scope = new UnattendedExecutionScope(
                context.workspaceId(),
                definition.id(),
                definition.revision(),
                occurrence.identity().id());
        return definition
                .target()
                .turnTemplate()
                .map(target -> context.executionPolicies()
                        .freeze(context.workspaceId(), target.execution(), context.cancellation())
                        .withUnattendedExecutionScope(scope));
    }

    private void reconcileAll() {
        synchronized (occurrenceClaimLock) {
            contexts.values().forEach(this::reconcileOccurrences);
        }
    }

    private void reconcileOccurrences(ExtensionExecutionContext context) {
        try {
            for (ScheduleContracts.Occurrence occurrence :
                    ScheduleOccurrenceStore.active(context.managedStore(), payloads(), context.workspaceId())) {
                reconcileOccurrence(context, occurrence);
            }
        } catch (Exception failure) {
            LOGGER.error("Schedule occurrence reconciliation failed for {}", context.workspaceId(), failure);
        }
    }

    private void reconcileOccurrence(ExtensionExecutionContext context, ScheduleContracts.Occurrence occurrence)
            throws Exception {
        if (occurrence.status().state() == ScheduleContracts.OccurrenceState.PENDING) {
            dispatch(context, occurrence);
            return;
        }
        Optional<ExtensionJob> job = occurrence.status().jobId().flatMap(context.jobs()::find);
        if (job.isEmpty()) {
            return;
        }
        ExecutionState state = job.orElseThrow().state();
        if (state.terminal()) {
            finishFromJob(context, occurrence, state);
        } else if (state != ExecutionState.QUEUED
                && occurrence.status().state() == ScheduleContracts.OccurrenceState.DISPATCHED) {
            transition(context, occurrence, ScheduleContracts.OccurrenceState.RUNNING, Optional.empty());
        }
    }

    private void finishFromJob(
            ExtensionExecutionContext context, ScheduleContracts.Occurrence occurrence, ExecutionState jobState)
            throws Exception {
        ScheduleContracts.OccurrenceState state =
                switch (jobState) {
                    case COMPLETED -> ScheduleContracts.OccurrenceState.COMPLETED;
                    case FAILED -> ScheduleContracts.OccurrenceState.FAILED;
                    case CANCELLED -> ScheduleContracts.OccurrenceState.CANCELLED;
                    default -> throw new IllegalArgumentException("Job is not terminal");
                };
        Optional<String> reason =
                state == ScheduleContracts.OccurrenceState.FAILED ? Optional.of("TARGET_FAILED") : Optional.empty();
        transition(context, occurrence, state, reason);
    }

    private void transition(
            ExtensionExecutionContext context,
            ScheduleContracts.Occurrence occurrence,
            ScheduleContracts.OccurrenceState state,
            Optional<String> reason)
            throws Exception {
        ScheduleOccurrenceStore.transition(
                context.managedStore(),
                payloads(),
                context.workspaceId(),
                occurrence.identity().id(),
                new ScheduleContracts.OccurrenceStatus(
                        state, occurrence.status().jobId(), reason),
                context.clock().instant());
    }

    private ExtensionPayloadCodec payloads() {
        requireRuntime();
        return payloads;
    }

    private ScheduledCommandPort scheduledCommands() {
        requireRuntime();
        return scheduledCommands;
    }

    private void requireRuntime() {
        if (payloads == null || scheduledCommands == null || scheduleLifecycle == null) {
            throw new IllegalStateException("Schedule runtime ports are not bound");
        }
    }

    private static String deliveryKey(ExtensionPayloadCodec payloads, ScheduleContracts.DeliveryRequest delivery) {
        return payloads.encode(delivery).sha256();
    }

    private static JobKey jobKey(WorkspaceId workspaceId, String scheduleId) {
        return new JobKey(scheduleId, workspaceId.toString());
    }

    private static Properties properties() {
        Properties values = new Properties();
        values.setProperty("org.quartz.scheduler.instanceName", "JavaClawSchedule");
        values.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        values.setProperty("org.quartz.threadPool.threadCount", "1");
        values.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        values.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        return values;
    }

    /** Quartz 只把触发点交给实时启用门禁与 H2 Occurrence 投递器。 */
    public static final class DeliveryJob implements Job {
        /** Quartz 反射构造。 */
        public DeliveryJob() {}

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                ScheduleEngine engine =
                        (ScheduleEngine) context.getScheduler().getContext().get(ENGINE_KEY);
                JobDataMap data = context.getMergedJobDataMap();
                Instant scheduled = Optional.ofNullable(context.getScheduledFireTime())
                        .map(Date::toInstant)
                        .orElseGet(Instant::now);
                engine.fire(
                        WorkspaceId.parse(data.getString("workspaceId")),
                        data.getString("scheduleId"),
                        data.getLong("scheduleRevision"),
                        scheduled);
            } catch (RuntimeException | SchedulerException failure) {
                throw new JobExecutionException(failure);
            }
        }
    }

    /** Quartz 定期触发 H2 权威状态收敛，不执行模型或工具。 */
    public static final class ReconcileJob implements Job {
        /** Quartz 反射构造。 */
        public ReconcileJob() {}

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                ScheduleEngine engine =
                        (ScheduleEngine) context.getScheduler().getContext().get(ENGINE_KEY);
                engine.reconcileAll();
            } catch (SchedulerException failure) {
                throw new JobExecutionException(failure);
            }
        }
    }
}
