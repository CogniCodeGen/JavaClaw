package com.javaclaw.nativehost.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Linux 命令子进程的可继承 seccomp 网络与调试边界，仅在固定 exec Worker 内安装。
 *
 * <p>network namespace 不隔离 bind mount 中的 pathname Unix socket。命令因而禁止创建具名 AF_UNIX socket，仅允许匿名 stream socketpair 供 Node
 * 等运行库内部 IPC；固定代理 relay 位于父进程，保留其 IPC。 同时阻止 ptrace、process_vm 与 pidfd_getfd，防止命令取得 relay 的宿主 IPC 能力。
 */
public final class LinuxSocketPolicy {
    private static final int ALLOW = 0x7fff0000;
    private static final int DENY = 0x00050001;
    private static final int KILL_PROCESS = 0x80000000;
    private static final int LOAD = 0x20;
    private static final int EQUAL = 0x15;
    private static final int RETURN = 0x06;
    private static final int AND = 0x54;

    private LinuxSocketPolicy() {}

    /**
     * 在当前 exec 线程上设置 no_new_privs 和 seccomp；随后必须立即 exec 固定参数命令。
     *
     * <p>过滤器由内核继承至所有后代，不支持关闭；任一未知架构或系统调用失败均拒绝启动。
     */
    public static void install() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            throw new UnsupportedOperationException("Linux seccomp policy is unavailable on this platform");
        }
        List<Instruction> instructions = instructions(System.getProperty("os.arch", ""));
        var linker = Linker.nativeLinker();
        var symbol = linker.defaultLookup()
                .find("prctl")
                .orElseThrow(() -> new UnsupportedOperationException("Linux prctl is unavailable"));
        var prctl = linker.downcallHandle(
                symbol,
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
                Linker.Option.firstVariadicArg(1));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment filter = arena.allocate(instructions.size() * 8L, 8);
            for (int index = 0; index < instructions.size(); index++) {
                instructions.get(index).write(filter.asSlice(index * 8L, 8));
            }
            MemorySegment program = arena.allocate(16, 8);
            program.set(JAVA_SHORT, 0, (short) instructions.size());
            program.set(JAVA_LONG, 8, filter.address());
            int noPrivileges = (int) prctl.invoke(38, 1L, 0L, 0L, 0L);
            if (noPrivileges != 0) {
                throw new IllegalStateException("PR_SET_NO_NEW_PRIVS failed");
            }
            int installed = (int) prctl.invoke(22, 2L, program.address(), 0L, 0L);
            if (installed != 0) {
                throw new IllegalStateException("PR_SET_SECCOMP failed");
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot install Linux socket policy", failure);
        }
    }

    static List<Instruction> instructions(String architecture) {
        boolean arm = architecture.equals("aarch64") || architecture.equals("arm64");
        if (!arm && !architecture.equals("amd64") && !architecture.equals("x86_64")) {
            throw new UnsupportedOperationException("unsupported Linux seccomp architecture: " + architecture);
        }
        Program program = new Program();
        program.load(4);
        program.jump(arm ? 0xc00000b7 : 0xc000003e, "architecture-ok", "kill");
        program.label("architecture-ok");
        program.load(0);
        if (!arm) {
            program.add(new Instruction(0x45, 0, 1, 0x40000000));
            program.result(DENY);
        }
        // io_uring 能以异步 socket 操作绕过逐 syscall 过滤，因此与跨进程句柄取得一起禁止。
        int[] denied =
                arm ? new int[] {117, 270, 271, 425, 426, 427, 438} : new int[] {101, 310, 311, 425, 426, 427, 438};
        for (int syscall : denied) {
            program.add(new Instruction(EQUAL, 0, 1, syscall));
            program.result(DENY);
        }
        program.jump(arm ? 198 : 41, "socket", "check-pair");
        program.label("check-pair");
        program.jump(arm ? 199 : 53, "pair", "allow");
        program.label("socket");
        program.load(16);
        program.jump(2, "allow", "check-ipv6");
        program.label("check-ipv6");
        program.jump(10, "allow", "deny");
        program.label("pair");
        program.load(16);
        program.jump(1, "pair-type", "deny");
        program.label("pair-type");
        program.load(24);
        program.add(new Instruction(AND, 0, 0, 0xf));
        program.jump(1, "allow", "deny");
        program.label("kill");
        program.result(KILL_PROCESS);
        program.label("deny");
        program.result(DENY);
        program.label("allow");
        program.result(ALLOW);
        return program.finish();
    }

    record Instruction(int code, int yes, int no, int value) {
        void write(MemorySegment memory) {
            memory.set(JAVA_SHORT, 0, (short) code);
            memory.set(JAVA_BYTE, 2, (byte) yes);
            memory.set(JAVA_BYTE, 3, (byte) no);
            memory.set(JAVA_INT, 4, value);
        }
    }

    private static final class Program {
        private final List<Instruction> instructions = new ArrayList<>();
        private final Map<String, Integer> labels = new HashMap<>();
        private final List<Jump> jumps = new ArrayList<>();

        void load(int offset) {
            add(new Instruction(LOAD, 0, 0, offset));
        }

        void result(int action) {
            add(new Instruction(RETURN, 0, 0, action));
        }

        void add(Instruction instruction) {
            instructions.add(instruction);
        }

        void label(String name) {
            labels.put(name, instructions.size());
        }

        void jump(int value, String yes, String no) {
            jumps.add(new Jump(instructions.size(), yes, no));
            add(new Instruction(EQUAL, 0, 0, value));
        }

        List<Instruction> finish() {
            for (Jump jump : jumps) {
                Instruction original = instructions.get(jump.index());
                int yes = labels.get(jump.yes()) - jump.index() - 1;
                int no = labels.get(jump.no()) - jump.index() - 1;
                if (yes < 0 || no < 0 || yes > 255 || no > 255) {
                    throw new IllegalStateException("invalid fixed seccomp branch");
                }
                instructions.set(jump.index(), new Instruction(original.code(), yes, no, original.value()));
            }
            return List.copyOf(instructions);
        }
    }

    private record Jump(int index, String yes, String no) {}
}
