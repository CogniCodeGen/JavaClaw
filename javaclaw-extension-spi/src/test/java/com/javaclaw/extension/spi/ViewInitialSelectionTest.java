package com.javaclaw.extension.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewInitialSelectionTest {
    @Test
    void 精确引用允许现有最长模型名组合键及零版本() {
        String key = "endpoint/" + "m".repeat(1000);
        var hint = new ViewInitialSelection(1, "providers", key, "revision", 0);
        assertEquals(key, hint.key());
        assertEquals(0, hint.revision());
    }

    @Test
    void 不支持的版本和越界引用不可作为有效初选() {
        assertThrows(
                IllegalArgumentException.class, () -> new ViewInitialSelection(2, "roles", "saved", "revision", 1));
        assertThrows(
                IllegalArgumentException.class, () -> new ViewInitialSelection(1, "roles", "saved", "revision", -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewInitialSelection(1, "r".repeat(129), "saved", "revision", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewInitialSelection(1, "roles", "r".repeat(2049), "revision", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewInitialSelection(1, "roles", "saved", "r".repeat(129), 1));
    }
}
