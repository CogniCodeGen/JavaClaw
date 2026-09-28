package com.javaclaw.framework.spi;

import java.util.Objects;

/** 固定上下文的版本化正文；空正文表示来源当前没有可注入内容。 */
public record FixedContextSnapshot(String version, String body) {
    public FixedContextSnapshot {
        version = Objects.requireNonNull(version, "version");
        body = Objects.requireNonNull(body, "body");
        if (version.isBlank()) throw new IllegalArgumentException("fixed context version is blank");
    }
}
