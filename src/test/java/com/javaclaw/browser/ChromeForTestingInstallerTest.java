package com.javaclaw.browser;

import com.javaclaw.platform.data.ApplicationHome;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class ChromeForTestingInstallerTest {

    @TempDir
    Path temporary;

    private HttpServer server;
    private URI base;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicBoolean brokenArchive = new AtomicBoolean();
    private final List<String> requestedPaths = new ArrayList<>();

    @BeforeEach
    void startDownloadService() throws IOException {
        byte[] archive = archive();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            synchronized (requestedPaths) { requestedPaths.add(path); }
            byte[] body = path.equals("/version") ? "155.0.8059.39\n".getBytes(StandardCharsets.UTF_8)
                    : brokenArchive.get() ? new byte[] {1, 2, 3} : archive;
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopDownloadService() {
        server.stop(0);
    }

    @Test
    void 仅下载当前平台的一份Chrome且后续离线复用() throws Exception {
        ApplicationHome home = ApplicationHome.at(temporary).prepare();
        var installer = installer();
        Path executable = installer.ensureInstalled(home);
        assertTrue(Files.isExecutable(executable));
        assertEquals(List.of("/version", "/155.0.8059.39/linux64/chrome-linux64.zip"), requestedPaths);
        server.stop(0);
        assertEquals(executable, installer.ensureInstalled(home));
        assertEquals(2, requests.get());
        assertTemporaryDirectoryEmpty(home);
    }

    @Test
    void 失败安装不留下有效缓存且下次可重试() throws Exception {
        ApplicationHome home = ApplicationHome.at(temporary).prepare();
        brokenArchive.set(true);
        assertThrows(IOException.class, () -> installer().ensureInstalled(home));
        assertFalse(Files.exists(home.cacheDirectory().resolve("google-chrome/linux64/active-version")));
        assertTemporaryDirectoryEmpty(home);
        brokenArchive.set(false);
        assertTrue(Files.isExecutable(installer().ensureInstalled(home)));
        assertEquals(4, requests.get());
    }

    @Test
    void 损坏的版本标记可恢复且完整缓存不重复下载() throws Exception {
        ApplicationHome home = ApplicationHome.at(temporary).prepare();
        Path executable = installer().ensureInstalled(home);
        Path active = home.cacheDirectory().resolve("google-chrome/linux64/active-version");
        Files.writeString(active, "broken-version");
        assertEquals(executable, installer().ensureInstalled(home));
        assertEquals(3, requests.get(), "仅重新查询版本，完整 Chrome 不应重复下载");
        assertEquals("155.0.8059.39", Files.readString(active));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 不允许缓存目录通过符号链接越界() throws Exception {
        ApplicationHome home = ApplicationHome.at(Files.createDirectory(temporary.resolve("home"))).prepare();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(home.cacheDirectory().resolve("google-chrome"), outside);
        assertThrows(IOException.class, () -> installer().ensureInstalled(home));
        assertEquals(0, requests.get());
    }

    @Test
    void 根据操作系统和架构选择官方下载平台() {
        assertEquals("mac-arm64", ChromeForTestingInstaller.platform("Mac OS X", "aarch64"));
        assertEquals("mac-x64", ChromeForTestingInstaller.platform("Mac OS X", "x86_64"));
        assertEquals("linux64", ChromeForTestingInstaller.platform("Linux", "amd64"));
        assertEquals("linux-arm64", ChromeForTestingInstaller.platform("Linux", "aarch64"));
        assertEquals("win64", ChromeForTestingInstaller.platform("Windows 11", "amd64"));
        assertEquals("win32", ChromeForTestingInstaller.platform("Windows 10", "x86"));
        assertThrows(IllegalStateException.class,
                () -> ChromeForTestingInstaller.platform("Linux", "riscv64"));
    }

    private ChromeForTestingInstaller installer() {
        return new ChromeForTestingInstaller(base.resolve("version"), base, "linux64");
    }

    private static void assertTemporaryDirectoryEmpty(ApplicationHome home) throws IOException {
        try (var files = Files.list(home.temporaryDirectory())) {
            assertEquals(0, files.count(), "失败或完成后均应清理临时安装包");
        }
    }

    private static byte[] archive() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(bytes)) {
            var entry = new ZipArchiveEntry("chrome-linux64/chrome");
            entry.setUnixMode(0100755);
            zip.putArchiveEntry(entry);
            zip.write("test chrome executable".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
}
