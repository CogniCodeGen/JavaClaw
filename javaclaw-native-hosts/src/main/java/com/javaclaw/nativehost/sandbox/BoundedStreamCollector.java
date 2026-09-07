package com.javaclaw.nativehost.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 持续排空进程流，但只保留共享字节预算内的前缀。 */
final class BoundedStreamCollector implements Callable<byte[]> {
    private static final int BUFFER_BYTES = 8 * 1024;

    private final InputStream input;
    private final AtomicLong remaining;
    private final Consumer<byte[]> observer;
    private final BooleanSupplier cancelled;

    BoundedStreamCollector(InputStream input, AtomicLong remaining) {
        this(input, remaining, bytes -> {});
    }

    BoundedStreamCollector(InputStream input, AtomicLong remaining, Consumer<byte[]> observer) {
        this(input, remaining, observer, () -> false);
    }

    BoundedStreamCollector(
            InputStream input, AtomicLong remaining, Consumer<byte[]> observer, BooleanSupplier cancelled) {
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.input = Objects.requireNonNull(input, "input");
        this.remaining = Objects.requireNonNull(remaining, "remaining");
    }

    @Override
    public byte[] call() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int count;
        try {
            while ((count = input.read(buffer)) >= 0) {
                int allowed = reserve(count);
                if (allowed > 0) {
                    captured.write(buffer, 0, allowed);
                    // 仅复制已保留前缀；回调完成前不复用缓冲区或读取下一块，避免无界排队。
                    observer.accept(Arrays.copyOf(buffer, allowed));
                }
            }
        } catch (IOException closed) {
            // 受控终止会关闭 Java 进程管道；已取消时仍交还前缀，其他读取失败不得伪装 EOF。
            if (!cancelled.getAsBoolean()) {
                throw closed;
            }
        }
        return captured.toByteArray();
    }

    private int reserve(int requested) {
        while (true) {
            long available = remaining.get();
            if (available <= 0) {
                return 0;
            }
            int allowed = (int) Math.min(available, requested);
            if (remaining.compareAndSet(available, available - allowed)) {
                return allowed;
            }
        }
    }
}
