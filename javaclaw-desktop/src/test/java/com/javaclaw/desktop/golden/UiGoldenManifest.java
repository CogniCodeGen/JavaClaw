package com.javaclaw.desktop.golden;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class UiGoldenManifest {
    static final String FILE_NAME = "manifest.txt";

    private UiGoldenManifest() {}

    static String create(String platform, Path directory, List<UiGoldenCase> cases) throws IOException {
        StringBuilder manifest = new StringBuilder();
        manifest.append("schema=javaclaw-ui-golden-v1\n");
        manifest.append("platform=").append(platform).append('\n');
        manifest.append("themes=9\n");
        manifest.append("densities=3\n");
        manifest.append("font-scale=100\n");
        manifest.append("viewports=minimum:880x620,standard:1040x720\n");
        manifest.append("scene=production-management-center\n");
        manifest.append("management-entries=29\n");
        manifest.append("images=").append(cases.size()).append('\n');
        for (UiGoldenCase golden : cases) {
            Path image = directory.resolve(golden.fileName());
            manifest.append(golden.fileName())
                    .append(" sha256=")
                    .append(digest(image))
                    .append(" bytes=")
                    .append(Files.size(image))
                    .append('\n');
        }
        return manifest.toString();
    }

    private static String digest(Path source) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }
}
