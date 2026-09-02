package com.javaclaw.api;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单向取消源；首个原因获胜，后续取消不会覆盖诊断信息。
 *
 * <p>取消只发布状态，不中断任意线程。调用方必须在模型、工具与持久化边界检查 token 并关闭自己拥有的资源。
 */
public final class CancellationSource implements CancellationToken {
    private final AtomicReference<String> reason = new AtomicReference<>();

    /** 创建尚未取消的信号源。 */
    public CancellationSource() {}

    /**
     * 发布取消；只有首次调用返回 true。
     *
     * @param value 脱敏后的原因
     * @return 本次是否成为首个取消原因
     */
    public boolean cancel(String value) {
        return reason.compareAndSet(null, Preconditions.text(value, "reason"));
    }

    @Override
    public boolean isCancelled() {
        return reason.get() != null;
    }

    @Override
    public Optional<String> reason() {
        return Optional.ofNullable(reason.get());
    }
}
