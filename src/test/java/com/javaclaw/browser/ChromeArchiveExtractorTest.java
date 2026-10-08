package com.javaclaw.browser;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChromeArchiveExtractorTest {

    @TempDir
    Path temporary;

    @Test
    void 普通文件保留内容和执行权限() throws Exception {
        Path stage = stage();
        ChromeArchiveExtractor.extract(archive(
                entry("chrome/", UnixStat.DIR_FLAG | 0755, ""),
                entry("chrome/chrome", UnixStat.FILE_FLAG | 0755, "browser")), stage);

        Path executable = stage.resolve("chrome/chrome");
        assertEquals("browser", Files.readString(executable));
        if (Files.getFileAttributeView(stage, PosixFileAttributeView.class) != null) {
            assertTrue(Files.getPosixFilePermissions(executable)
                    .contains(PosixFilePermission.OWNER_EXECUTE));
            assertFalse(Files.getPosixFilePermissions(executable)
                    .contains(PosixFilePermission.OTHERS_WRITE));
        }
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 应用包内部相对符号链接保留且可以读取() throws Exception {
        Path stage = stage();
        ChromeArchiveExtractor.extract(archive(
                entry("app/Versions/Current", UnixStat.LINK_FLAG | 0777, "A"),
                entry("app/Resources", UnixStat.LINK_FLAG | 0777, "Versions/Current/Resources"),
                entry("app/Versions/A/Resources/data", UnixStat.FILE_FLAG | 0644, "content")), stage);

        assertTrue(Files.isSymbolicLink(stage.resolve("app/Versions/Current")));
        assertEquals(Path.of("A"), Files.readSymbolicLink(stage.resolve("app/Versions/Current")));
        assertEquals("content", Files.readString(stage.resolve("app/Resources/data")));
    }

    @Test
    void 拒绝目录逃逸和绝对路径及反斜杠路径() throws Exception {
        for (String name : new String[]{"../outside", "/outside", "C:/outside", "dir/..\\outside"}) {
            IOException failure = assertThrows(IOException.class, () -> ChromeArchiveExtractor.extract(
                    archive(entry(name, UnixStat.FILE_FLAG | 0644, "unsafe")), stage()));
            assertTrue(failure.getMessage().contains("Chrome"));
        }
        assertFalse(Files.exists(temporary.resolve("outside")));
    }

    @Test
    void 拒绝符号链接指向目录外() throws Exception {
        IOException failure = assertThrows(IOException.class, () -> ChromeArchiveExtractor.extract(
                archive(entry("link", UnixStat.LINK_FLAG | 0777, "../outside")), stage()));

        assertTrue(failure.getMessage().contains("符号链接越界"));
        assertFalse(Files.exists(temporary.resolve("outside")));
    }

    @Test
    void 拒绝后续文件穿透归档中的符号链接() throws Exception {
        Path stage = stage();
        IOException failure = assertThrows(IOException.class, () -> ChromeArchiveExtractor.extract(
                archive(entry("link", UnixStat.LINK_FLAG | 0777, "directory"),
                        entry("link/data", UnixStat.FILE_FLAG | 0644, "unsafe")), stage));

        assertTrue(failure.getMessage().contains("穿透符号链接"));
        assertFalse(Files.exists(stage.resolve("link")));
    }

    @Test
    void 拒绝通过内部链接和上级路径组合逃逸() throws Exception {
        IOException failure = assertThrows(IOException.class, () -> ChromeArchiveExtractor.extract(
                archive(entry("inside", UnixStat.LINK_FLAG | 0777, "."),
                        entry("link", UnixStat.LINK_FLAG | 0777, "inside/../outside")), stage()));

        assertTrue(failure.getMessage().contains("符号链接越界"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 大小写不敏感文件系统也拒绝通过链接别名逃逸() throws Exception {
        Path probe = temporary.resolve("case-sensitive-probe");
        Files.writeString(probe, "probe");
        assumeTrue(Files.exists(temporary.resolve("CASE-SENSITIVE-PROBE")),
                "此测试需要大小写不敏感的文件系统");

        IOException failure = assertThrows(IOException.class, () -> ChromeArchiveExtractor.extract(
                archive(entry("inside", UnixStat.LINK_FLAG | 0777, "."),
                        entry("link", UnixStat.LINK_FLAG | 0777, "INSIDE/../outside")), stage()));

        assertTrue(failure.getMessage().contains("符号链接越界"));
        assertFalse(Files.exists(temporary.resolve("outside")));
    }

    @Test
    void 拒绝不支持的特殊条目() throws Exception {
        IOException failure = assertThrows(IOException.class, () -> ChromeArchiveExtractor.extract(
                archive(entry("device", 0020000 | 0600, "")), stage()));

        assertTrue(failure.getMessage().contains("特殊条目"));
    }

    @Test
    void 拒绝截断的归档() throws Exception {
        byte[] complete = zip(entry("chrome", UnixStat.FILE_FLAG | 0755, "browser"));
        Path truncated = Files.createTempFile(temporary, "truncated-", ".zip");
        Files.write(truncated, Arrays.copyOf(complete, complete.length - 22));

        assertThrows(IOException.class, () -> ChromeArchiveExtractor.extract(truncated, stage()));
    }

    @Test
    void 声明长度与实际内容不符时拒绝归档() throws Exception {
        byte[] bytes = zip(entry("chrome", UnixStat.FILE_FLAG | 0755, "browser"));
        for (int offset = 0; offset < bytes.length - 28; offset++) {
            if (bytes[offset] == 0x50 && bytes[offset + 1] == 0x4b
                    && bytes[offset + 2] == 0x01 && bytes[offset + 3] == 0x02) {
                bytes[offset + 24] = 8;
                break;
            }
        }
        Path corrupt = Files.createTempFile(temporary, "corrupt-", ".zip");
        Files.write(corrupt, bytes);

        IOException failure = assertThrows(IOException.class,
                () -> ChromeArchiveExtractor.extract(corrupt, stage()));
        assertTrue(failure.getMessage().contains("长度或校验和不符"));
    }

    @Test
    void 解压响应线程中断且保留中断标志() throws Exception {
        Path archive = archive(entry("chrome", UnixStat.FILE_FLAG | 0755, "browser"));
        Path stage = stage();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> ChromeArchiveExtractor.extract(archive, stage));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private Path stage() throws IOException {
        return Files.createTempDirectory(temporary, "stage-");
    }

    private Path archive(Entry... entries) throws IOException {
        Path archive = Files.createTempFile(temporary, "chrome-", ".zip");
        Files.write(archive, zip(entries));
        return archive;
    }

    private static byte[] zip(Entry... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(bytes)) {
            for (Entry source : entries) {
                ZipArchiveEntry entry = new ZipArchiveEntry(source.name());
                entry.setUnixMode(source.mode());
                output.putArchiveEntry(entry);
                output.write(source.content().getBytes(StandardCharsets.UTF_8));
                output.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Entry entry(String name, int mode, String content) {
        return new Entry(name, mode, content);
    }

    private record Entry(String name, int mode, String content) {
    }
}
