package com.javaclaw.server.persistence;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobRegistrar;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.OrchestratedTurnFailureException;

/**
 * 每次只推进一个已持久化工作单元的通用 Extension Job Supervisor。
 *
 * <p><strong>恢复不变量：</strong>意图一旦记录就始终以相同 unit ID 重放，直到结果与 checkpoint 提交；进程启动先把遗留 PROCESSING Outbox 恢复为
 * PENDING。关闭只发布协作式取消，不伪造工作单元终态。
 */
public final class ExtensionJobSupervisor implements AutoCloseable, ExtensionJobRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionJobSupervisor.class);
    private static final Duration MISSING_EXECUTOR_RETRY = Duration.ofSeconds(1);
    private static final long IDLE_NANOS = Duration.ofMillis(100).toNanos();

    private final ExtensionJobService jobs;
    private final ExtensionJobActivityPort activities;
    private final Map<ExecutorKey, ExtensionJobExecutor> executors = new ConcurrentHashMap<>();
    private final AtomicReference<CancellationSource> activeCancellation = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread worker;

    /**
     * 创建尚未启动的 Supervisor。
     *
     * @param jobs Job 事务服务
     * @param activities 工作单元生命周期端口
     */
    public ExtensionJobSupervisor(ExtensionJobService jobs, ExtensionJobActivityPort activities) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.activities = Objects.requireNonNull(activities, "activities");
    }

    /**
     * 注册一个扩展执行类型；同一键只能注册一次。
     *
     * @param extensionId 扩展所有者
     * @param jobType Job 类型
     * @param executor 单工作单元执行器
     */
    public void register(ExtensionId extensionId, String jobType, ExtensionJobExecutor executor) {
        ExecutorKey key = new ExecutorKey(extensionId, jobType);
        if (executors.putIfAbsent(key, Objects.requireNonNull(executor, "executor")) != null) {
            throw new IllegalArgumentException("duplicate Extension Job executor");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void register(ExtensionId extensionId, ExtensionJobRegistration registration) {
        ExtensionJobRegistration checked = Objects.requireNonNull(registration, "registration");
        register(extensionId, checked.jobType(), checked.executor());
    }

    /**
     * 恢复 Outbox 并启动单个虚拟线程后台循环。
     *
     * @return 遗留 PROCESSING Outbox 恢复数量
     */
    public int start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("Extension Job Supervisor cannot start twice");
        }
        int recovered = jobs.recoverOutbox();
        worker = Thread.ofVirtual().name("javaclaw-extension-jobs").start(this::runLoop);
        return recovered;
    }

    /**
     * 同步领取并推进至多一个 Outbox，用于确定性测试和受控调度。
     *
     * @return 是否领取到工作
     */
    public boolean runOnce() {
        if (closed.get()) {
            return false;
        }
        Optional<ExtensionJobService.ClaimedJob> claimed = jobs.claimNext();
        if (claimed.isEmpty()) {
            return false;
        }
        ExtensionJobService.ClaimedJob work = claimed.orElseThrow();
        try {
            process(work);
        } catch (PersistenceException concurrentChange) {
            jobs.retry(work, MISSING_EXECUTOR_RETRY);
        }
        return true;
    }

    private void process(ExtensionJobService.ClaimedJob claimed) {
        ExtensionJobExecutor executor = executors.get(
                new ExecutorKey(claimed.job().extensionId(), claimed.job().jobType()));
        if (executor == null) {
            jobs.retry(claimed, MISSING_EXECUTOR_RETRY);
            return;
        }
        ExtensionJobService.ClaimedJob executable = claimed;
        if (executable.unit().isEmpty()) {
            Optional<ExtensionJobWorkUnit> planned;
            try {
                planned = Objects.requireNonNull(executor.plan(executable.job()), "planned unit");
            } catch (RuntimeException failure) {
                fail(executable, "JOB_PLAN_FAILED", failure);
                return;
            }
            if (planned.isEmpty()) {
                jobs.completeWithoutUnit(executable);
                return;
            }
            executable = jobs.recordIntent(executable, planned.orElseThrow());
        }
        execute(executor, executable);
    }

    private void execute(ExtensionJobExecutor executor, ExtensionJobService.ClaimedJob claimed) {
        ExtensionJobActivityPort.Lease activity;
        try {
            activity = Objects.requireNonNull(activities.acquire(claimed.job()), "activity lease");
        } catch (RuntimeException unavailable) {
            jobs.retry(claimed, MISSING_EXECUTOR_RETRY);
            LOGGER.warn(
                    "Extension Job {} activity lease is unavailable",
                    claimed.job().id(),
                    unavailable);
            return;
        }
        CancellationSource cancellation = new CancellationSource();
        if (!activeCancellation.compareAndSet(null, cancellation)) {
            closeActivity(claimed, activity);
            throw new IllegalStateException("Supervisor already owns an active unit");
        }
        try {
            ExtensionJobExecution execution =
                    new ExtensionJobExecution(claimed.job(), claimed.unit().orElseThrow());
            ExtensionJobStepResult result = executor.execute(execution, cancellation);
            jobs.complete(claimed, Objects.requireNonNull(result, "step result"));
        } catch (OrchestratedTurnFailureException failure) {
            fail(
                    claimed,
                    new ExtensionJobFailureEvidence(
                            failure.errorCode(), Optional.of(failure.turnId()), failure.effectReceiptKey()),
                    failure);
        } catch (Exception failure) {
            fail(claimed, cancellation.isCancelled() ? "JOB_CANCELLED_DURING_UNIT" : "JOB_UNIT_FAILED", failure);
        } finally {
            activeCancellation.compareAndSet(cancellation, null);
            closeActivity(claimed, activity);
        }
    }

    private void closeActivity(ExtensionJobService.ClaimedJob claimed, ExtensionJobActivityPort.Lease activity) {
        try {
            activity.close();
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Extension Job {} activity lease release failed",
                    claimed.job().id(),
                    failure);
        }
    }

    private void fail(ExtensionJobService.ClaimedJob claimed, String errorCode, Throwable failure) {
        fail(claimed, ExtensionJobFailureEvidence.code(errorCode), failure);
    }

    private void fail(ExtensionJobService.ClaimedJob claimed, ExtensionJobFailureEvidence evidence, Throwable failure) {
        try {
            jobs.fail(claimed, evidence);
        } catch (PersistenceException concurrentChange) {
            try {
                jobs.retry(claimed, MISSING_EXECUTOR_RETRY);
            } catch (RuntimeException retryFailure) {
                concurrentChange.addSuppressed(retryFailure);
            }
            failure.addSuppressed(concurrentChange);
        }
        LOGGER.warn("Extension Job {} unit failed with {}", claimed.job().id(), evidence.errorCode());
    }

    private void runLoop() {
        while (!closed.get()) {
            try {
                if (!runOnce()) {
                    LockSupport.parkNanos(IDLE_NANOS);
                }
            } catch (RuntimeException failure) {
                LOGGER.error("Extension Job Supervisor iteration failed", failure);
                LockSupport.parkNanos(IDLE_NANOS);
            }
            if (Thread.interrupted() && closed.get()) {
                return;
            }
        }
    }

    /**
     * 发布取消并等待后台虚拟线程退出。
     *
     * <p>不中断正在执行的 JDBC 调用；H2 的文件通道在中断时会被异步关闭，可能破坏整个 data-v6。空闲等待只用 {@link LockSupport#unpark(Thread)} 唤醒，活动单元通过
     * CancellationToken 协作式收敛。
     */
    @Override
    public void close() throws InterruptedException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CancellationSource cancellation = activeCancellation.get();
        if (cancellation != null) {
            cancellation.cancel("Extension Job Supervisor 正在关闭");
        }
        Thread current = worker;
        if (current != null) {
            LockSupport.unpark(current);
            current.join(Duration.ofSeconds(5));
            if (current.isAlive()) {
                throw new IllegalStateException("Extension Job Supervisor did not stop within 5 seconds");
            }
        }
    }

    private record ExecutorKey(ExtensionId extensionId, String jobType) {
        private ExecutorKey {
            Objects.requireNonNull(extensionId, "extensionId");
            jobType = Objects.requireNonNull(jobType, "jobType").strip();
            if (!jobType.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
                throw new IllegalArgumentException("jobType contains unsupported characters");
            }
        }
    }
}
