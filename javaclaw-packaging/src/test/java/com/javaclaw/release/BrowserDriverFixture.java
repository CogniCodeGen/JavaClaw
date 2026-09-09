package com.javaclaw.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 使用不可运行的小型归档验证装配边界，不依赖开发机的真实 Node 或 Chromium。 */
final class BrowserDriverFixture {
    private BrowserDriverFixture() {}

    static Path bundle(Path image) throws IOException {
        String node = BrowserDriverAssembler.platformDirectory().equals("win32_x64") ? "node.exe" : "node";
        return bundle(
                image,
                Map.of(node, "node", "package/cli.js", "cli", "package/package.json", "{}", "LICENSE", "license"));
    }

    static Path bundle(Path image, Map<String, String> entries) throws IOException {
        Path app = Files.createDirectories(image.resolve("app"));
        Path bundle = app.resolve("driver-bundle-1.52.0.jar");
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(bundle))) {
            for (var entry : entries.entrySet()) {
                archive.putNextEntry(
                        new ZipEntry("driver/" + BrowserDriverAssembler.platformDirectory() + "/" + entry.getKey()));
                archive.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        }
        return bundle;
    }
}
