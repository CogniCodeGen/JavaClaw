package com.javaclaw.infrastructure.serviceplugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Rechecks the approved plugin artifact immediately before process launch. */
final class ServicePluginArtifactVerifier {
    private static final String ALLOW_UNSIGNED_DEVELOPMENT =
            "javaclaw.service-plugin.allow-unsigned-development";

    boolean trusted(ServicePluginDefinition definition) {
        return definition.signatureVerified()
                || (definition.developmentUnsigned()
                && Boolean.getBoolean(ALLOW_UNSIGNED_DEVELOPMENT));
    }

    void verify(ServicePluginDefinition definition) throws IOException {
        if (!trusted(definition)) throw new SecurityException("服务插件签名未经验证");
        Path file = definition.pluginJar();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("服务插件运行文件缺失: " + file.getFileName());
        }
        if (!sha256(definition.pluginJar()).equalsIgnoreCase(definition.artifactSha256())) {
            throw new SecurityException("服务插件 JAR 在安装后被修改");
        }
        Files.createDirectories(definition.dataDirectory());
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
