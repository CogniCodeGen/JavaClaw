package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.javaclaw.extension.spi.ExtensionJobCursor;

/** Extension Job 管理页共用的稳定 keyset 游标编码。 */
final class ExtensionJobCursors {
    private ExtensionJobCursors() {}

    /**
     * 解码 ViewSchema 不透明游标。
     *
     * @param value 页面传回的游标；空串表示首页
     * @return 可选稳定游标
     */
    static Optional<ExtensionJobCursor> decode(String value) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        int separator = value.indexOf('|');
        if (separator < 1 || separator == value.length() - 1 || value.indexOf('|', separator + 1) >= 0) {
            throw new IllegalArgumentException("Execution cursor is invalid");
        }
        try {
            Instant updatedAt = Instant.parse(value.substring(0, separator));
            return Optional.of(new ExtensionJobCursor(updatedAt, value.substring(separator + 1)));
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Execution cursor timestamp is invalid", failure);
        }
    }

    /**
     * 编码可作为 ViewSchema 不透明值传回的游标。
     *
     * @param cursor 稳定游标
     * @return wire 字符串
     */
    static String encode(ExtensionJobCursor cursor) {
        return cursor.updatedAt() + "|" + cursor.id();
    }
}
