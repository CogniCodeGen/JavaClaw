package com.javaclaw.framework.spi;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/** Framework port for interruptible, timeout-bound work with observable real termination. */
public interface CancellableTaskExecutor extends Executor {
    <T> CancellableTask<T> submit(
            String name, Duration timeout, CancellationToken cancellation, Callable<T> task);
}
