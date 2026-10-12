package com.javaclaw.platform.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicContentStoreTest {

    @Test
    void atomicallyCreatesAndReplacesUtf8Content(@TempDir Path directory) throws Exception {
        AtomicContentStore store = new AtomicContentStore();
        Path target = directory.resolve("nested/state.txt");

        store.writeString(target, "第一版");
        store.writeString(target, "第二版");

        assertEquals("第二版", store.readString(target));
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count(), "成功写入后不应遗留临时文件");
        }
        assertTrue(Files.isRegularFile(target));
    }
}
