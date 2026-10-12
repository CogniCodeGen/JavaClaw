package com.javaclaw.browser;

import com.javaclaw.platform.data.ApplicationHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/** 仅在系统缺少 Chrome 时准备一份应用内的 Google Chrome for Testing。 */
final class ChromeForTestingInstaller {

    private static final Logger log = LoggerFactory.getLogger(ChromeForTestingInstaller.class);
    private static final URI STABLE_VERSION = URI.create(
            "https://googlechromelabs.github.io/chrome-for-testing/LATEST_RELEASE_STABLE");
    private static final URI DOWNLOADS = URI.create(
            "https://storage.googleapis.com/chrome-for-testing-public/");
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(15);
    private static final ReentrantLock INSTALL_LOCK = new ReentrantLock();

    private final URI versionUri;
    private final URI downloadsUri;
    private final String platform;

    ChromeForTestingInstaller() {
        this(STABLE_VERSION, DOWNLOADS, platform(
                System.getProperty("os.name", ""), System.getProperty("os.arch", "")));
    }

    ChromeForTestingInstaller(URI versionUri, URI downloadsUri, String platform) {
        this.versionUri = versionUri;
        this.downloadsUri = downloadsUri;
        this.platform = platform;
    }

    /** 复用已完成的安装；下载失败或取消时清理临时包，保留已有安装。 */
    Path ensureInstalled(ApplicationHome home) throws IOException {
        try {
            INSTALL_LOCK.lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("等待 Chrome 准备时已取消");
        }
        try {
            Path root = home.cacheDirectory().resolve("google-chrome").resolve(platform);
            home.requireDirectory(root);
            Path active = home.requireManaged(root.resolve("active-version"));
            if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
                String version = readCachedVersion(active);
                if (version != null) {
                    Path cached = installedExecutable(home, root, version);
                    if (cached != null) return cached;
                }
            }
            String version = fetchStableVersion();
            Path executable = installedExecutable(home, root, version);
            if (executable == null) executable = install(home, root, version);
            Path marker = Files.createTempFile(root, "active-", ".tmp");
            try {
                Files.writeString(marker, version, StandardCharsets.UTF_8);
                Files.move(marker, active, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(marker);
            }
            return executable;
        } finally {
            INSTALL_LOCK.unlock();
        }
    }

    private Path install(ApplicationHome home, Path root, String version) throws IOException {
        home.requireDirectory(home.temporaryDirectory());
        Path staging = Files.createTempDirectory(home.temporaryDirectory(), "chrome-install-");
        Path target = home.requireManaged(root.resolve(version));
        try {
            Path archive = staging.resolve("chrome.zip");
            URI uri = downloadsUri.resolve(version + "/" + platform + "/chrome-" + platform + ".zip");
            log.info("正在下载应用内 Google Chrome for Testing {}（{}）", version, platform);
            download(uri, archive);
            Path unpacked = Files.createDirectory(staging.resolve("unpacked"));
            ChromeArchiveExtractor.extract(archive, unpacked);
            Path executable = unpacked.resolve(executableRelativePath(platform));
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isExecutable(executable)) {
                throw new IOException("Chrome 安装包缺少可执行程序: " + executable.getFileName());
            }
            Files.writeString(unpacked.resolve(".complete"), version, StandardCharsets.UTF_8);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) deleteTree(target);
            Files.move(unpacked, target);
            log.info("Google Chrome 已准备完成: {}", target);
            return target.resolve(executableRelativePath(platform));
        } finally {
            try {
                deleteTree(staging);
            } catch (IOException cleanupFailure) {
                log.warn("清理 Chrome 临时安装目录失败: {}", staging, cleanupFailure);
            }
        }
    }

    private Path installedExecutable(ApplicationHome home, Path root, String version) throws IOException {
        Path directory = home.requireManaged(root.resolve(version));
        Path marker = home.requireManaged(directory.resolve(".complete"));
        Path executable = home.requireManaged(directory.resolve(executableRelativePath(platform)));
        if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                && version.equals(readCachedVersion(marker))
                && Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                && Files.isExecutable(executable)) {
            log.info("复用应用内 Google Chrome: {}", executable);
            return executable;
        }
        return null;
    }

    private String fetchStableVersion() throws IOException {
        checkInterrupted();
        HttpURLConnection connection = connect(versionUri);
        try {
            requireSuccess(connection);
            try (InputStream input = connection.getInputStream()) {
                String version = new String(input.readNBytes(65), StandardCharsets.UTF_8).strip();
                checkInterrupted();
                return requireVersion(version);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void download(URI uri, Path target) throws IOException {
        HttpURLConnection connection = connect(uri);
        long deadline = System.nanoTime() + DOWNLOAD_TIMEOUT.toNanos();
        try {
            requireSuccess(connection);
            long expected = connection.getContentLengthLong();
            if (expected > MAX_ARCHIVE_BYTES) throw new IOException("Chrome 安装包超过 512 MiB");
            try (InputStream input = connection.getInputStream();
                 var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                long nextProgress = 16L * 1024 * 1024;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancellation(deadline);
                    total += count;
                    if (total > MAX_ARCHIVE_BYTES) throw new IOException("Chrome 安装包超过 512 MiB");
                    output.write(buffer, 0, count);
                    if (total >= nextProgress) {
                        log.info("Chrome 下载进度: {} / {} MiB", total / 1024 / 1024,
                                expected < 0 ? "未知" : expected / 1024 / 1024);
                        nextProgress = total + 16L * 1024 * 1024;
                    }
                }
                checkCancellation(deadline);
                if (expected >= 0 && total != expected) {
                    throw new IOException("Chrome 下载不完整: " + total + " / " + expected + " 字节");
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection connect(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "JavaClaw Chrome Runtime");
        return connection;
    }

    private static void requireSuccess(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status != 200) throw new IOException("Chrome 下载服务返回 HTTP " + status);
    }

    private static void checkCancellation(long deadline) throws IOException {
        checkInterrupted();
        if (System.nanoTime() > deadline) throw new IOException("Chrome 下载超过 15 分钟，请检查网络后重试");
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Chrome 准备已取消");
    }

    private static String readCachedVersion(Path file) throws IOException {
        if (Files.size(file) > 64) {
            log.warn("忽略损坏的 Chrome 版本标记: {}", file);
            return null;
        }
        String version = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (version.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return version;
        log.warn("忽略损坏的 Chrome 版本标记: {}", file);
        return null;
    }

    private static String requireVersion(String version) throws IOException {
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+") || version.length() > 64) {
            throw new IOException("Chrome 下载服务返回无效版本号");
        }
        return version;
    }

    static String platform(String operatingSystem, String architecture) {
        String os = operatingSystem.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("mac") && (arm || x64)) return arm ? "mac-arm64" : "mac-x64";
        if (os.contains("linux") && (arm || x64)) return arm ? "linux-arm64" : "linux64";
        if (os.contains("win") && x64) return "win64";
        if (os.contains("win") && (arch.equals("x86") || arch.equals("i386"))) return "win32";
        throw new IllegalStateException("Google Chrome 自动下载不支持当前平台: " + operatingSystem + "/" + architecture);
    }

    static Path executableRelativePath(String platform) {
        String base = "chrome-" + platform + "/";
        if (platform.startsWith("mac-")) {
            return Path.of(base + "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing");
        }
        return Path.of(base + (platform.startsWith("win") ? "chrome.exe" : "chrome"));
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
