package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** 同句柄取得的普通对象属性：三个时间为非空 FileTime，isDirectory 表示目录，size 是非负字节数， 非空 fileKey 由卷序列号和文件标识组成；不接受 reparse 或设备对象。 */
record WindowsWorkspaceAttributes(
        FileTime lastModifiedTime,
        FileTime lastAccessTime,
        FileTime creationTime,
        boolean isDirectory,
        long size,
        Object fileKey)
        implements BasicFileAttributes {
    static WindowsWorkspaceAttributes read(WindowsFileHandle handle) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment information = WindowsFileHandle.information(handle.address(), arena);
            int attributes = information.get(JAVA_INT, 0);
            if ((attributes & 0x440) != 0) {
                throw new IOException("Windows workspace object became a reparse point or device");
            }
            long size = unsignedPair(information, 36, 32);
            if (size < 0) {
                throw new IOException("Windows file size exceeds supported range");
            }
            return new WindowsWorkspaceAttributes(
                    time(information, 20),
                    time(information, 12),
                    time(information, 4),
                    (attributes & 0x10) != 0,
                    size,
                    new FileKey(information.get(JAVA_INT, 28), unsignedPair(information, 48, 44)));
        }
    }

    private static FileTime time(MemorySegment information, long offset) {
        long ticks = unsignedPair(information, offset, offset + 4);
        return FileTime.from(ticks / 10 - 11_644_473_600_000_000L, TimeUnit.MICROSECONDS);
    }

    private static long unsignedPair(MemorySegment information, long low, long high) {
        return ((long) information.get(JAVA_INT, high) << 32) | Integer.toUnsignedLong(information.get(JAVA_INT, low));
    }

    @Override
    public boolean isRegularFile() {
        return !isDirectory;
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    private record FileKey(int volumeSerialNumber, long fileIndex) {}
}
