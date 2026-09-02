package com.javaclaw.nativehost.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/** 持续排空进程流，但只保留共享字节预算内的前缀。 */
final class BoundedStreamCollector implements Callable<byte[]> {
    private static final int BUFFER_BYTES = 8 * 1024;

    private final InputStream input;
    private final AtomicLong remaining;

    BoundedStreamCollector(InputStream input, AtomicLong remaining) {
        this.input = Objects.requireNonNull(input, "input");
        this.remaining = Objects.requireNonNull(remaining, "remaining");
    }

    @Override
    public byte[] call() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            int allowed = reserve(count);
            if (allowed > 0) {
                captured.write(buffer, 0, allowed);
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
