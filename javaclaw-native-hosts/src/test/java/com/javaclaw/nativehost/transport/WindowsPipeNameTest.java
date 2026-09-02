package com.javaclaw.nativehost.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsPipeNameTest {
    @Test
    void acceptsOnlyBoundedLogicalNamesAndAddsLocalNamespace() {
        WindowsPipeName name = WindowsPipeName.parse("JavaClaw.test_5-0");

        assertEquals("JavaClaw.test_5-0", name.value());
        assertEquals("\\\\.\\pipe\\JavaClaw.test_5-0", name.nativePath());
        assertEquals(name, WindowsPipeName.parse(" JavaClaw.test_5-0 "));
        assertEquals(name.hashCode(), WindowsPipeName.parse("JavaClaw.test_5-0").hashCode());
        assertEquals(name.value(), name.toString());

        assertThrows(NullPointerException.class, () -> WindowsPipeName.parse(null));
        assertThrows(IllegalArgumentException.class, () -> WindowsPipeName.parse(""));
        assertThrows(IllegalArgumentException.class, () -> WindowsPipeName.parse("bad/name"));
        assertThrows(IllegalArgumentException.class, () -> WindowsPipeName.parse("bad\\name"));
        assertThrows(IllegalArgumentException.class, () -> WindowsPipeName.parse("a".repeat(129)));
    }

    @Test
    void currentUserNameIsStableHashedAndDoesNotExposeHome() {
        WindowsPipeName first = WindowsPipeName.currentUserDefault();
        WindowsPipeName second = WindowsPipeName.currentUserDefault();

        assertEquals(first, second);
        assertTrue(first.value().startsWith("javaclaw-app-server-v5-"));
        assertEquals(
                24, first.value().substring("javaclaw-app-server-v5-".length()).length());
        assertFalse(first.nativePath().contains(System.getProperty("user.home")));
        assertNotEquals(first, new Object());
    }

    @Test
    void nativeBackendFailsClosedOutsideWindows() {
        if (!System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("windows")) {
            assertFalse(WindowsNamedPipeTransport.isSupported());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> new WindowsNamedPipeTransport(WindowsPipeName.parse("javaclaw-test")).connect());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> WindowsNamedPipeRpcServer.bind(WindowsPipeName.parse("javaclaw-test")));
        }
    }
}
