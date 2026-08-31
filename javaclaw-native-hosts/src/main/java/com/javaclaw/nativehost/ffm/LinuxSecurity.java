package com.javaclaw.nativehost.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** Linux-only no-new-privileges and seccomp bindings for the isolated launcher helper. */
public final class LinuxSecurity {
    private static final int BPF_LD_W_ABS = 0x20;
    private static final int BPF_JMP_JEQ_K = 0x15;
    private static final int BPF_JMP_JGE_K = 0x35;
    private static final int BPF_RET_K = 0x06;
    private static final int SECCOMP_SET_MODE_FILTER = 1;
    private static final int SECCOMP_RET_KILL_PROCESS = 0x80000000;
    private static final int SECCOMP_RET_ERRNO_EPERM = 0x00050001;
    private static final int SECCOMP_RET_ALLOW = 0x7fff0000;
    private static final int PR_SET_NO_NEW_PRIVS = 38;

    private static final MemoryLayout FILTER = MemoryLayout.structLayout(
            JAVA_SHORT.withName("code"), JAVA_BYTE.withName("jt"),
            JAVA_BYTE.withName("jf"), JAVA_INT.withName("k"));
    private static final MemoryLayout PROGRAM = MemoryLayout.structLayout(
            JAVA_SHORT.withName("length"), MemoryLayout.paddingLayout(6), ADDRESS.withName("filters"));
    private static final MethodHandle SYSCALL = syscallHandle();

    private LinuxSecurity() {}

    /** 检查当前 Linux ABI 和 syscall 绑定是否可用；不安装 seccomp，也不代表内核必然允许安装过滤器。 */
    public static boolean isSupported() {
        return platform() != null && SYSCALL != null;
    }

    /**
     * Installs an architecture-checked denylist on top of bubblewrap namespaces. The filter blocks kernel-management
     * and cross-process primitives while retaining ordinary tool execution and subprocess creation inside the
     * namespace.
     */
    public static void installBaselineSeccomp() {
        Platform platform = platform();
        if (platform == null || SYSCALL == null) {
            throw new UnsupportedOperationException("Linux seccomp is unavailable");
        }
        int[] denied = platform.deniedSyscalls();
        int instructionCount = 6 + denied.length * 2 + 1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment filters = arena.allocate(FILTER.byteSize() * instructionCount, FILTER.byteAlignment());
            int cursor = 0;
            put(filters, cursor++, BPF_LD_W_ABS, 0, 0, 4); // seccomp_data.arch
            put(filters, cursor++, BPF_JMP_JEQ_K, 1, 0, platform.auditArchitecture());
            put(filters, cursor++, BPF_RET_K, 0, 0, SECCOMP_RET_KILL_PROCESS);
            put(filters, cursor++, BPF_LD_W_ABS, 0, 0, 0); // seccomp_data.nr
            // Reject x32 and malformed high syscall numbers before the ordinary denylist.
            put(filters, cursor++, BPF_JMP_JGE_K, 0, 1, 0x40000000);
            put(filters, cursor++, BPF_RET_K, 0, 0, SECCOMP_RET_ERRNO_EPERM);
            for (int syscall : denied) {
                put(filters, cursor++, BPF_JMP_JEQ_K, 0, 1, syscall);
                put(filters, cursor++, BPF_RET_K, 0, 0, SECCOMP_RET_ERRNO_EPERM);
            }
            put(filters, cursor, BPF_RET_K, 0, 0, SECCOMP_RET_ALLOW);

            MemorySegment program = arena.allocate(PROGRAM);
            program.set(JAVA_SHORT, 0, (short) instructionCount);
            program.set(ADDRESS, 8, filters);
            long noNewPrivileges = syscall(platform.prctlSyscall(), PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0, 0);
            if (noNewPrivileges != 0) {
                throw new IllegalStateException("PR_SET_NO_NEW_PRIVS failed: " + noNewPrivileges);
            }
            long installed = syscall(platform.seccompSyscall(), SECCOMP_SET_MODE_FILTER, 0, program.address(), 0, 0, 0);
            if (installed != 0) {
                throw new IllegalStateException("seccomp filter installation failed: " + installed);
            }
        }
    }

    private static void put(MemorySegment filters, int index, int code, int jt, int jf, int k) {
        long offset = FILTER.byteSize() * index;
        filters.set(JAVA_SHORT, offset, (short) code);
        filters.set(JAVA_BYTE, offset + 2, (byte) jt);
        filters.set(JAVA_BYTE, offset + 3, (byte) jf);
        filters.set(JAVA_INT, offset + 4, k);
    }

    private static long syscall(long number, long first, long second, long third, long fourth, long fifth, long sixth) {
        try {
            return (long) SYSCALL.invoke(number, first, second, third, fourth, fifth, sixth);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Linux syscall invocation failed", failure);
        }
    }

    private static MethodHandle syscallHandle() {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = linker.defaultLookup();
            return symbols.find("syscall")
                    .map(symbol -> linker.downcallHandle(
                            symbol,
                            FunctionDescriptor.of(
                                    JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
                                    JAVA_LONG),
                            Linker.Option.firstVariadicArg(1)))
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static Platform platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            return null;
        }
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return Platform.X86_64;
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return Platform.AARCH64;
        }
        return null;
    }

    private enum Platform {
        X86_64(0xc000003e, 157, 317, new int[] {
            101, 139, 155, 163, 165, 166, 167, 168, 169, 170, 171, 172, 173,
            175, 176, 179, 180, 212, 246, 248, 249, 250, 272, 298, 303, 304,
            308, 310, 311, 313, 320, 321, 323, 425, 426, 427, 428, 429
        }),
        AARCH64(0xc00000b7, 167, 277, new int[] {
            18, 39, 40, 41, 42, 43, 60, 89, 97, 104, 105, 106, 117, 142, 161, 162, 217, 218, 219, 224, 225, 241, 264,
            265, 268, 270, 271, 273, 280, 282, 294, 425, 426, 427, 428, 429
        });

        private final int auditArchitecture;
        private final int prctlSyscall;
        private final int seccompSyscall;
        private final int[] deniedSyscalls;

        Platform(int auditArchitecture, int prctlSyscall, int seccompSyscall, int[] deniedSyscalls) {
            this.auditArchitecture = auditArchitecture;
            this.prctlSyscall = prctlSyscall;
            this.seccompSyscall = seccompSyscall;
            this.deniedSyscalls = deniedSyscalls.clone();
            Arrays.sort(this.deniedSyscalls);
        }

        int auditArchitecture() {
            return auditArchitecture;
        }

        int prctlSyscall() {
            return prctlSyscall;
        }

        int seccompSyscall() {
            return seccompSyscall;
        }

        int[] deniedSyscalls() {
            return deniedSyscalls.clone();
        }
    }
}
