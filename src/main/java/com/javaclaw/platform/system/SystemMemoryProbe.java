package com.javaclaw.platform.system;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cross-platform physical-memory capacity with an explicit available-memory confidence flag. */
public final class SystemMemoryProbe {
    private static final long KIB = 1024L;
    private static final long MIB = KIB * KIB;
    private static final Pattern MAC_PAGE_SIZE =
            Pattern.compile("page size of\\s+(\\d+)\\s+bytes", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAC_PAGE_COUNT = Pattern.compile(
            "(?m)^Pages\\s+(free|inactive):\\s*(\\d+)\\.\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LINUX_MEMORY = Pattern.compile(
            "(?m)^(MemTotal|MemAvailable|MemFree|Active\\(file\\)|Inactive\\(file\\)|"
                    + "SReclaimable):\\s*(\\d+)\\s+kB\\s*$");

    private SystemMemoryProbe() { }

    public static Snapshot read() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalMemorySize();
            long rawFree = os.getFreeMemorySize();
            String name = System.getProperty("os.name", "");
            return resolve(name, total, rawFree, platformDetails(name));
        } catch (RuntimeException unavailable) {
            return Snapshot.unavailable();
        }
    }

    static Snapshot resolve(String osName, long jdkTotal, long jdkFree, String details) {
        long total = Math.max(0, jdkTotal);
        long raw = bounded(jdkFree, total);
        if (total == 0) return Snapshot.unavailable();
        String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            OptionalLong available = parseMacAvailable(details);
            return available.isPresent()
                    ? new Snapshot(total, available.getAsLong(), true)
                    : new Snapshot(total, raw, false);
        }
        if (normalized.contains("linux")) {
            LinuxReading reading = parseLinux(details);
            if (reading.availableBytes() >= 0) {
                long tolerance = Math.max(64L * MIB, total / 20);
                boolean containerLimited = reading.totalBytes() > total
                        && reading.totalBytes() - total > tolerance;
                if (!containerLimited) {
                    return new Snapshot(total, reading.availableBytes(), true);
                }
                return jdkFree >= 0
                        ? new Snapshot(total, raw, true)
                        : new Snapshot(total, 0, false);
            }
            return new Snapshot(total, raw, false);
        }
        if (normalized.contains("win")) {
            return jdkFree >= 0
                    ? new Snapshot(total, raw, true)
                    : new Snapshot(total, 0, false);
        }
        return new Snapshot(total, raw, false);
    }

    static OptionalLong parseMacAvailable(String output) {
        if (output == null || output.isBlank()) return OptionalLong.empty();
        Matcher sizeMatch = MAC_PAGE_SIZE.matcher(output);
        if (!sizeMatch.find()) return OptionalLong.empty();
        try {
            long pageSize = Long.parseLong(sizeMatch.group(1));
            long free = -1;
            long inactive = 0;
            Matcher counts = MAC_PAGE_COUNT.matcher(output);
            while (counts.find()) {
                long value = Long.parseLong(counts.group(2));
                if ("free".equalsIgnoreCase(counts.group(1))) free = value;
                else inactive = value;
            }
            if (pageSize <= 0 || free < 0) return OptionalLong.empty();
            return OptionalLong.of(Math.multiplyExact(Math.addExact(free, inactive), pageSize));
        } catch (NumberFormatException | ArithmeticException invalid) {
            return OptionalLong.empty();
        }
    }

    static LinuxReading parseLinux(String input) {
        if (input == null || input.isBlank()) return LinuxReading.unavailable();
        long total = -1;
        long available = -1;
        long free = 0;
        long activeFile = 0;
        long inactiveFile = 0;
        long reclaimable = 0;
        try {
            Matcher values = LINUX_MEMORY.matcher(input);
            while (values.find()) {
                long bytes = Math.multiplyExact(Long.parseLong(values.group(2)), KIB);
                switch (values.group(1)) {
                    case "MemTotal" -> total = bytes;
                    case "MemAvailable" -> available = bytes;
                    case "MemFree" -> free = bytes;
                    case "Active(file)" -> activeFile = bytes;
                    case "Inactive(file)" -> inactiveFile = bytes;
                    case "SReclaimable" -> reclaimable = bytes;
                    default -> { }
                }
            }
            if (available < 0 && total >= 0) {
                available = Math.addExact(Math.addExact(free, activeFile),
                        Math.addExact(inactiveFile, reclaimable));
            }
            return new LinuxReading(total, available);
        } catch (NumberFormatException | ArithmeticException invalid) {
            return LinuxReading.unavailable();
        }
    }

    private static String platformDetails(String osName) {
        String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("mac") || normalized.contains("darwin")) return vmStat();
        if (normalized.contains("linux")) {
            try {
                return Files.readString(Path.of("/proc/meminfo"), StandardCharsets.US_ASCII);
            } catch (IOException | SecurityException unavailable) {
                return "";
            }
        }
        return "";
    }

    private static String vmStat() {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("/usr/bin/vm_stat");
            builder.environment().put("LC_ALL", "C");
            builder.redirectErrorStream(true);
            process = builder.start();
            if (!process.waitFor(1, TimeUnit.SECONDS)) return "";
            if (process.exitValue() != 0) return "";
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException | SecurityException unavailable) {
            return "";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static long bounded(long value, long total) {
        if (value <= 0 || total <= 0) return 0;
        return Math.min(value, total);
    }

    public record Snapshot(long totalBytes, long availableBytes, boolean availableReliable) {
        public Snapshot {
            totalBytes = Math.max(0, totalBytes);
            availableBytes = totalBytes == 0 ? 0
                    : Math.max(0, Math.min(totalBytes, availableBytes));
            if (totalBytes == 0) availableReliable = false;
        }

        static Snapshot unavailable() { return new Snapshot(0, 0, false); }
    }

    record LinuxReading(long totalBytes, long availableBytes) {
        static LinuxReading unavailable() { return new LinuxReading(-1, -1); }
    }
}
