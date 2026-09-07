package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** 将本地盘符转换成真正 NT device 路径，避免 OBJ_DONT_REPARSE 被 DOS namespace 链接阻断。 */
final class WindowsWorkspaceRoot {
    private WindowsWorkspaceRoot() {}

    static String ntPath(Path root) throws IOException {
        if (!root.isAbsolute() || !root.normalize().equals(root)) {
            throw new IOException("Windows Workspace root must be an absolute normalized path");
        }
        String value = root.toString();
        if (value.length() < 3
                || !Character.isLetter(value.charAt(0))
                || value.charAt(1) != ':'
                || value.charAt(2) != '\\') {
            throw new IOException("Windows Workspace requires a local drive; UNC roots are unsupported");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment target = arena.allocate(65_536, 2);
            var result = WindowsFileNative.call(
                    WindowsFileNative.backend().queryDosDevice,
                    WindowsSandboxNative.wide(arena, value.substring(0, 2)),
                    target,
                    32_768);
            if (result.number() == 0) {
                throw WindowsSandboxNative.error("QueryDosDeviceW", result.error());
            }
            String device = target.getString(0, StandardCharsets.UTF_16LE);
            if (!device.startsWith("\\Device\\") || device.indexOf('\\', 8) >= 0) {
                throw new IOException(
                        "Windows Workspace requires a direct local volume; device aliases are unsupported");
            }
            return device + value.substring(2);
        }
    }
}
