package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** 对固定目录对象执行有界 NT 枚举；重复句柄共享枚举游标，必须在共同锁内重置和读取。 */
final class WindowsWorkspaceListing {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int NO_MORE_FILES = 0x80000006;

    private WindowsWorkspaceListing() {}

    static List<String> names(WindowsFileHandle directory, int maximum) throws IOException {
        synchronized (directory.listingLock()) {
            try (WindowsFileHandle duplicate = directory.reopenDirectory(WindowsFileHandle.ATTRIBUTES | 1 | 0x20);
                    Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BUFFER_BYTES, 8);
                MemorySegment status = arena.allocate(16, 8);
                ArrayList<String> result = new ArrayList<>();
                for (int iteration = 0; iteration <= maximum + 2; iteration++) {
                    int code = WindowsFileNative.call(
                                    WindowsFileNative.backend().queryDirectory,
                                    duplicate.address(),
                                    MemorySegment.NULL,
                                    MemorySegment.NULL,
                                    MemorySegment.NULL,
                                    status,
                                    buffer,
                                    BUFFER_BYTES,
                                    12,
                                    (byte) 0,
                                    MemorySegment.NULL,
                                    (byte) (iteration == 0 ? 1 : 0))
                            .number();
                    if (code == NO_MORE_FILES) {
                        return List.copyOf(result);
                    }
                    WindowsFileNative.ntCheck("NtQueryDirectoryFile", "directory", code);
                    append(buffer, status.get(JAVA_LONG, 8), result, maximum);
                }
                throw new IOException("Windows directory enumeration exceeded bounded progress");
            }
        }
    }

    static void append(MemorySegment buffer, long used, List<String> result, int maximum) throws IOException {
        if (used < 12 || used > buffer.byteSize()) {
            throw new IOException("Windows directory returned an invalid buffer length");
        }
        long offset = 0;
        while (offset < used) {
            long next = Integer.toUnsignedLong(buffer.get(JAVA_INT, offset));
            long bytes = Integer.toUnsignedLong(buffer.get(JAVA_INT, offset + 8));
            if ((bytes & 1) != 0 || bytes > 510 || bytes == 0 || offset + 12 + bytes > used) {
                throw new IOException("Windows directory returned an invalid name");
            }
            String name = new String(
                    buffer.asSlice(offset + 12, bytes).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                    StandardCharsets.UTF_16LE);
            addName(result, name, maximum);
            if (next == 0) {
                return;
            }
            if (next < 12 + bytes || (next & 3) != 0 || offset + next > used - 12) {
                throw new IOException("Windows directory returned an invalid next-entry offset");
            }
            offset += next;
        }
    }

    private static void addName(List<String> result, String name, int maximum) throws IOException {
        if (name.equals(".") || name.equals("..")) {
            return;
        }
        if (result.size() >= maximum) {
            throw new IOException("Windows directory exceeds entry limit");
        }
        try {
            WindowsFileHandle.requireLeaf(name);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Windows directory contains an unsupported entry name", invalid);
        }
        result.add(name);
    }
}
