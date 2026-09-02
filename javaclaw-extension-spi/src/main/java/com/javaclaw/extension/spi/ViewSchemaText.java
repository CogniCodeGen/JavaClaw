package com.javaclaw.extension.spi;

import java.util.Objects;

/** ViewSchema 契约内部的文本规范化方法。 */
final class ViewSchemaText {
    private ViewSchemaText() {}

    static String required(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
