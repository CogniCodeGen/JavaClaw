package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** 普通文件替换前复制 DACL；只操作已固定的源/目标对象，不按文件名重新读取安全描述符。 */
final class WindowsWorkspaceAcl {
    private WindowsWorkspaceAcl() {}

    static void copy(WindowsFileHandle source, WindowsFileHandle target) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dacl = arena.allocate(ADDRESS);
            MemorySegment descriptor = arena.allocate(ADDRESS);
            int status = backend.invoke(
                            backend.getSecurityInfo,
                            source.address(),
                            1,
                            4,
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            dacl,
                            MemorySegment.NULL,
                            descriptor)
                    .number();
            if (status != 0) {
                throw WindowsSandboxNative.status("GetSecurityInfo", status);
            }
            MemorySegment original = descriptor.get(ADDRESS, 0);
            try {
                int flags = flags(original, arena);
                status = backend.invoke(
                                backend.setSecurityInfo,
                                target.address(),
                                1,
                                flags,
                                MemorySegment.NULL,
                                MemorySegment.NULL,
                                dacl.get(ADDRESS, 0),
                                MemorySegment.NULL)
                        .number();
                if (status != 0) {
                    throw WindowsSandboxNative.status("SetSecurityInfo", status);
                }
            } finally {
                WindowsSandboxNative.localFree(original);
            }
        }
    }

    private static int flags(MemorySegment descriptor, Arena arena) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment control = arena.allocate(JAVA_SHORT);
        MemorySegment revision = arena.allocate(JAVA_INT);
        var result = backend.invoke(backend.getSecurityDescriptorControl, descriptor, control, revision);
        if (result.number() == 0) {
            throw WindowsSandboxNative.error("GetSecurityDescriptorControl", result.error());
        }
        boolean protectedDacl = (Short.toUnsignedInt(control.get(JAVA_SHORT, 0)) & 0x1000) != 0;
        return 4 | (protectedDacl ? 0x80000000 : 0x20000000);
    }
}
