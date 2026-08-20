package com.javaclaw.application.serviceplugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeliveranceResourceRecommendationsTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void usesSixtyPercentWithTwentyOneGibGuardAndThirtyFivePercentHeap() {
        ResourceConfiguration fallback = new ResourceConfiguration(4096, 4096, 6, 64, 256);

        ResourceConfiguration ordinary = DeliveranceResourceRecommendations.balanced(
                fallback, 16L * GIB, 12);
        assertEquals(3_440, ordinary.heapMiB());
        assertEquals(6_390, ordinary.nativeMemoryMiB());
        assertEquals(6, ordinary.computeThreads());

        ResourceConfiguration guarded = DeliveranceResourceRecommendations.balanced(
                fallback, 128L * GIB, 32);
        assertEquals(7_526, guarded.heapMiB());
        assertEquals(13_978, guarded.nativeMemoryMiB());
        assertEquals(21L * 1024, guarded.reservedMemoryMiB());
    }

    @Test
    void preservesFallbackWhenPhysicalMemoryIsUnavailableAndLeavesOneCpuFree() {
        ResourceConfiguration fallback = new ResourceConfiguration(3072, 2048, 6, 32, 128);
        assertEquals(fallback, DeliveranceResourceRecommendations.balanced(fallback, 0, 8));
        assertEquals(3, DeliveranceResourceRecommendations.balanced(
                fallback, 8L * GIB, 4).computeThreads());
    }
}
