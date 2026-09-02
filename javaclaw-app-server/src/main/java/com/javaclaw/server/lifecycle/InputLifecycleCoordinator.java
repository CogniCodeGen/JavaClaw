package com.javaclaw.server.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.server.persistence.InputRequestService;

/** 把持久化 InputRequest 投影为 App Server 交互生命周期 lease。 */
public final class InputLifecycleCoordinator implements AutoCloseable {
    private static final Duration EXPIRY_GRACE = Duration.ofMinutes(1);
    private static final Duration MINIMUM_LIFETIME = Duration.ofSeconds(1);

    private final LifecycleCoordinator lifecycle;
    private final Clock clock;
    private final Map<String, LifecycleCoordinator.Lease> leases = new HashMap<>();
    private boolean closed;

    /**
     * 创建协调器，先收口到期请求，再为重启后仍等待的请求重建 lease。
     *
     * @param inputs InputRequest 权威服务
     * @param lifecycle App Server 生命周期协调器
     * @param clock 平台时钟
     */
    public InputLifecycleCoordinator(InputRequestService inputs, LifecycleCoordinator lifecycle, Clock clock) {
        InputRequestService checkedInputs = Objects.requireNonNull(inputs, "inputs");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.clock = Objects.requireNonNull(clock, "clock");
        checkedInputs.onChanged(this::synchronize);
        checkedInputs.expireDue();
        checkedInputs.list(Optional.empty(), false).forEach(this::synchronize);
    }

    private synchronized void synchronize(InputRequestRecord record) {
        if (closed) {
            return;
        }
        InputRequestRecord checked = Objects.requireNonNull(record, "record");
        if (checked.pending()) {
            leases.computeIfAbsent(checked.request().id(), ignored -> acquire(checked));
            return;
        }
        LifecycleCoordinator.Lease lease = leases.remove(checked.request().id());
        if (lease != null) {
            lease.close();
        }
    }

    private LifecycleCoordinator.Lease acquire(InputRequestRecord record) {
        Duration remaining =
                Duration.between(clock.instant(), record.request().expiresAt()).plus(EXPIRY_GRACE);
        Duration lifetime = remaining.compareTo(MINIMUM_LIFETIME) < 0 ? MINIMUM_LIFETIME : remaining;
        return lifecycle.acquireActivity("input:" + record.request().id(), "INTERACTION", lifetime);
    }

    /** 释放本进程建立的交互 lease；权威 InputRequest 保留，下次启动会重建投影。 */
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
