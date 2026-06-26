package com.javaclaw.plugin;

import com.javaclaw.plugin.api.PluginException;
import com.javaclaw.plugin.api.exec.Cancellation;
import com.javaclaw.plugin.api.exec.LoopBody;
import com.javaclaw.plugin.api.exec.ManagedTask;
import com.javaclaw.plugin.api.exec.PluginCallable;
import com.javaclaw.plugin.api.exec.PluginExecutor;
import com.javaclaw.plugin.api.exec.PluginTask;
import com.javaclaw.plugin.api.exec.ServiceHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 插件执行引擎 —— {@link PluginExecutor} 的宿主实现，每个插件独占一个实例。
 *
 * <p>核心机制：</p>
 * <ul>
 *   <li><b>每插件独占虚拟线程执行器</b>（{@code Executors.newThreadPerTaskExecutor} + 虚拟线程工厂，
 *       线程名 {@code plugin-{id}-vt-N}）：一任务一虚拟线程，海量廉价；停用时 {@code shutdownNow()}
 *       只中断本插件的虚拟线程，不波及宿主与其他插件。</li>
 *   <li><b>ScopedValue 身份绑定</b>：派发每段执行前把插件身份绑入 {@link PluginScope#CURRENT}，
 *       能力实现据此鉴权与审计（见 {@link CapabilityGuard}）。</li>
 *   <li><b>定时触发</b>用单个 daemon 平台线程（{@code plugin-{id}-timer}），到点仅负责把活派发到
 *       虚拟线程执行器，绝不在定时线程上跑插件代码。</li>
 *   <li><b>配额</b>：信号量限制并发任务数，超出自动排队（背压），防插件瞬时提交失控。</li>
 *   <li><b>句柄注册表</b>：所有 {@link ManagedTask}/{@link ServiceHandle} 登记在册，停用时统一取消。</li>
 * </ul>
 *
 * @author JavaClaw
 */
public final class PluginScheduler implements PluginExecutor {

    private static final Logger log = LoggerFactory.getLogger(PluginScheduler.class);

    private final String pluginId;
    private final PluginScope.PluginIdentity identity;

    /** 每插件独占的虚拟线程执行器（同步/异步/后台循环都跑在这里） */
    private final ExecutorService vexec;
    /** 单 daemon 平台线程，仅做定时触发，到点把活派发到 vexec */
    private final ScheduledExecutorService timer;
    /** 并发配额：超出则任务在虚拟线程内排队等待许可（背压） */
    private final Semaphore concurrencyGate;

    /** 活跃句柄注册表：停用时统一取消，自然结束的任务会自行移除 */
    private final Set<Live> liveHandles = ConcurrentHashMap.newKeySet();

    private volatile boolean shutdown = false;

    /**
     * @param pluginId           插件 id（线程命名 + 日志前缀）
     * @param identity           插件身份（绑入 ScopedValue 供鉴权）
     * @param maxConcurrentTasks 并发任务上限（≥1）
     */
    PluginScheduler(String pluginId, PluginScope.PluginIdentity identity, int maxConcurrentTasks) {
        this.pluginId = pluginId;
        this.identity = identity;

        ThreadFactory vtf = Thread.ofVirtual()
                .name("plugin-" + pluginId + "-vt-", 0)
                .inheritInheritableThreadLocals(false)
                .factory();
        this.vexec = Executors.newThreadPerTaskExecutor(vtf);

        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "plugin-" + pluginId + "-timer");
            t.setDaemon(true);
            return t;
        });

        this.concurrencyGate = new Semaphore(Math.max(1, maxConcurrentTasks));
        log.info("插件[{}]执行器已创建（并发上限 {}）", pluginId, Math.max(1, maxConcurrentTasks));
    }

    // ==================== PluginExecutor 实现 ====================

    @Override
    public ManagedTask submit(PluginTask task) {
        ensureActive();
        ManagedTaskImpl handle = new ManagedTaskImpl();
        Future<?> f = vexec.submit(() -> {
            try {
                runGuarded(task, "异步任务");
            } finally {
                liveHandles.remove(handle);
            }
        });
        handle.bind(f, () -> liveHandles.remove(handle));
        liveHandles.add(handle);
        return handle;
    }

    @Override
    public <T> T call(PluginCallable<T> task) throws Exception {
        ensureActive();
        Future<T> f = vexec.submit(() -> {
            concurrencyGate.acquire();
            try {
                // 绑定插件身份后执行，使同步调用内部触发的能力调用也能被鉴权/审计
                return ScopedValue.where(PluginScope.CURRENT, identity).call(task::call);
            } finally {
                concurrencyGate.release();
            }
        });
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("插件[{}]同步调用失败：{}", pluginId, cause.toString());
            throw new PluginException.PluginExecException("插件[" + pluginId + "]同步调用失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw e;
        }
    }

    @Override
    public ServiceHandle background(String name, LoopBody loop) {
        ensureActive();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Cancellation signal = cancelled::get;
        ServiceHandleImpl handle = new ServiceHandleImpl(cancelled);
        Future<?> f = vexec.submit(() -> {
            log.info("插件[{}]后台服务[{}]已启动", pluginId, name);
            try {
                ScopedValue.where(PluginScope.CURRENT, identity).run(() -> {
                    try {
                        loop.run(signal);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.info("插件[{}]后台服务[{}]被中断退出", pluginId, name);
                    } catch (Exception e) {
                        log.warn("插件[{}]后台服务[{}]异常退出：{}", pluginId, name, e.toString(), e);
                    }
                });
            } finally {
                liveHandles.remove(handle);
                log.info("插件[{}]后台服务[{}]已结束", pluginId, name);
            }
        });
        handle.bind(f, () -> liveHandles.remove(handle));
        liveHandles.add(handle);
        return handle;
    }

    @Override
    public ManagedTask schedule(Duration delay, PluginTask task) {
        ensureActive();
        ManagedTaskImpl handle = new ManagedTaskImpl();
        ScheduledFuture<?> sf = timer.schedule(
                () -> vexec.submit(() -> {
                    try {
                        runGuarded(task, "延时任务");
                    } finally {
                        liveHandles.remove(handle);
                    }
                }),
                Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
        handle.bind(sf, () -> liveHandles.remove(handle));
        liveHandles.add(handle);
        return handle;
    }

    @Override
    public ManagedTask scheduleAtRate(Duration period, PluginTask task) {
        ensureActive();
        ManagedTaskImpl handle = new ManagedTaskImpl();
        long ms = Math.max(1, period.toMillis());
        // 周期任务：每次触发派发到虚拟线程执行；句柄保留至显式取消
        ScheduledFuture<?> sf = timer.scheduleAtFixedRate(
                () -> vexec.submit(() -> runGuarded(task, "周期任务")),
                ms, ms, TimeUnit.MILLISECONDS);
        handle.bind(sf, () -> liveHandles.remove(handle));
        liveHandles.add(handle);
        return handle;
    }

    // ==================== 生命周期（宿主调用） ====================

    /** 取消该插件全部活跃句柄（停用回收第 2 步）。 */
    void cancelAllHandles() {
        int n = liveHandles.size();
        for (Live h : liveHandles) {
            try {
                h.cancel();
            } catch (Exception e) {
                log.debug("插件[{}]取消句柄时忽略异常：{}", pluginId, e.toString());
            }
        }
        liveHandles.clear();
        if (n > 0) {
            log.info("插件[{}]已取消 {} 个活跃任务/服务句柄", pluginId, n);
        }
    }

    /** 关闭执行器（停用回收第 3 步）：取消句柄 → 中断全部虚拟线程 → 关定时线程。 */
    void shutdown() {
        shutdown = true;
        cancelAllHandles();
        timer.shutdownNow();
        vexec.shutdownNow();
        try {
            if (!vexec.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("插件[{}]虚拟线程未在 5 秒内全部结束（可能有未响应中断的循环）", pluginId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("插件[{}]执行器已关闭", pluginId);
    }

    // ==================== 内部辅助 ====================

    private void ensureActive() {
        if (shutdown) {
            throw new IllegalStateException("插件[" + pluginId + "]执行器已关闭，无法再提交任务");
        }
    }

    /** 取许可 → 绑定身份 → 执行 → 释放许可；异常被吞并记录，绝不外溢拖垮宿主。 */
    private void runGuarded(PluginTask task, String label) {
        try {
            concurrencyGate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            ScopedValue.where(PluginScope.CURRENT, identity).run(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("插件[{}]{}执行异常：{}", pluginId, label, e.toString(), e);
                }
            });
        } finally {
            concurrencyGate.release();
        }
    }

    // ==================== 句柄实现 ====================

    /** 注册表内部统一取消接口（ManagedTask / ServiceHandle 共用） */
    private interface Live {
        void cancel();
    }

    /** {@link ManagedTask} 实现：包裹 Future/ScheduledFuture，对外仅暴露取消与状态查询。 */
    private static final class ManagedTaskImpl implements ManagedTask, Live {
        private volatile Future<?> future;
        private volatile Runnable onCancel = () -> {
        };

        void bind(Future<?> f, Runnable onCancel) {
            this.future = f;
            this.onCancel = onCancel;
        }

        @Override
        public void cancel() {
            Future<?> f = future;
            if (f != null) {
                f.cancel(true);
            }
            onCancel.run();
        }

        @Override
        public boolean isDone() {
            Future<?> f = future;
            return f != null && f.isDone();
        }
    }

    /** {@link ServiceHandle} 实现：取消即翻转协作信号 + 中断承载虚拟线程，双保险退出循环。 */
    private static final class ServiceHandleImpl implements ServiceHandle, Live {
        private final AtomicBoolean cancelled;
        private volatile Future<?> future;
        private volatile Runnable onCancel = () -> {
        };

        ServiceHandleImpl(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }

        void bind(Future<?> f, Runnable onCancel) {
            this.future = f;
            this.onCancel = onCancel;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            Future<?> f = future;
            if (f != null) {
                f.cancel(true);
            }
            onCancel.run();
        }

        @Override
        public boolean isRunning() {
            Future<?> f = future;
            return f != null && !f.isDone();
        }
    }
}
