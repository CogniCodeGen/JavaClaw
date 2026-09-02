package com.javaclaw.server.extension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 持续排空子进程输出，但只在内存中保留固定上限。 */
final class BoundedProcessOutput {
    private static final int BUFFER_BYTES = 8 * 1024;

    private BoundedProcessOutput() {}

    static Capture read(InputStream input, long maximumBytes) throws IOException {
        InputStream checked = Objects.requireNonNull(input, "input");
        if (maximumBytes < 1 || maximumBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumBytes is outside the in-memory bound");
        }
        ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.toIntExact(Math.min(maximumBytes, 16_384)));
        byte[] buffer = new byte[BUFFER_BYTES];
        long observed = 0;
        int read;
        while ((read = checked.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            long before = observed;
            observed = Math.addExact(observed, read);
            int keep = Math.toIntExact(Math.min(read, Math.max(0, maximumBytes - before)));
            if (keep > 0) {
                retained.write(buffer, 0, keep);
            }
        }
        return new Capture(retained.toString(StandardCharsets.UTF_8), observed > maximumBytes);
    }

    /**
     * 有界输出投影。
     *
     * @param text UTF-8 文本
     * @param truncated 是否丢弃超出上限的字节
     */
    record Capture(String text, boolean truncated) {
        Capture {
            Objects.requireNonNull(text, "text");
        }
    }
}
