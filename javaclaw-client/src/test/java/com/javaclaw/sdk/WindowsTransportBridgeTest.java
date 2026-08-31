package com.javaclaw.sdk;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowsTransportBridgeTest {
    @Test
    void failsClosedOffWindowsWithoutStartingAProcess() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> WindowsTransportBridge.connect(List.of("not-started"), "javaclaw-v4-test"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> WindowsTransportBridge.startLocalAppServer(
                        List.of("not-started"), "javaclaw-v4-test", List.of("not-started"), Map.of()));
    }
}
