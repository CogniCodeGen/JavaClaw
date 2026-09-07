package com.javaclaw.nativehost.sandbox;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.SandboxFrame;

/** 将首个输出观察失败投影为本次执行的取消信号；不修改外部令牌或中断任意调用线程。 */
final class SandboxOutputObservation implements CancellationToken {
    private final CancellationToken cancellation;
    private final Consumer<SandboxFrame> observer;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    SandboxOutputObservation(CancellationToken cancellation, Consumer<SandboxFrame> observer) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    Consumer<byte[]> channel(String channel) {
        return bytes -> emit(channel, bytes);
    }

    private synchronized void emit(String channel, byte[] bytes) {
        if (failure.get() != null) {
            return;
        }
        try {
            observer.accept(new SandboxFrame(channel, bytes, Instant.now()));
        } catch (RuntimeException rejected) {
            failure.compareAndSet(null, rejected);
        } catch (Error rejected) {
            failure.compareAndSet(null, new IllegalStateException("sandbox output observer failed", rejected));
        }
    }

    @Override
    public boolean isCancelled() {
        return failure.get() != null || cancellation.isCancelled();
    }

    @Override
    public Optional<String> reason() {
        return failure.get() == null ? cancellation.reason() : Optional.of("sandbox output observer failed");
    }

    void throwIfFailed() {
        RuntimeException rejected = failure.get();
        if (rejected != null) {
            throw rejected;
        }
    }

    Exception preferFailure(Exception cleanup) {
        RuntimeException rejected = failure.get();
        if (rejected == null || rejected == cleanup) {
            return cleanup;
        }
        rejected.addSuppressed(cleanup);
        return rejected;
    }
}
