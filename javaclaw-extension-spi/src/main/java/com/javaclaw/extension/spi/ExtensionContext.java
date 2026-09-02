package com.javaclaw.extension.spi;

import java.time.Clock;
import java.util.Objects;

/**
 * 内置 Bundle 启动上下文；第三方 Bundle 不会接触此 Java 接口。
 *
 * @param clock 平台时钟
 * @param payloads 共享契约编解码器
 */
public record ExtensionContext(Clock clock, ExtensionPayloadCodec payloads) {
    /** 校验启动端口。 */
    public ExtensionContext {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(payloads, "payloads");
    }
}
