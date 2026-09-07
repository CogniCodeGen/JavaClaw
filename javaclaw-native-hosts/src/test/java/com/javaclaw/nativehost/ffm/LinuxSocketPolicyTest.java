package com.javaclaw.nativehost.ffm;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinuxSocketPolicyTest {
    @Test
    void bothArchitecturesDenyHostIpcDebugHandlesAndAsyncSocketBypasses() {
        for (String architecture : List.of("amd64", "aarch64")) {
            boolean arm = architecture.equals("aarch64");
            int audit = arm ? 0xc00000b7 : 0xc000003e;
            var program = LinuxSocketPolicy.instructions(architecture);
            int socket = arm ? 198 : 41;
            int pair = arm ? 199 : 53;
            assertEquals(0x7fff0000, evaluate(program, audit, socket, 2, 1));
            assertEquals(0x7fff0000, evaluate(program, audit, socket, 10, 2));
            for (int domain : new int[] {1, 16, 17}) {
                assertEquals(0x00050001, evaluate(program, audit, socket, domain, 1));
            }
            assertEquals(0x7fff0000, evaluate(program, audit, pair, 1, 1 | 0x80000));
            assertEquals(0x00050001, evaluate(program, audit, pair, 1, 2));
            assertEquals(0x00050001, evaluate(program, audit, pair, 2, 1));
            int[] denied =
                    arm ? new int[] {117, 270, 271, 425, 426, 427, 438} : new int[] {101, 310, 311, 425, 426, 427, 438};
            for (int syscall : denied) {
                assertEquals(0x00050001, evaluate(program, audit, syscall, 0, 0));
            }
            assertEquals(0x7fff0000, evaluate(program, audit, arm ? 64 : 1, 0, 0));
            assertEquals(0x80000000, evaluate(program, 0x40000003, socket, 2, 1));
        }
        assertEquals(0x00050001, evaluate(LinuxSocketPolicy.instructions("amd64"), 0xc000003e, 0x40000029, 2, 1));
        assertThrows(UnsupportedOperationException.class, () -> LinuxSocketPolicy.instructions("riscv64"));
    }

    private static int evaluate(List<LinuxSocketPolicy.Instruction> program, int arch, int call, int domain, int type) {
        int accumulator = 0;
        int index = 0;
        while (index < program.size()) {
            var instruction = program.get(index++);
            switch (instruction.code()) {
                case 0x20 -> accumulator = load(instruction.value(), arch, call, domain, type);
                case 0x15 -> index += accumulator == instruction.value() ? instruction.yes() : instruction.no();
                case 0x45 -> index += (accumulator & instruction.value()) != 0 ? instruction.yes() : instruction.no();
                case 0x54 -> accumulator &= instruction.value();
                case 0x06 -> {
                    return instruction.value();
                }
                default -> throw new AssertionError("unexpected seccomp opcode");
            }
        }
        throw new AssertionError("seccomp program did not terminate");
    }

    private static int load(int offset, int arch, int call, int domain, int type) {
        return switch (offset) {
            case 0 -> call;
            case 4 -> arch;
            case 16 -> domain;
            case 24 -> type;
            default -> throw new AssertionError("unexpected seccomp offset");
        };
    }
}
