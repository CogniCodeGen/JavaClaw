package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** 按平台读取 Sandbox 完整进程树的驻留内存；无法度量存活进程时拒绝继续执行。 */
final class SandboxMemoryMeter {
    private static final int RUSAGE_INFO_V0 = 0;
    private static final long RUSAGE_INFO_V0_BYTES = 96;
    private static final long PHYSICAL_FOOTPRINT_OFFSET = 72;
    private static final MethodHandle PROC_PID_RUSAGE = lookupMacUsage();
    private static final Platform PLATFORM = platform();

    private SandboxMemoryMeter() {}

    static long treeBytes(Process process) {
        ArrayList<ProcessHandle> handles = new ArrayList<>();
        handles.add(process.toHandle());
        handles.addAll(process.descendants().toList());
        long total = 0;
        boolean measured = false;
        for (ProcessHandle handle : handles) {
            if (!handle.isAlive()) {
                continue;
            }
            long bytes = residentBytesForLiveHandle(handle);
            if (bytes < 0) {
                throw new IllegalStateException("cannot measure live sandbox process memory");
            }
            if (bytes == 0 && !handle.isAlive()) {
                continue;
            }
            measured = true;
            total = Math.addExact(total, bytes);
        }
        return measured ? total : 0;
    }

    private static long residentBytes(long pid) {
        return switch (PLATFORM) {
            case MACOS -> macPhysicalFootprint(pid);
            case LINUX -> linuxResidentBytes(pid);
            case UNSUPPORTED -> throw new UnsupportedOperationException("sandbox memory metering is unavailable");
        };
    }

    private static long residentBytesForLiveHandle(ProcessHandle handle) {
        long bytes = residentBytes(handle.pid());
        if (bytes >= 0) {
            return bytes;
        }
        if (!handle.isAlive()) {
            return 0;
        }
        bytes = residentBytes(handle.pid());
        return bytes < 0 && !handle.isAlive() ? 0 : bytes;
    }

    private static long macPhysicalFootprint(long pid) {
        if (PROC_PID_RUSAGE == null || pid < 1 || pid > Integer.MAX_VALUE) {
            return -1;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment usage = arena.allocate(RUSAGE_INFO_V0_BYTES, JAVA_LONG.byteAlignment());
            int status = (int) PROC_PID_RUSAGE.invoke((int) pid, RUSAGE_INFO_V0, usage);
            return status == 0 ? usage.get(JAVA_LONG, PHYSICAL_FOOTPRINT_OFFSET) : -1;
        } catch (Throwable failure) {
            return -1;
        }
    }

    private static long linuxResidentBytes(long pid) {
        Path status = Path.of("/proc", Long.toString(pid), "status");
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:")) {
                    return parseKilobytes(line);
                }
            }
            return -1;
        } catch (IOException | NumberFormatException failure) {
            return -1;
        }
    }

    private static long parseKilobytes(String line) {
        List<String> fields = List.of(line.trim().split("\\s+"));
        if (fields.size() != 3 || !"kB".equals(fields.get(2))) {
            return -1;
        }
        return Math.multiplyExact(Long.parseLong(fields.get(1)), 1024L);
    }

    private static MethodHandle lookupMacUsage() {
        if (platform() != Platform.MACOS) {
            return null;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = linker.defaultLookup();
            return symbols.find("proc_pid_rusage")
                    .map(symbol ->
                            linker.downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)))
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static Platform platform() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("mac")) {
            return Platform.MACOS;
        }
        if (name.contains("linux")) {
            return Platform.LINUX;
        }
        return Platform.UNSUPPORTED;
    }

    private enum Platform {
        MACOS,
        LINUX,
        UNSUPPORTED
    }
}
