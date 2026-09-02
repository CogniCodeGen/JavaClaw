package com.javaclaw.extension.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 扩展稳定标识。
 *
 * @param value 反向域名风格的小写标识
 */
public record ExtensionId(String value) implements Comparable<ExtensionId> {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+");

    /** 校验扩展标识。 */
    public ExtensionId {
        value = Objects.requireNonNull(value, "value").strip();
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid extension id: " + value);
        }
    }

    @Override
    public int compareTo(ExtensionId other) {
        return value.compareTo(other.value);
    }
}
