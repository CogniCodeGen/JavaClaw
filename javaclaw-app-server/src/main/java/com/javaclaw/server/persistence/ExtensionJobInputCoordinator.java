package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.extension.spi.ExtensionJob;

/**
 * 恢复并收口 Extension Job 与平台 InputRequest 的持久等待关系。
 *
 * <p><strong>事务不变量：</strong>到期 InputRequest、所属 Turn、等待 Job 和关联删除在同一个 SERIALIZABLE 事务内提交。协调器只按数据库保存的绝对 {@code expiresAt}
 * 判断，不从进程启动时间重新计算期限。关闭仅取消扫描，不修改未到期记录。
 */
public final class ExtensionJobInputCoordinator implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionJobInputCoordinator.class);
    private static final Duration DEFAULT_SCAN_INTERVAL = Duration.ofSeconds(1);
    private static final int BATCH_SIZE = 100;
    private static final int MAXIMUM_BATCHES_PER_SCAN = 10;
    private static final String INPUT_EXPIRED = "INPUT_REQUEST_EXPIRED";

    private final H2Transactions transactions;
    private final ExtensionJobInputWaitRepository waits = new ExtensionJobInputWaitRepository();
    private final ExtensionJobRepository jobs = new ExtensionJobRepository();
    private final InputRequestRepository requests = new InputRequestRepository();
    private final InputRequestService inputs;
    private final Clock clock;
    private final Duration scanInterval;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledScan;
    private boolean closed;

    /**
     * 创建协调器，立即恢复数据库中的等待关联并开始周期扫描。
     *
     * @param database data-v6 数据库
     * @param inputs InputRequest 权威服务
     * @param clock 平台时钟
     */
    public ExtensionJobInputCoordinator(H2Database database, InputRequestService inputs, Clock clock) {
        this(database, inputs, clock, DEFAULT_SCAN_INTERVAL);
    }

    ExtensionJobInputCoordinator(H2Database database, InputRequestService inputs, Clock clock, Duration scanInterval) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scanInterval = requirePositive(scanInterval);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("javaclaw-extension-job-input")
                .unstarted(runnable));
        inputs.onChanged(ignored -> scheduleSoon());
        runOnce();
        schedule(scanInterval);
    }

    synchronized int runOnce() {
        if (closed) {
            return 0;
        }
        int changed = 0;
        for (int batch = 0; batch < MAXIMUM_BATCHES_PER_SCAN; batch++) {
            List<String> candidates =
                    execute(connection -> waits.listActionable(connection, clock.instant(), BATCH_SIZE));
            if (candidates.isEmpty()) {
                break;
            }
            for (String jobId : candidates) {
                ReconcileResult result = execute(connection -> reconcile(connection, jobId, clock.instant()));
                result.changedInput().ifPresent(inputs::publishChanged);
                if (result.changed()) {
                    changed++;
                }
            }
            if (candidates.size() < BATCH_SIZE) {
                break;
            }
        }
        return changed;
    }

    private ReconcileResult reconcile(java.sql.Connection connection, String jobId, Instant now) throws Exception {
        Optional<ExtensionJobInputWaitRepository.Link> locked = waits.lock(connection, jobId);
        if (locked.isEmpty()) {
            return ReconcileResult.unchanged();
        }
        ExtensionJobInputWaitRepository.Link link = locked.orElseThrow();
        ExtensionJob job = jobs.lock(connection, link.jobId())
                .orElseThrow(() -> PersistenceException.invalidRequest("关联的 Extension Job 不存在"));
        InputRequestRecord input = requests.lock(connection, link.requestId())
                .orElseThrow(() -> PersistenceException.invalidRequest("关联的 InputRequest 不存在"));
        if (!input.request().turnId().equals(link.turnId())) {
            throw PersistenceException.invalidRequest("InputRequest Turn 与持久等待关联不一致");
        }
        if (terminal(job.state())) {
            Optional<InputRequestRecord> cancelled =
                    inputs.cancelLinked(connection, link.requestId(), link.turnId(), "所属 Extension Job 已结束");
            inputs.completeLinked(connection, link.requestId(), link.turnId());
            waits.delete(connection, link.jobId());
            return new ReconcileResult(true, cancelled);
        }
        if (input.state() == InputRequestState.CANCELLED) {
            inputs.completeLinked(connection, link.requestId(), link.turnId());
            finishJob(connection, job, ExecutionState.CANCELLED, Optional.empty(), now);
            waits.delete(connection, link.jobId());
            return new ReconcileResult(true, Optional.empty());
        }
        if (input.state() == InputRequestState.EXPIRED) {
            inputs.completeLinked(connection, link.requestId(), link.turnId());
            finishJob(connection, job, ExecutionState.FAILED, Optional.of(INPUT_EXPIRED), now);
            waits.delete(connection, link.jobId());
            return new ReconcileResult(true, Optional.empty());
        }
        if (input.pending() && !now.isBefore(input.request().expiresAt())) {
            Optional<InputRequestRecord> expired =
                    inputs.expireLinked(connection, link.requestId(), link.turnId(), now);
            inputs.completeLinked(connection, link.requestId(), link.turnId());
            finishJob(connection, job, ExecutionState.FAILED, Optional.of(INPUT_EXPIRED), now);
            waits.delete(connection, link.jobId());
            return new ReconcileResult(true, expired);
        }
        return ReconcileResult.unchanged();
    }

    private void finishJob(
            java.sql.Connection connection,
            ExtensionJob job,
            ExecutionState state,
            Optional<String> errorCode,
            Instant now)
            throws Exception {
        jobs.finishInputWait(connection, job, state, errorCode, now);
    }

    private synchronized void scheduleSoon() {
        if (closed) {
            return;
        }
        if (scheduledScan != null && scheduledScan.getDelay(TimeUnit.NANOSECONDS) <= 0) {
            return;
        }
        schedule(Duration.ZERO);
    }

    private synchronized void schedule(Duration delay) {
        if (closed) {
            return;
        }
        if (scheduledScan != null) {
            scheduledScan.cancel(false);
        }
        scheduledScan = scheduler.schedule(this::scanAndReschedule, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void scanAndReschedule() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            LOGGER.warn("Extension Job 输入等待协调失败，将按周期重试", failure);
        }
        schedule(scanInterval);
    }

    private static boolean terminal(ExecutionState state) {
        return state == ExecutionState.COMPLETED || state == ExecutionState.FAILED || state == ExecutionState.CANCELLED;
    }

    private static Duration requirePositive(Duration value) {
        Duration checked = Objects.requireNonNull(value, "scanInterval");
        if (checked.isNegative() || checked.isZero()) {
            throw new IllegalArgumentException("scanInterval must be positive");
        }
        return checked;
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Extension Job 输入等待事务失败", failure);
        }
    }

    /** 停止进程内周期扫描；不会过期或取消尚未到期的持久请求。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (scheduledScan != null) {
            scheduledScan.cancel(false);
            scheduledScan = null;
        }
        scheduler.shutdown();
    }

    private record ReconcileResult(boolean changed, Optional<InputRequestRecord> changedInput) {
        private static ReconcileResult unchanged() {
            return new ReconcileResult(false, Optional.empty());
        }
    }
}
