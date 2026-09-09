package com.javaclaw.release;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 将锁定依赖中的当前平台驱动安装到只读授权的镜像目录，禁止 Worker 从可写临时目录执行 Node。 */
final class BrowserDriverAssembler {
    private static final long MAXIMUM_BYTES = 256L * 1024 * 1024;

    private BrowserDriverAssembler() {}

    static void install(Path image, String version) throws IOException {
        Path bundle = image.resolve("app/driver-bundle-" + version + ".jar");
        if (Files.isSymbolicLink(bundle) || !Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Browser Worker locked driver bundle is missing or unsafe");
        }
        Path destination = image.resolve("driver");
        Files.createDirectory(destination);
        extract(bundle, destination, platformDirectory());
        Path node = destination.resolve(nodeName());
        if (!node.toFile().setExecutable(true, false) && !Files.isExecutable(node)) {
            throw new IOException("Browser Worker Node cannot be made executable");
        }
        verify(image);
    }

    static void verify(Path image) throws IOException {
        Path driver = image.resolve("driver").toRealPath();
        if (!driver.startsWith(image.toRealPath()) || !Files.isDirectory(driver)) {
            throw new IOException("Browser Worker driver escapes its image");
        }
        for (String relative : new String[] {nodeName(), "package/cli.js", "package/package.json", "LICENSE"}) {
            Path file = driver.resolve(relative).toRealPath();
            if (!file.startsWith(driver) || !Files.isRegularFile(file)) {
                throw new IOException("Browser Worker preinstalled driver is incomplete");
            }
        }
        if (!Files.isExecutable(driver.resolve(nodeName()))) {
            throw new IOException("Browser Worker Node is not executable");
        }
    }

    static void extract(Path bundle, Path destination, String platform) throws IOException {
        String prefix = "driver/" + platform + "/";
        long remaining = MAXIMUM_BYTES;
        int entries = 0;
        try (ZipFile archive = new ZipFile(bundle.toFile())) {
            var iterator = archive.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (!entry.getName().startsWith(prefix) || entry.getName().equals(prefix)) {
                    continue;
                }
                if (++entries > 20_000) {
                    throw new IOException("Browser Worker driver archive exceeds its entry limit");
                }
                remaining -= extractEntry(archive, entry, destination, prefix, remaining);
            }
        }
    }

    private static long extractEntry(ZipFile archive, ZipEntry entry, Path destination, String prefix, long remaining)
            throws IOException {
        String relative = entry.getName().substring(prefix.length());
        Path target = destination.resolve(relative).normalize();
        if (relative.contains("\\") || !target.startsWith(destination) || target.equals(destination)) {
            throw new IOException("Browser Worker driver archive entry escapes its destination");
        }
        if (entry.isDirectory()) {
            Files.createDirectories(target);
            return 0;
        }
        if (entry.getSize() < 0 || entry.getSize() > remaining) {
            throw new IOException("Browser Worker driver archive exceeds its byte limit");
        }
        Files.createDirectories(target.getParent());
        try (InputStream input = archive.getInputStream(entry);
                OutputStream output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            long copied = copyBounded(input, output, remaining);
            if (copied != entry.getSize()) {
                throw new IOException("Browser Worker driver archive entry size is inconsistent");
            }
            return copied;
        }
    }

    private static long copyBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[8192];
        long copied = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > maximum - copied) {
                throw new IOException("Browser Worker driver archive exceeds its byte limit");
            }
            output.write(buffer, 0, count);
            copied += count;
        }
        return copied;
    }

    static String platformDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        if (!arm && !arch.equals("amd64") && !arch.equals("x86_64")) {
            throw new IllegalArgumentException("Browser Worker driver architecture is unsupported");
        }
        if (os.contains("mac")) {
            return arm ? "mac-arm64" : "mac";
        }
        if (os.contains("windows") && !arm) {
            return "win32_x64";
        }
        if (os.contains("linux")) {
            return arm ? "linux-arm64" : "linux";
        }
        throw new IllegalArgumentException("Browser Worker driver platform is unsupported");
    }

    private static String nodeName() {
        return platformDirectory().equals("win32_x64") ? "node.exe" : "node";
    }
}
