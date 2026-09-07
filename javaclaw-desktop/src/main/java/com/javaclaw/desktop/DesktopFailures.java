package com.javaclaw.desktop;

import java.util.Objects;
import java.util.Optional;

/** Desktop 状态只保留有界、可展示的失败摘要。 */
final class DesktopFailures {
    private DesktopFailures() {}

    static String safeMessage(Throwable failure) {
        Throwable checked = Objects.requireNonNull(failure, "failure");
        String message = Optional.ofNullable(checked.getMessage())
                .orElse(checked.getClass().getSimpleName());
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    static String requireText(String value, String name) {
        String normalized = java.util.Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
