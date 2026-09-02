package com.javaclaw.nativehost.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.javaclaw.api.ResourceLimits;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** 仅供独立 Sandbox helper 使用的 libc 资源限制与进程组绑定。 */
public final class NativeResourceLimits {
    private static final MemoryLayout RLIMIT =
            MemoryLayout.structLayout(JAVA_LONG.withName("current"), JAVA_LONG.withName("maximum"));
    private static final MethodHandle SET_RLIMIT =
            lookup("setrlimit", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle SET_PGID = lookup("setpgid", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle GET_PID = lookup("getpid", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GET_PGRP = lookup("getpgrp", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle KILL = lookup("kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle EXECVP = lookup("execvp", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private NativeResourceLimits() {}

    /**
     * 预先分配 exec 参数，建立进程组，施加 CPU、地址空间和文件描述符上限后原子替换为目标进程。
     *
     * <p>实现说明：RLIMIT_AS 最后设置；其后不再分配 Java 对象或原生内存，避免 helper JVM 的保留地址空间影响 exec 前准备。
     *
     * @param arguments 目标可执行文件和参数
     * @param timeout CPU 时间上限的基础值
     * @param limits 资源上限
     */
    public static void leadProcessGroupApplyLimitsAndExec(
            List<String> arguments, Duration timeout, ResourceLimits limits) {
        List<String> argv = checkedArguments(arguments);
        Duration checkedTimeout = Objects.requireNonNull(timeout, "timeout");
        ResourceLimits checkedLimits = Objects.requireNonNull(limits, "limits");
        LimitResources resources = LimitResources.current();
        if (resources == null || SET_RLIMIT == null || SET_PGID == null || EXECVP == null) {
            throw new UnsupportedOperationException("POSIX resource limits are unavailable");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vector = argumentVector(arena, argv);
            MemorySegment cpu = limit(arena, Math.max(1, checkedTimeout.toSeconds() + 1));
            MemorySegment files = limit(arena, checkedLimits.openFiles());
            MemorySegment memory = limit(arena, checkedLimits.memoryBytes());
            establishOwnProcessGroup();
            set(resources.cpu(), cpu);
            set(resources.openFiles(), files);
            if (resources.addressSpace() >= 0) {
                set(resources.addressSpace(), memory);
            }
            int status = (int) EXECVP.invoke(vector.getAtIndex(ADDRESS, 0), vector);
            throw new IllegalStateException("execvp failed with status " + status);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot apply resource limits and exec target", failure);
        }
    }

    /**
     * 对当前 PTY helper 施加 CPU 时间与打开文件数上限；地址空间限制延迟到 exec 前，避免影响 JVM 准备阶段。
     *
     * @param timeout CPU 时间上限的基础值
     * @param limits 资源上限
     */
    public static void applyCpuAndOpenFileLimits(Duration timeout, ResourceLimits limits) {
        Duration checkedTimeout = Objects.requireNonNull(timeout, "timeout");
        ResourceLimits checkedLimits = Objects.requireNonNull(limits, "limits");
        LimitResources resources = LimitResources.current();
        if (resources == null || SET_RLIMIT == null) {
            throw new UnsupportedOperationException("POSIX resource limits are unavailable");
        }
        set(resources.cpu(), Math.max(1, checkedTimeout.toSeconds() + 1), Math.max(1, checkedTimeout.toSeconds() + 1));
        set(resources.openFiles(), checkedLimits.openFiles(), checkedLimits.openFiles());
    }

    /**
     * 预先分配 argv，在支持的平台施加地址空间上限，再原子替换当前进程。
     *
     * @param arguments 目标 argv
     * @param limits 资源上限
     */
    public static void applyAddressSpaceLimitAndExec(List<String> arguments, ResourceLimits limits) {
        List<String> argv = checkedArguments(arguments);
        ResourceLimits checkedLimits = Objects.requireNonNull(limits, "limits");
        LimitResources resources = LimitResources.current();
        if (resources == null || EXECVP == null) {
            throw new UnsupportedOperationException("POSIX exec is unavailable");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vector = argumentVector(arena, argv);
            if (resources.addressSpace() >= 0) {
                MemorySegment memory = limit(arena, checkedLimits.memoryBytes());
                set(resources.addressSpace(), memory);
            }
            int status = (int) EXECVP.invoke(vector.getAtIndex(ADDRESS, 0), vector);
            throw new IllegalStateException("execvp failed with status " + status);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot apply address-space limit and exec target", failure);
        }
    }

    /** 将直接 Sandbox 子进程设置为独立 POSIX 进程组。 */
    public static void createProcessGroup(long pid) {
        if (SET_PGID == null || pid < 1 || pid > Integer.MAX_VALUE) {
            throw new UnsupportedOperationException("POSIX process groups are unavailable");
        }
        try {
            int status = (int) SET_PGID.invoke((int) pid, (int) pid);
            if (status != 0) {
                throw new IllegalStateException("setpgid failed");
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot create process group", failure);
        }
    }

    /** 强制终止仍属于 Sandbox 子进程组的所有进程。 */
    public static void killProcessGroup(long pid) {
        signalProcessGroup(pid, 9);
    }

    /** 向完整 Sandbox 进程组发送 POSIX signal。 */
    public static void signalProcessGroup(long pid, int signal) {
        if (KILL == null || pid < 1 || pid > Integer.MAX_VALUE) {
            throw new UnsupportedOperationException("POSIX process-group kill is unavailable");
        }
        if (signal < 1 || signal > 64) {
            throw new IllegalArgumentException("invalid POSIX signal");
        }
        try {
            int status = (int) KILL.invoke(-(int) pid, signal);
            if (status != 0) {
                throw new IllegalStateException("process-group kill failed");
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot kill process group", failure);
        }
    }

    /** 将当前 helper 建为进程组 leader，再原子替换为目标命令，避免父进程在 exec 后调用 setpgid 的竞态。 */
    public static void leadProcessGroupAndExec(List<String> arguments) {
        exec(arguments, true);
    }

    /** 将已隔离 helper 原子替换为目标命令。 */
    public static void exec(List<String> arguments) {
        exec(arguments, false);
    }

    private static void exec(List<String> arguments, boolean createProcessGroup) {
        List<String> argv = checkedArguments(arguments);
        if (SET_PGID == null || EXECVP == null) {
            throw new UnsupportedOperationException("process-group exec is unavailable");
        }
        try (Arena arena = Arena.ofConfined()) {
            if (createProcessGroup) {
                establishOwnProcessGroup();
            }
            MemorySegment vector = argumentVector(arena, argv);
            int status = (int) EXECVP.invoke(vector.getAtIndex(ADDRESS, 0), vector);
            throw new IllegalStateException("execvp failed with status " + status);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot establish sandbox process group", failure);
        }
    }

    private static List<String> checkedArguments(List<String> arguments) {
        List<String> argv = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("exec argv must contain non-empty values");
        }
        return argv;
    }

    private static MemorySegment argumentVector(Arena arena, List<String> argv) {
        MemorySegment vector = arena.allocate(ADDRESS.byteSize() * (argv.size() + 1L), ADDRESS.byteAlignment());
        for (int index = 0; index < argv.size(); index++) {
            vector.setAtIndex(ADDRESS, index, arena.allocateFrom(argv.get(index)));
        }
        vector.setAtIndex(ADDRESS, argv.size(), MemorySegment.NULL);
        return vector;
    }

    private static void establishOwnProcessGroup() throws Throwable {
        int grouped = (int) SET_PGID.invoke(0, 0);
        if (grouped != 0 && !isOwnProcessGroupLeader()) {
            throw new IllegalStateException("self setpgid failed");
        }
    }

    private static MemorySegment limit(Arena arena, long value) {
        MemorySegment result = arena.allocate(RLIMIT);
        result.set(JAVA_LONG, 0, value);
        result.set(JAVA_LONG, JAVA_LONG.byteSize(), value);
        return result;
    }

    private static void set(int resource, MemorySegment limits) throws Throwable {
        int status = (int) SET_RLIMIT.invoke(resource, limits);
        if (status != 0) {
            throw new IllegalStateException("setrlimit failed for resource " + resource);
        }
    }

    private static boolean isOwnProcessGroupLeader() throws Throwable {
        if (GET_PID == null || GET_PGRP == null) {
            return false;
        }
        int process = (int) GET_PID.invoke();
        int group = (int) GET_PGRP.invoke();
        return process > 0 && process == group;
    }

    private static void set(int resource, long current, long maximum) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment limits = arena.allocate(RLIMIT);
            limits.set(JAVA_LONG, 0, current);
            limits.set(JAVA_LONG, JAVA_LONG.byteSize(), maximum);
            int status = (int) SET_RLIMIT.invoke(resource, limits);
            if (status != 0) {
                throw new IllegalStateException("setrlimit failed for resource " + resource);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot invoke setrlimit", failure);
        }
    }

    private static MethodHandle lookup(String name, FunctionDescriptor descriptor) {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = linker.defaultLookup();
            return symbols.find(name)
                    .map(symbol -> linker.downcallHandle(symbol, descriptor))
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private record LimitResources(int cpu, int addressSpace, int openFiles) {
        private static LimitResources current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) {
                // macOS 26 对 RLIMIT_AS 返回 EINVAL；宿主通过 proc_pid_rusage 对整个进程树执行内存硬终止。
                return new LimitResources(0, -1, 8);
            }
            if (os.contains("linux")) {
                return new LimitResources(0, 9, 7);
            }
            return null;
        }
    }
}
