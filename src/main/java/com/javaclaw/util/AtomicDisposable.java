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

    public boolean isActive() {
        Disposable current = ref.get();
        return current != null && !current.isDisposed();
    }
}
