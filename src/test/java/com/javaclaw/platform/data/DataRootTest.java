package com.javaclaw.platform.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataRootTest {

    @TempDir
    Path tempDirectory;

    @Test
    void emptyDirectoryIsInitializedAndCanBePreparedAgain() throws IOException {
        DataRoot root = new DataRoot(tempDirectory.resolve("fresh"));

        assertEquals(root, root.prepare());
        assertEquals(DataRoot.FORMAT_VERSION,
                Files.readString(root.path().resolve(DataRoot.FORMAT_FILE)));
        assertEquals(root, root.prepare());
    }

    @Test
    void nonEmptyUnmarkedDirectoryIsRejectedWithoutChangingContents() throws IOException {
        Path oldData = tempDirectory.resolve("old-data");
        Files.createDirectories(oldData);
        Path existing = Files.writeString(oldData.resolve("javaclaw.mv.db"), "legacy");

        IOException failure = assertThrows(IOException.class,
                () -> new DataRoot(oldData).prepare());

        assertTrue(failure.getMessage().contains("拒绝使用不兼容的数据目录"));
        assertEquals("legacy", Files.readString(existing));
        assertTrue(Files.notExists(oldData.resolve(DataRoot.FORMAT_FILE)));
    }

    @Test
    void wrongFormatVersionIsRejected() throws IOException {
        Files.writeString(tempDirectory.resolve(DataRoot.FORMAT_FILE), "2");

        IOException failure = assertThrows(IOException.class,
                () -> new DataRoot(tempDirectory).prepare());

        assertTrue(failure.getMessage().contains("格式版本为 2"));
    }
}
