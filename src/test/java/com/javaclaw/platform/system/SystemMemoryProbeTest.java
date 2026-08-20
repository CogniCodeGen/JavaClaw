package com.javaclaw.platform.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemMemoryProbeTest {
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024L * MIB;

    @Test
    void macIncludesInactivePagesInsteadOfUsingRawFreePages() {
        String vmStat = """
                Mach Virtual Memory Statistics: (page size of 16384 bytes)
                Pages free:                              104329.
                Pages active:                            901231.
                Pages inactive:                          695411.
                Pages purgeable:                          14123.
                """;

        SystemMemoryProbe.Snapshot result = SystemMemoryProbe.resolve(
                "Mac OS X", 32 * GIB, 132 * MIB, vmStat);

        assertTrue(result.availableReliable());
        assertEquals((104329L + 695411L) * 16384, result.availableBytes());
    }

    @Test
    void macMarksRawFreeFallbackAsUnreliableWhenVmStatCannotBeParsed() {
        SystemMemoryProbe.Snapshot result = SystemMemoryProbe.resolve(
                "Mac OS X", 32 * GIB, 132 * MIB, "not vm_stat output");

        assertFalse(result.availableReliable());
        assertEquals(132 * MIB, result.availableBytes());
    }

    @Test
    void linuxPrefersKernelMemAvailable() {
        String meminfo = """
                MemTotal:       33554432 kB
                MemFree:          131072 kB
                MemAvailable:   12582912 kB
                Active(file):    1048576 kB
                Inactive(file):  2097152 kB
                SReclaimable:     524288 kB
                """;

        SystemMemoryProbe.Snapshot result = SystemMemoryProbe.resolve(
                "Linux", 32 * GIB, 256 * MIB, meminfo);

        assertTrue(result.availableReliable());
        assertEquals(12 * GIB, result.availableBytes());
    }

    @Test
    void linuxFallsBackToReclaimableFileMemoryForOlderKernels() {
        String meminfo = """
                MemTotal:       16777216 kB
                MemFree:          262144 kB
                Active(file):     524288 kB
                Inactive(file):  1048576 kB
                SReclaimable:     262144 kB
                """;

        SystemMemoryProbe.Snapshot result = SystemMemoryProbe.resolve(
                "Linux", 16 * GIB, 128 * MIB, meminfo);

        assertTrue(result.availableReliable());
        assertEquals(2 * GIB, result.availableBytes());
    }

    @Test
    void linuxUsesContainerAwareJdkValueWhenHostMeminfoExceedsTheLimit() {
        String meminfo = """
                MemTotal:       67108864 kB
                MemAvailable:   52428800 kB
                """;

        SystemMemoryProbe.Snapshot result = SystemMemoryProbe.resolve(
                "Linux", 8 * GIB, 3 * GIB, meminfo);

        assertTrue(result.availableReliable());
        assertEquals(8 * GIB, result.totalBytes());
        assertEquals(3 * GIB, result.availableBytes());
    }

    @Test
    void linuxMarksTheRawFallbackAsUnreliableWhenMeminfoCannotBeParsed() {
        SystemMemoryProbe.Snapshot result = SystemMemoryProbe.resolve(
                "Linux", 16 * GIB, 128 * MIB, "not meminfo");

        assertFalse(result.availableReliable());
        assertEquals(128 * MIB, result.availableBytes());
    }

    @Test
    void windowsJdkValueRepresentsAvailablePhysicalMemory() {
        SystemMemoryProbe.Snapshot result = SystemMemoryProbe.resolve(
                "Windows 11", 16 * GIB, 6 * GIB, "");

        assertTrue(result.availableReliable());
        assertEquals(6 * GIB, result.availableBytes());
    }
}
