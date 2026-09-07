package com.javaclaw.nativehost.coding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceInventoryScannerTest {
    @TempDir
    Path temporary;

    @Test
    void 摘要完整文件且实际排除目录不会读入扫描预算() throws Exception {
        Files.writeString(temporary.resolve("source.java"), "你好");
        Files.createDirectories(temporary.resolve("nested/node_modules"));
        Files.writeString(temporary.resolve("nested/node_modules/secret"), "x".repeat(100));
        Files.createDirectories(temporary.resolve(".git"));
        Files.writeString(temporary.resolve(".git/config"), "x".repeat(100));
        var result = scan(100, 6, List.of("node_modules"));
        assertFalse(result.truncated());
        assertEquals(6, result.scannedBytes());
        assertEquals(List.of(".git", "nested/node_modules"), result.excludedDirectories());
        assertEquals(
                List.of(new WorkspaceInventory.Entry(
                        "source.java", 6, WorkspaceFileAccess.hash("你好".getBytes(StandardCharsets.UTF_8)))),
                result.entries());
    }

    @Test
    void 超预算文件不生成部分摘要且目录本身计入访问上限() throws Exception {
        Files.writeString(temporary.resolve("large"), "12345");
        assertTrue(scan(100, 4, List.of()).truncated());
        assertTrue(scan(100, 4, List.of()).entries().isEmpty());
        assertEquals(0, scan(100, 4, List.of()).scannedBytes());
        assertTrue(scan(1, 100, List.of()).truncated());
        assertTrue(scan(1, 100, List.of()).entries().isEmpty());
        assertFalse(scan(2, 5, List.of()).truncated());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void 链接不被跟随且缺失覆盖范围显式标记() throws Exception {
        Path outside = Files.createTempFile("inventory-outside", ".txt");
        try {
            Files.writeString(outside, "outside");
            Files.createSymbolicLink(temporary.resolve("link"), outside);
            var result = scan(100, 100, List.of());
            assertTrue(result.entries().isEmpty());
            assertEquals(0, result.scannedBytes());
            assertTrue(result.truncated());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void 超深目录不递归且二进制协议保留覆盖范围() throws Exception {
        Path nested = temporary;
        for (int index = 0; index < 34; index++) {
            nested = Files.createDirectory(nested.resolve("d"));
        }
        Files.writeString(nested.resolve("unseen"), "hidden");
        var result = scan(100, 100, List.of());
        assertTrue(result.truncated());
        assertTrue(result.entries().isEmpty());
        var bytes = new ByteArrayOutputStream();
        WorkspaceFileProtocol.writeInventory(new DataOutputStream(bytes), result);
        assertEquals(
                result,
                WorkspaceFileProtocol.readInventory(
                        new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        assertThrows(IllegalArgumentException.class, () -> scan(100, 100, List.of("../secret")));
        assertThrows(IllegalArgumentException.class, () -> scan(100, 100, List.of("a/b")));
    }

    private WorkspaceInventory scan(int entries, int bytes, List<String> excluded) throws Exception {
        try (var tree = new WorkspaceFileTree(temporary.toRealPath())) {
            return WorkspaceInventoryScanner.scan(tree, "", entries, bytes, excluded);
        }
    }
}
