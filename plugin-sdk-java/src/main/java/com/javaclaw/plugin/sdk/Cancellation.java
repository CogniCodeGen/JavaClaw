package com.javaclaw.plugin.sdk;

import java.util.concurrent.CancellationException;

/** 取消可协作传播；宿主仍可在排空超时后终止整个插件进程。 */
@FunctionalInterface
public interface Cancellation {
    boolean isCancelled();
    default void throwIfCancelled() {
        if (isCancelled()) throw new CancellationException("插件调用已取消");
    }
}
