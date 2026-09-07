package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowsWorkspaceListingTest {
    @Test
    void nativeEnumerationPreservesUnicodeNamesAndRejectsLimitTruncation() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(64, 8);
            entry(buffer, 0, 16, ".");
            entry(buffer, 16, 0, "文件.txt");
            List<String> names = new ArrayList<>();
            WindowsWorkspaceListing.append(buffer, 40, names, 1);
            assertEquals(List.of("文件.txt"), names);
            assertThrows(IOException.class, () -> WindowsWorkspaceListing.append(buffer, 40, names, 1));
        }
    }

    @Test
    void malformedKernelBufferCannotReadOutsideItsReportedRange() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(64, 8);
            entry(buffer, 0, 64, "first");
            assertThrows(IOException.class, () -> WindowsWorkspaceListing.append(buffer, 24, new ArrayList<>(), 10));
            buffer.set(JAVA_INT, 0, 0);
            buffer.set(JAVA_INT, 8, Integer.MAX_VALUE);
            assertThrows(IOException.class, () -> WindowsWorkspaceListing.append(buffer, 24, new ArrayList<>(), 10));
            buffer.set(JAVA_INT, 8, 3);
            assertThrows(IOException.class, () -> WindowsWorkspaceListing.append(buffer, 24, new ArrayList<>(), 10));
            assertThrows(IOException.class, () -> WindowsWorkspaceListing.append(buffer, 65, new ArrayList<>(), 10));
        }
    }

    @Test
    void relativeNamesCannotCarryTraversalStreamsOrWin32Aliases() {
        for (String invalid : List.of("..", ".", "", "a/b", "a\\b", "file:stream", "name.", "name ", "a\0b")) {
            assertThrows(IllegalArgumentException.class, () -> WindowsFileHandle.requireLeaf(invalid));
        }
        WindowsFileHandle.requireLeaf("源文件.java");
        assertInstanceOf(NoSuchFileException.class, WindowsFileNative.fileError("open", "missing", 2));
        assertInstanceOf(FileAlreadyExistsException.class, WindowsFileNative.fileError("create", "present", 183));
        assertInstanceOf(AccessDeniedException.class, WindowsFileNative.fileError("open", "protected", 5));
    }

    private static void entry(MemorySegment buffer, long offset, int next, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_16LE);
        buffer.set(JAVA_INT, offset, next);
        buffer.set(JAVA_INT, offset + 8, bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, buffer, offset + 12, bytes.length);
    }
}
