package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * Extension 调用结果。
 *
 * @param payload 规范化输出
 * @param revision 执行后的资源版本；只读结果沿用读取版本
 */
public record ExtensionResponse(CanonicalPayload payload, long revision) {
    /** 校验结果与版本。 */
    public ExtensionResponse {
        Objects.requireNonNull(payload, "payload");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
