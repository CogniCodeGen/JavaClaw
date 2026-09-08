package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.concurrent.CompletionException;

/** 声明式页面错误投影；保留原有 revision 冲突类型检查和默认显示文字。 */
final class ViewSchemaPageFailures {
    static String documentLabel(
            com.javaclaw.protocol.ExtensionRpcContracts.ViewDocument value,
            com.javaclaw.protocol.ViewSchemaWireCodec schemas) {
        try {
            return schemas.decode(value.schema()).title();
        } catch (RuntimeException failure) {
            return value.viewId();
        }
    }

    static String requireText(String value, String name) {
        String normalized = java.util.Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }

    private ViewSchemaPageFailures() {}

    static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static String detail(Throwable failure) {
        String message = unwrap(failure).getMessage();
        return message == null || message.isBlank() ? "扩展返回了不受支持的页面数据。" : message;
    }
}
