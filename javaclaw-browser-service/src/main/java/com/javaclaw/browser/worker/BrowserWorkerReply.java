package com.javaclaw.browser.worker;

import java.util.Arrays;
import java.util.Objects;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;

/** Worker 命令的最终消息与可选敏感二进制帧。 */
record BrowserWorkerReply(BrowserWorkerProtocol.WorkerMessage message, byte[] sensitiveBytes) implements AutoCloseable {
    BrowserWorkerReply {
        Objects.requireNonNull(message, "message");
        sensitiveBytes =
                Objects.requireNonNull(sensitiveBytes, "sensitiveBytes").clone();
        if (message.binaryBytes() != sensitiveBytes.length) {
            throw new IllegalArgumentException("Worker result binary length does not match its metadata");
        }
    }

    public byte[] sensitiveBytes() {
        return sensitiveBytes.clone();
    }

    @Override
    public void close() {
        Arrays.fill(sensitiveBytes, (byte) 0);
    }
}
