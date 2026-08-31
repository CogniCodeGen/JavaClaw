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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Minimal libc bindings used only by the isolated launcher JVM. */
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

    /** 仅在独立 Launcher 中设置 POSIX CPU 时间和文件描述符上限，子进程继承限制；Windows 由后续 Job Object 限制，此处不降权。timeout 用于计算 CPU 秒数。 */
    public static void applyLauncherLimits(Duration timeout) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            // The Windows helper applies Restricted Token/AppContainer and Job Object limits
            // before resuming the target. Windows has no setrlimit equivalent.
            return;
        }
        if (SET_RLIMIT == null) {
            throw new UnsupportedOperationException("setrlimit is unavailable on this platform");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        int noFileResource = os.contains("mac") ? 8 : 7;
        long cpuSeconds = Math.max(1, timeout.toSeconds() + 2);
        set(0, cpuSeconds, cpuSeconds);
        set(noFileResource, 256, 256);
        // Do not lower RLIMIT_AS on the already-started launcher JVM. HotSpot reserves a large
        // sparse virtual address range; setting a limit below that range can SIGSEGV the trusted
        // helper before it execs the target. Wall time/output are enforced by the supervisor,
        // and Windows applies an exact Job memory limit to the target process tree.
    }

    /** Places the direct sandbox child in its own POSIX process group. */
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

    /** Kills every process still belonging to the sandbox child's POSIX process group. */
    public static void killProcessGroup(long pid) {
        signalProcessGroup(pid, 9);
    }

    /** Delivers a POSIX signal to the complete sandbox process group. */
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

    /**
     * Establishes the current helper as a process-group leader and atomically replaces it with the target command. This
     * avoids the racy parent-side setpgid-after-exec pattern on macOS.
     */
    public static void leadProcessGroupAndExec(List<String> arguments) {
        exec(arguments, true);
    }

    /** Atomically replaces the isolated helper with the target command. */
    public static void exec(List<String> arguments) {
        exec(arguments, false);
    }

    private static void exec(List<String> arguments, boolean createProcessGroup) {
        List<String> argv = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("exec argv must contain non-empty values");
        }
        if (SET_PGID == null || EXECVP == null) {
            throw new UnsupportedOperationException("process-group exec is unavailable");
        }
        try (Arena arena = Arena.ofConfined()) {
            if (createProcessGroup) {
                int grouped = (int) SET_PGID.invoke(0, 0);
                // sandbox-exec may already have made the helper a process-group leader. In
                // that case setpgid can report EPERM even though the required invariant is
                // already true; accept only the independently verified equivalent state.
                if (grouped != 0 && !isOwnProcessGroupLeader()) {
                    throw new IllegalStateException("self setpgid failed");
                }
            }
            MemorySegment vector = arena.allocate(ADDRESS.byteSize() * (argv.size() + 1L), ADDRESS.byteAlignment());
            for (int index = 0; index < argv.size(); index++) {
                vector.setAtIndex(ADDRESS, index, arena.allocateFrom(argv.get(index)));
            }
            vector.setAtIndex(ADDRESS, argv.size(), MemorySegment.NULL);
            int status = (int) EXECVP.invoke(vector.getAtIndex(ADDRESS, 0), vector);
            throw new IllegalStateException("execvp failed with status " + status);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot establish sandbox process group", failure);
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
}
