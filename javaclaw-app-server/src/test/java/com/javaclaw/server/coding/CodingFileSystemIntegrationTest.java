package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.DirectoryChange;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts.Encoding;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts.FileSystemResult;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingFileSystemIntegrationTest {
    @TempDir
    Path temporary;

    private CodingTestFixture fixture;

    @BeforeEach
    void initialize() throws Exception {
        fixture = new CodingTestFixture(temporary);
    }

    @AfterEach
    void close() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void 二进制创建分页复制移动删除保留原字节和幂等回执() throws Exception {
        byte[] bytes = {0, (byte) 255, (byte) 128, 10, 42};
        var write = new CodingFileSystemContracts.FileWrite(
                "a.bin", Base64.getEncoder().encodeToString(bytes), Encoding.BASE64, Optional.empty());
        var written = fixture.invoke("file_write", write, "write");
        assertTrue(written.success(), written.response().payload().json());
        assertEquals(written, fixture.invoke("file_write", write, "write"));
        assertArrayEquals(bytes, Files.readAllBytes(fixture.root.resolve("a.bin")));
        var read =
                fixture.invoke("file_read_binary", new CodingFileSystemContracts.FileReadBinary("a.bin", 1, 2), "read");
        var page = fixture.json.decode(read.response().payload(), CodingFileSystemContracts.FileReadBinaryResult.class);
        assertEquals(3, page.nextOffsetBytes());
        assertTrue(page.truncated());
        assertArrayEquals(
                new byte[] {(byte) 255, (byte) 128}, Base64.getDecoder().decode(page.contentBase64()));
        assertTrue(fixture.invoke(
                        "file_copy",
                        new CodingFileSystemContracts.FileTransfer("a.bin", "b.bin", page.sha256()),
                        "copy")
                .success());
        assertTrue(fixture.invoke(
                        "file_move",
                        new CodingFileSystemContracts.FileTransfer("b.bin", "c.bin", page.sha256()),
                        "move")
                .success());
        assertFalse(Files.exists(fixture.root.resolve("b.bin")));
        assertArrayEquals(bytes, Files.readAllBytes(fixture.root.resolve("c.bin")));
        assertTrue(fixture.invoke(
                        "file_delete", new CodingFileSystemContracts.FileDelete("c.bin", page.sha256()), "delete")
                .success());
        var stat = fixture.invoke("file_stat", new CodingFileSystemContracts.FileStat("c.bin"), "stat");
        assertTrue(fixture.json
                .decode(stat.response().payload(), CodingFileSystemContracts.FileStatResult.class)
                .entry()
                .isEmpty());
        var result = fixture.json.decode(written.response().payload(), FileSystemResult.class);
        assertEquals(
                CodingFileSystemContracts.ContentKind.BINARY,
                result.changes().getFirst().kind());
        assertTrue(result.changes().getFirst().textDiff().isEmpty());
        assertEquals(Optional.of(5L), result.changes().getFirst().afterSizeBytes());
    }

    @Test
    void 文本超过Diff预算仍准确声明为文本而非二进制() throws Exception {
        var written = fixture.invoke(
                "file_write",
                new CodingFileSystemContracts.FileWrite(
                        "large.txt", "正文".repeat(20_000), Encoding.UTF8, Optional.empty()),
                "large-text");
        assertTrue(written.success(), written.response().payload().json());
        var result = fixture.json.decode(written.response().payload(), FileSystemResult.class);
        assertEquals(
                CodingFileSystemContracts.ContentKind.TEXT,
                result.changes().getFirst().kind());
        assertTrue(result.changes().getFirst().textDiff().isEmpty());
    }

    @Test
    void 冲突和缺失父目录不覆盖或建立任何目标() throws Exception {
        Files.write(fixture.root.resolve("user.bin"), new byte[] {0, 1});
        var update =
                new CodingFileSystemContracts.FileWrite("user.bin", "new", Encoding.UTF8, Optional.of("0".repeat(64)));
        assertFalse(fixture.invoke("file_write", update, "conflict").success());
        var create = new CodingFileSystemContracts.FileWrite("missing/file", "new", Encoding.UTF8, Optional.empty());
        assertFalse(fixture.invoke("file_write", create, "parent").success());
        assertFalse(Files.exists(fixture.root.resolve("missing")));
        assertArrayEquals(new byte[] {0, 1}, Files.readAllBytes(fixture.root.resolve("user.bin")));
    }

    @Test
    void 新目录和旧补丁自动父目录均产生独立Core目录事实() throws Exception {
        var directories = fixture.invoke("file_mkdir", new CodingFileSystemContracts.FileMkdir("a/b", true), "mkdir");
        assertTrue(directories.success(), directories.response().payload().json());
        assertEquals(
                2,
                directories.facts().stream()
                        .filter(fact -> fact.payload() instanceof DirectoryChange)
                        .count());
        assertEquals(
                directories,
                fixture.invoke("file_mkdir", new CodingFileSystemContracts.FileMkdir("a/b", true), "mkdir"));
        var again = fixture.invoke("file_mkdir", new CodingFileSystemContracts.FileMkdir("a/b", false), "again");
        assertTrue(again.success());
        assertTrue(again.facts().isEmpty());
        var nonempty = fixture.invoke("file_rmdir", new CodingFileSystemContracts.FileRmdir("a"), "nonempty");
        assertFalse(nonempty.success());
        assertTrue(nonempty.facts().isEmpty());
        var removed = fixture.invoke("file_rmdir", new CodingFileSystemContracts.FileRmdir("a/b"), "rmdir");
        assertTrue(removed.success(), removed.response().payload().json());
        assertTrue(removed.facts().getFirst().payload() instanceof DirectoryChange);
        var patch = new CodingContracts.ApplyPatch(List.of(new CodingContracts.FileEdit(
                "old/nested/file.txt", Optional.empty(), Optional.of("正文"), Optional.empty())));
        var result = fixture.invoke("file_apply_patch", patch, "old-patch");
        assertTrue(result.success());
        assertEquals(
                2,
                result.facts().stream()
                        .filter(fact -> fact.payload() instanceof DirectoryChange)
                        .count());
    }
}
