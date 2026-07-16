package com.javaclaw.util;

import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 线程安全的 {@link Disposable} 引用容器
 *
 * <p>替代散落在各处的 {@code volatile Disposable} + 手动判空/dispose 模式。
 * {@link #set(Disposable)} 会自动取消前一个订阅，{@link #dispose()} 使用 CAS 保证 dispose 只发生一次。</p>
 */
public final class AtomicDisposable {

    private final AtomicReference<Disposable> ref = new AtomicReference<>();

    /**
     * 设置新的 Disposable，自动 dispose 旧值。
     */
    public void set(Disposable next) {
        Disposable prev = ref.getAndSet(next);
        if (prev != null && !prev.isDisposed()) {
            prev.dispose();
        }
    }

    /**
     * 取消当前 Disposable；若已 dispose 或不存在则返回 false。
     */
    public boolean dispose() {
        Disposable current = ref.getAndSet(null);
        if (current != null && !current.isDisposed()) {
            current.dispose();
            return true;
        }
        return false;
    }

    /**
     * 清除引用但不 dispose（用于流自然结束的场景）。
     */
    public void clear() {
        ref.set(null);
    }

    /**
     * 仅当当前引用正是 {@code expected}（同一实例）时清除引用，不 dispose。
     *
     * <p>供可能<b>迟到</b>的收尾回调使用：跨轮/跨代的 doFinally 若无条件 {@link #clear()}
     * 会抹掉后继订阅的引用，使后续 {@link #dispose()} 扑空、在途流杀不掉。
     * {@code expected} 为 null 时不做任何事。</p>
     *
     * @return 是否发生了清除
     */
    public boolean clearIf(Disposable expected) {
        return expected != null && ref.compareAndSet(expected, null);
    }

    public boolean isActive() {
        Disposable current = ref.get();
        return current != null && !current.isDisposed();
    }
}
