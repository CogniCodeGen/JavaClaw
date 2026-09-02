package com.javaclaw.nativehost.ffm;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ResourceLimits;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PosixPtyTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(64L * 1024 * 1024, 4096, 1, 16);

    @Test
    void opensValidPtyAndDefensivelyClosesDescriptor() {
        Assumptions.assumeTrue(PosixPty.isSupported());
        PosixPty terminal = PosixPty.open(80, 24);
        assertTrue(terminal.slavePath().isAbsolute());
        assertTrue(terminal.slavePath().startsWith("/dev"));

        terminal.write(new byte[0]);
        assertThrows(IllegalStateException.class, terminal::closeInput);
        assertThrows(IllegalArgumentException.class, () -> terminal.read(0));
        assertThrows(IllegalArgumentException.class, () -> terminal.read(1024 * 1024 + 1));
        assertThrows(IllegalArgumentException.class, () -> terminal.resize(19, 24));

        terminal.close();
        terminal.close();
        assertThrows(IllegalStateException.class, () -> terminal.write(new byte[] {1}));
        assertThrows(IllegalStateException.class, () -> terminal.read(1));
        assertThrows(IllegalStateException.class, () -> terminal.resize(80, 24));
    }

    @Test
    void validatesDimensionsAndNullWritesBeforeNativeIo() {
        Assumptions.assumeTrue(PosixPty.isSupported());
        assertThrows(IllegalArgumentException.class, () -> PosixPty.open(19, 24));
        assertThrows(IllegalArgumentException.class, () -> PosixPty.open(80, 4));
        assertThrows(IllegalArgumentException.class, () -> PosixPty.open(1001, 24));
        assertThrows(IllegalArgumentException.class, () -> PosixPty.open(80, 1001));
        try (PosixPty terminal = PosixPty.open(80, 24)) {
            assertThrows(NullPointerException.class, () -> terminal.write(null));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> PosixPty.attachControllingTerminalAndExec(List.of("/bin/echo"), 19, 24, LIMITS));
    }

    @Test
    void unsupportedPlatformFailsClosed() {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Plan 9");
            assertFalse(PosixPty.isSupported());
            assertThrows(UnsupportedOperationException.class, () -> PosixPty.open(80, 24));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> PosixPty.attachControllingTerminalAndExec(List.of("target"), 80, 24, LIMITS));
        } finally {
            restoreOsName(original);
        }
    }

    @Test
    void nativeResourceLimitEntryPointsValidateWithoutMutatingLimits() {
        assertThrows(
                NullPointerException.class,
                () -> NativeResourceLimits.leadProcessGroupApplyLimitsAndExec(null, Duration.ofSeconds(1), LIMITS));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeResourceLimits.leadProcessGroupApplyLimitsAndExec(
                        List.of(), Duration.ofSeconds(1), LIMITS));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeResourceLimits.applyAddressSpaceLimitAndExec(List.of(""), LIMITS));
        assertThrows(NullPointerException.class, () -> NativeResourceLimits.exec(Arrays.asList("target", null)));
        assertThrows(NullPointerException.class, () -> NativeResourceLimits.applyCpuAndOpenFileLimits(null, LIMITS));
        assertThrows(
                NullPointerException.class,
                () -> NativeResourceLimits.applyCpuAndOpenFileLimits(Duration.ofSeconds(1), null));
        assertThrows(UnsupportedOperationException.class, () -> NativeResourceLimits.createProcessGroup(0));
        assertThrows(
                UnsupportedOperationException.class, () -> NativeResourceLimits.createProcessGroup(Long.MAX_VALUE));
        assertThrows(UnsupportedOperationException.class, () -> NativeResourceLimits.killProcessGroup(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeResourceLimits.signalProcessGroup(
                        ProcessHandle.current().pid(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeResourceLimits.signalProcessGroup(
                        ProcessHandle.current().pid(), 65));
    }

    @Test
    void resourceLimitOperationsFailClosedOnUnsupportedPlatform() {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Plan 9");
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> NativeResourceLimits.leadProcessGroupApplyLimitsAndExec(
                            List.of("missing"), Duration.ofSeconds(1), LIMITS));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> NativeResourceLimits.applyCpuAndOpenFileLimits(Duration.ofSeconds(1), LIMITS));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> NativeResourceLimits.applyAddressSpaceLimitAndExec(List.of("missing"), LIMITS));
        } finally {
            restoreOsName(original);
        }
    }

    @Test
    void execReportsMissingTargetWithoutReplacingTestProcess() {
        Assumptions.assumeTrue(PosixPty.isSupported());
        assertThrows(
                IllegalStateException.class,
                () -> NativeResourceLimits.exec(List.of("/javaclaw-target-that-does-not-exist")));
        assertThrows(IllegalArgumentException.class, () -> NativeResourceLimits.leadProcessGroupAndExec(List.of()));
    }

    @Test
    void macAddressSpaceExecSkipsUnsupportedLimitAndReportsMissingTarget() {
        Assumptions.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("mac"));
        assertThrows(
                IllegalStateException.class,
                () -> NativeResourceLimits.applyAddressSpaceLimitAndExec(
                        List.of("/javaclaw-target-that-does-not-exist"), LIMITS));
    }

    private static void restoreOsName(String original) {
        if (original == null) {
            System.clearProperty("os.name");
        } else {
            System.setProperty("os.name", original);
        }
    }
}
