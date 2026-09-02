package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * PTY 或管道的有界输出帧。
 *
 * @param channel stdout、stderr 或 terminal
 * @param bytes 帧内容；所有权由本对象持有
 * @param observedAt 宿主接收时间
 */
public record SandboxFrame(String channel, byte[] bytes, Instant observedAt) {
    /** 复制帧并校验通道。 */
    public SandboxFrame {
        channel = Preconditions.text(channel, "channel");
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
        Objects.requireNonNull(observedAt, "observedAt");
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
