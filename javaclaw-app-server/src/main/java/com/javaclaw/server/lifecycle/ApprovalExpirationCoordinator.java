package com.javaclaw.server.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.server.persistence.ApprovalService;

/**
 * 把持久审批的绝对 {@code expiresAt} 投影为进程内到期任务，并用周期扫描修复丢失的唤醒。
 *
 * <p>本协调器不拥有审批状态：每次唤醒都回到 {@link ApprovalService} 的事务和审批写锁内重新判断。关闭只取消本进程的投影任务，不改变未到期审批；下次启动仍按数据库中的原始绝对时间恢复。
 *
 * <p>实现说明：调度器只有一个守护线程。审批变更会重新计算最近期限，无变更时仍按固定上限扫描，避免一次调度失败永久留下 PENDING 审批。
 */
public final class ApprovalExpirationCoordinator implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApprovalExpirationCoordinator.class);
    private static final Duration MAXIMUM_SCAN_INTERVAL = Duration.ofSeconds(30);
    private static final Duration FAILURE_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final ApprovalService approvals;
    private final Clock clock;
    private final Duration maximumScanInterval;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledScan;
    private boolean closed;

    /**
     * 创建审批到期投影，并立即恢复数据库中的 PENDING 审批。
     *
     * @param approvals 审批权威服务
     * @param clock 平台时钟
     */
    public ApprovalExpirationCoordinator(ApprovalService approvals, Clock clock) {
        this(approvals, clock, MAXIMUM_SCAN_INTERVAL);
    }

    ApprovalExpirationCoordinator(ApprovalService approvals, Clock clock, Duration maximumScanInterval) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumScanInterval = requirePositive(maximumScanInterval);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("javaclaw-approval-expiration")
                .unstarted(runnable));
        approvals.onChanged(ignored -> reschedule());
        projectAndReschedule();
    }

    private void projectAndReschedule() {
        synchronized (this) {
            if (closed) {
                return;
            }
            scheduledScan = null;
        }
        boolean failed = false;
        try {
            approvals.expireDue();
        } catch (RuntimeException failure) {
            failed = true;
            LOGGER.warn("审批到期投影执行失败，将按退避时间重试", failure);
        }
        if (failed) {
            schedule(earlier(FAILURE_RETRY_DELAY, maximumScanInterval));
        } else {
            reschedule();
        }
    }

    private synchronized void reschedule() {
        if (closed) {
            return;
        }
        cancelScheduledScan();
        schedule(nextDelay());
    }

    private synchronized void schedule(Duration delay) {
        if (closed) {
            return;
        }
        cancelScheduledScan();
        scheduledScan = scheduler.schedule(this::projectAndReschedule, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    private Duration nextDelay() {
        Instant now = clock.instant();
        Optional<Instant> nearest = approvals.list(Optional.empty(), false).stream()
                .map(record -> record.request().expiresAt())
                .min(Comparator.naturalOrder());
        if (nearest.isEmpty()) {
            return maximumScanInterval;
        }
        Duration remaining = Duration.between(now, nearest.orElseThrow());
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ZERO;
        }
        return earlier(remaining, maximumScanInterval);
    }

    private void cancelScheduledScan() {
        if (scheduledScan != null) {
            scheduledScan.cancel(false);
            scheduledScan = null;
        }
    }

    private static Duration earlier(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static Duration requirePositive(Duration value) {
        Duration checked = Objects.requireNonNull(value, "maximumScanInterval");
        if (checked.isNegative() || checked.isZero()) {
            throw new IllegalArgumentException("maximumScanInterval must be positive");
        }
        return checked;
    }

    /**
     * 取消进程内到期任务；不会修改仍为 PENDING 的持久审批。
     *
     * <p>关闭不会中断正在执行的 H2 投影事务。中断文件通道会使共享 MVStore 进入关闭状态，因此必须等待当前扫描自然完成。
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            cancelScheduledScan();
        }
        scheduler.shutdown();
        awaitShutdown();
    }

    private void awaitShutdown() {
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Approval expiration scheduler did not stop");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Approval expiration shutdown was interrupted", failure);
        }
    }
}
