package com.javaclaw.server.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.TurnId;
import com.javaclaw.server.persistence.ApprovalService;

/** 把持久审批等待投影为生命周期 lease，并在决议后恢复所属 Turn。 */
public final class ApprovalLifecycleCoordinator implements AutoCloseable {
    private static final Duration EXPIRY_GRACE = Duration.ofMinutes(1);
    private static final Duration MINIMUM_LIFETIME = Duration.ofSeconds(1);
    private static final Consumer<TurnId> UNBOUND = ignored -> {};

    private final LifecycleCoordinator lifecycle;
    private final Clock clock;
    private final Map<String, LifecycleCoordinator.Lease> leases = new HashMap<>();
    private final AtomicReference<Consumer<TurnId>> resume = new AtomicReference<>(UNBOUND);
    private boolean closed;

    /**
     * 创建协调器，并为硬崩溃后仍 PENDING 的审批重建 lease。
     *
     * @param approvals 审批权威服务
     * @param lifecycle App Server 生命周期
     * @param clock 平台时钟
     */
    public ApprovalLifecycleCoordinator(ApprovalService approvals, LifecycleCoordinator lifecycle, Clock clock) {
        ApprovalService checkedApprovals = Objects.requireNonNull(approvals, "approvals");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.clock = Objects.requireNonNull(clock, "clock");
        checkedApprovals.onChanged(this::synchronize);
        checkedApprovals.list(Optional.empty(), false).forEach(this::synchronize);
    }

    /**
     * 绑定 Turn 恢复端口；只允许组合根绑定一次。
     *
     * @param resumeTurn 按 Turn ID 重建执行栈的端口
     */
    public void bindResume(Consumer<TurnId> resumeTurn) {
        Consumer<TurnId> checked = Objects.requireNonNull(resumeTurn, "resumeTurn");
        if (!resume.compareAndSet(UNBOUND, checked)) {
            throw new IllegalStateException("approval resume port is already bound");
        }
    }

    private synchronized void synchronize(ApprovalRecord record) {
        if (closed) {
            return;
        }
        ApprovalRecord checked = Objects.requireNonNull(record, "record");
        String approvalId = checked.request().id();
        if (checked.pending()) {
            leases.computeIfAbsent(approvalId, ignored -> acquire(checked));
            return;
        }
        LifecycleCoordinator.Lease lease = leases.remove(approvalId);
        if (lease != null) {
            lease.close();
        }
        resume.get().accept(checked.request().turnId());
    }

    private LifecycleCoordinator.Lease acquire(ApprovalRecord record) {
        Duration remaining =
                Duration.between(clock.instant(), record.request().expiresAt()).plus(EXPIRY_GRACE);
        Duration lifetime = remaining.compareTo(MINIMUM_LIFETIME) < 0 ? MINIMUM_LIFETIME : remaining;
        return lifecycle.acquireActivity("approval:" + record.request().id(), "INTERACTION", lifetime);
    }

    /** 释放本进程建立的审批 lease；权威审批状态仍保留在 H2。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        leases.values().forEach(LifecycleCoordinator.Lease::close);
        leases.clear();
    }
}
