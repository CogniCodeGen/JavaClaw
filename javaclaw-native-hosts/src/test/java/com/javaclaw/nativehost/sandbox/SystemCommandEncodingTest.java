package com.javaclaw.nativehost.sandbox;

import java.nio.charset.Charset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemCommandEncodingTest {
    @Test
    void Windows按可信OEM代码页选择编码而不是默认UTF8() {
        assertEquals(Charset.forName("GBK").name(), SystemCommandEncoding.resolve("Windows 11", () -> 936));
        assertEquals(Charset.forName("IBM437").name(), SystemCommandEncoding.resolve("Windows 10", () -> 437));
        assertEquals("UTF-8", SystemCommandEncoding.resolve("Windows 11", () -> 65001));
    }

    @Test
    void 非Windows不调用原生替身且原生失败不能猜测编码() {
        assertEquals("UTF-8", SystemCommandEncoding.resolve("Mac OS X", () -> {
            throw new AssertionError();
        }));
        assertThrows(IllegalStateException.class, () -> SystemCommandEncoding.resolve("Windows 11", () -> 0));
    }
}
