package com.javaclaw.server.lifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.server.persistence.LifecycleLeaseRepository;

/**
 * 统一跟踪客户端与持久活动 lease，并在持续空闲后请求进程退出。
 *
 * <p>并发不变量：客户端计数、空闲计时器与协作式停止状态都由 {@code lock} 保护。停止一经接受便拒绝新连接和新 lease，避免响应写回窗口内启动的新工作被中断。
 */
public final class LifecycleCoordinator implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleCoordinator.class);
    private static final Duration PERSISTENT_LEASE_LIFETIME = Duration.ofMinutes(10);
    private static final Duration PERSISTENT_LEASE_RENEWAL = Duration.ofMinutes(5);

    /** 产品规定的无客户端、无活动 lease 等待时间。 */
    public static final Duration DEFAULT_IDLE_DELAY = Duration.ofSeconds(60);

    private final LifecycleLeaseRepository leases;
    private final Duration idleDelay;
    private final ScheduledExecutorService scheduler;
    private final CompletableFuture<Void> shutdownRequested = new CompletableFuture<>();
    private final Object lock = new Object();
    private int clients;
    private ScheduledFuture<?> idleCheck;
    private boolean controlledShutdownScheduled;
    private boolean closed;

    /**
     * 创建使用 60 秒空闲窗口的协调器。
     *
     * @param leases 持久 lease 仓储
     */
    public LifecycleCoordinator(LifecycleLeaseRepository leases) {
        this(leases, DEFAULT_IDLE_DELAY);
    }

    /**
     * 创建协调器。
     *
     * @param leases 持久 lease 仓储
     * @param idleDelay 无客户端、无 lease 后的固定等待时间
     */
    public LifecycleCoordinator(LifecycleLeaseRepository leases, Duration idleDelay) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.idleDelay = requirePositive(idleDelay);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon(true).name("javaclaw-idle-shutdown").unstarted(runnable));
        synchronized (lock) {
            scheduleIfIdle();
        }
    }

    /**
     * 标记一个已连接客户端；关闭返回句柄时开始重新评估空闲退出。
     *
     * @return 必须关闭的连接句柄
     */
    public Lease clientConnected() {
        synchronized (lock) {
            requireOpen();
            clients++;
            cancelIdleCheck();
        }
        return once(this::clientDisconnected);
    }

    /**
     * 为活动 Turn、等待交互或 Schedule 创建持久 lease。
     *
     * @param owner 持有者
     * @param kind TURN、INTERACTION 或 SCHEDULE 等稳定类别
     * @param lifetime 崩溃后的最长残留时间
     * @return 必须关闭的 lease
     */
    public Lease acquireActivity(String owner, String kind, Duration lifetime) {
        UUID id = leases.acquire(owner, kind, lifetime);
        synchronized (lock) {
            requireOpenOrRelease(id);
            cancelIdleCheck();
        }
        return once(() -> activityReleased(id));
    }

    /**
     * 为启用的 Schedule 等长期后台能力创建自动续期 lease。
     *
     * <p>实现说明：每五分钟续期到十分钟后；进程崩溃后不会再续期，残留行会自动过期。
     *
     * @param owner 稳定持有者
     * @param kind 稳定类别
     * @return 必须关闭的 lease
     */
    public Lease acquirePersistentActivity(String owner, String kind) {
        String checkedOwner = Objects.requireNonNull(owner, "owner");
        String checkedKind = Objects.requireNonNull(kind, "kind");
        UUID id = leases.acquire(checkedOwner, checkedKind, PERSISTENT_LEASE_LIFETIME);
        synchronized (lock) {
            requireOpenOrRelease(id);
            cancelIdleCheck();
        }
        PersistentLease lease = new PersistentLease(id, checkedOwner, checkedKind);
        lease.start();
        return lease;
    }

    /**
     * 阻塞至满足空闲退出条件。
     *
     * @throws InterruptedException 当前线程被中断
     */
    public void awaitShutdownRequest() throws InterruptedException {
        try {
            shutdownRequested.get();
        } catch (ExecutionException impossible) {
            throw new IllegalStateException("shutdown signal failed", impossible);
        }
    }

    /**
     * 返回是否已经满足空闲退出条件。
     *
     * @return 已请求退出时为 true
     */
    public boolean shutdownRequested() {
        return shutdownRequested.isDone();
    }

    /**
     * 返回不含持有者身份的当前计数。
     *
     * @return 连接与活动 lease 数
     */
    public Status status() {
        int connected;
        synchronized (lock) {
            connected = clients;
        }
        return new Status(connected, leases.activeCount());
    }

    /**
     * 请求由托盘发起的协作式退出。
     *
     * <p>当前控制 RPC 连接计入一个客户端；存在第二个客户端或任意持久 lease 时拒绝。接受后延迟 250 毫秒发出信号，给 JSON-RPC 响应留出完整写回时间。
     *
     * @return 不含持有者身份的门禁决策
     */
    public ShutdownDecision requestControlledShutdown() {
        synchronized (lock) {
            Status current = new Status(clients, leases.activeCount());
            if (closed) {
                return new ShutdownDecision(false, current, "App Server 正在关闭");
            }
            if (controlledShutdownScheduled) {
                return new ShutdownDecision(false, current, "App Server 正在关闭");
            }
            if (current.activeLeases() > 0) {
                return new ShutdownDecision(false, current, "存在活动 Turn、交互或 Schedule lease");
            }
            if (current.connectedClients() > 1) {
                return new ShutdownDecision(false, current, "仍有其他客户端连接 App Server");
            }
            if (current.connectedClients() == 0) {
                return new ShutdownDecision(false, current, "未检测到托盘控制连接");
            }
            cancelIdleCheck();
            controlledShutdownScheduled = true;
            scheduler.schedule(() -> shutdownRequested.complete(null), 250, TimeUnit.MILLISECONDS);
            return new ShutdownDecision(true, current, "");
        }
    }

    private void clientDisconnected() {
        synchronized (lock) {
            if (clients > 0) {
                clients--;
            }
            scheduleIfIdle();
        }
    }

    private void activityReleased(UUID id) {
        leases.release(id);
        synchronized (lock) {
            scheduleIfIdle();
        }
    }

    private void scheduleIfIdle() {
        if (closed || controlledShutdownScheduled || shutdownRequested.isDone() || clients != 0 || idleCheck != null) {
            return;
        }
        idleCheck = scheduler.schedule(this::checkIdle, idleDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void checkIdle() {
        synchronized (lock) {
            idleCheck = null;
            if (closed || shutdownRequested.isDone() || clients != 0) {
                return;
            }
            if (leases.activeCount() == 0) {
                shutdownRequested.complete(null);
            } else {
                scheduleIfIdle();
            }
        }
    }

    private void cancelIdleCheck() {
        if (idleCheck != null) {
            idleCheck.cancel(false);
            idleCheck = null;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("lifecycle coordinator is closed");
        }
        if (controlledShutdownScheduled) {
            throw new IllegalStateException("lifecycle coordinator is stopping");
        }
    }

    private void requireOpenOrRelease(UUID id) {
        try {
            requireOpen();
        } catch (IllegalStateException unavailable) {
            leases.release(id);
            throw unavailable;
        }
    }

    private static Lease once(Runnable release) {
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                release.run();
            }
        };
    }

    private static Duration requirePositive(Duration value) {
        Duration checked = Objects.requireNonNull(value, "idleDelay");
        if (checked.isNegative() || checked.isZero()) {
            throw new IllegalArgumentException("idleDelay must be positive");
        }
        return checked;
    }

    /** 取消计时器并唤醒等待退出的线程。 */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            cancelIdleCheck();
            shutdownRequested.complete(null);
        }
        scheduler.shutdownNow();
    }

    private final class PersistentLease implements Lease {
        private final AtomicReference<UUID> id;
        private final AtomicBoolean released = new AtomicBoolean();
        private final String owner;
        private final String kind;
        private volatile ScheduledFuture<?> renewal;

        private PersistentLease(UUID id, String owner, String kind) {
            this.id = new AtomicReference<>(id);
            this.owner = owner;
            this.kind = kind;
        }

        private void start() {
            renewal = scheduler.scheduleAtFixedRate(
                    this::renew,
                    PERSISTENT_LEASE_RENEWAL.toMillis(),
                    PERSISTENT_LEASE_RENEWAL.toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        private void renew() {
            if (released.get()) {
                return;
            }
            UUID current = id.get();
            try {
                if (leases.renew(current, PERSISTENT_LEASE_LIFETIME)) {
                    return;
                }
                UUID replacement = leases.acquire(owner, kind, PERSISTENT_LEASE_LIFETIME);
                if (!id.compareAndSet(current, replacement)) {
                    leases.release(replacement);
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("Lifecycle lease renewal failed for {}", owner, failure);
            }
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> currentRenewal = renewal;
            if (currentRenewal != null) {
                currentRenewal.cancel(false);
            }
            activityReleased(id.get());
        }
    }

    /** 客户端或活动资源的幂等关闭句柄。 */
    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        /** 释放持有关系；重复调用安全。 */
        @Override
        void close();
    }

    /**
     * 生命周期非敏感计数。
     *
     * @param connectedClients 当前连接数
     * @param activeLeases 当前未过期 lease 数
     */
    public record Status(int connectedClients, int activeLeases) {
        /** 校验计数。 */
        public Status {
            if (connectedClients < 0 || activeLeases < 0) {
                throw new IllegalArgumentException("lifecycle counts must not be negative");
            }
        }
    }

    /**
     * @param accepted 是否接受协作式退出
     * @param status 决策时的非敏感计数
     * @param reason 被拒绝时的通俗原因；接受时为空字符串
     */
    public record ShutdownDecision(boolean accepted, Status status, String reason) {
        /** 校验决策。 */
        public ShutdownDecision {
            Objects.requireNonNull(status, "status");
            reason = Objects.requireNonNull(reason, "reason").strip();
            if (accepted == !reason.isEmpty()) {
                throw new IllegalArgumentException("shutdown decision and reason disagree");
            }
        }
    }
}
