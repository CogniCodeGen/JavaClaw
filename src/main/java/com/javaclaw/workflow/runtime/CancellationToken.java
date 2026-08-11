package com.javaclaw.workflow.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** 跨线程取消/暂停令牌；取消钩子用于 dispose 正在流式执行的 AgentScope 调用。 */
public final class CancellationToken {
    private static final Logger log = LoggerFactory.getLogger(CancellationToken.class);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Runnable> cancelHooks = new CopyOnWriteArrayList<>();

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        for (Runnable hook : cancelHooks) {
            try {
                hook.run();
            } catch (Throwable hookFailure) {
                log.debug("取消钩子执行失败，继续通知其余钩子", hookFailure);
            }
        }
        cancelHooks.clear();
        return true;
    }

    public boolean requestPause() { return pauseRequested.compareAndSet(false, true); }
    public boolean isCancelled() { return cancelled.get(); }
    public boolean isPauseRequested() { return pauseRequested.get(); }

    public AutoCloseable onCancel(Runnable hook) {
        if (hook == null) return () -> {};
        if (cancelled.get()) {
            hook.run();
            return () -> {};
        }
        cancelHooks.add(hook);
        if (cancelled.get() && cancelHooks.remove(hook)) hook.run();
        return () -> cancelHooks.remove(hook);
    }

    public void throwIfCancelled() {
        if (cancelled.get()) throw new GraphCancelledException();
    }
}
