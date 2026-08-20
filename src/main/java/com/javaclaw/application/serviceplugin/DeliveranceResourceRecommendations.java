package com.javaclaw.application.serviceplugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;

import java.lang.management.ManagementFactory;

/** Balanced first-install and explicit-reset process resources for Deliverance. */
public final class DeliveranceResourceRecommendations {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final ResourceConfiguration FALLBACK =
            new ResourceConfiguration(4096, 4096, 6, 64, 256);

    private DeliveranceResourceRecommendations() { }

    public static ResourceConfiguration balanced() { return balanced(FALLBACK); }

    public static ResourceConfiguration balanced(ResourceConfiguration fallback) {
        ResourceConfiguration checked = fallback == null ? FALLBACK : fallback;
        try {
            var os = (com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();
            return balanced(checked, Math.max(0, os.getTotalMemorySize()),
                    Runtime.getRuntime().availableProcessors());
        } catch (RuntimeException unavailable) {
            return checked;
        }
    }

    static ResourceConfiguration balanced(
            ResourceConfiguration fallback, long totalPhysicalBytes, int processors) {
        if (totalPhysicalBytes <= 0) return fallback;
        long reserved = Math.min(21L * GIB, totalPhysicalBytes * 3 / 5);
        long reservedMiB = reserved / (1024 * 1024);
        long heapMiB = Math.max(256, reservedMiB * 35 / 100);
        long nativeMiB = Math.max(0, reservedMiB - heapMiB);
        int threads = Math.max(1, Math.min(Math.min(8, fallback.computeThreads()),
                processors > 1 ? processors - 1 : 1));
        return new ResourceConfiguration(
                Math.toIntExact(heapMiB), Math.toIntExact(nativeMiB), threads,
                fallback.ioConcurrency(), fallback.fileDescriptors());
    }
}
