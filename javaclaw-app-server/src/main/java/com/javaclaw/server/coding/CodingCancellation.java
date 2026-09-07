package com.javaclaw.server.coding;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.server.turn.CodingExecutionAuthority;

/** 将实时撤权、扩展停用和 Turn 取消合并为只收窄的执行取消信号。 */
final class CodingCancellation implements CancellationToken {
    private final CodingInvocation invocation;
    private final CodingExecutionAuthority authority;
    private final AtomicReference<String> stopped = new AtomicReference<>();
    private volatile long checkedAt;

    CodingCancellation(CodingInvocation invocation, CodingExecutionAuthority authority) {
        this.invocation = invocation;
        this.authority = authority;
    }

    void cancel(String reason) {
        stopped.compareAndSet(null, reason);
    }

    @Override
    public boolean isCancelled() {
        if (invocation.cancellation().isCancelled()) {
            cancel(invocation.cancellation().reason().orElse("Turn 已取消"));
        }
        long now = System.nanoTime();
        if (stopped.get() == null && now - checkedAt > 100_000_000L) {
            checkedAt = now;
            try {
                authority.requireUnchanged(invocation.turn().id(), invocation.permission());
            } catch (RuntimeException revoked) {
                cancel("Coding 权限已撤销或权威状态不可读取");
            }
        }
        return stopped.get() != null;
    }

    @Override
    public Optional<String> reason() {
        isCancelled();
        return Optional.ofNullable(stopped.get());
    }
}
