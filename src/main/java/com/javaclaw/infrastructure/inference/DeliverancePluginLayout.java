package com.javaclaw.infrastructure.inference;

import com.javaclaw.infrastructure.inference.serviceplugin.DeliveranceServicePluginGateway;
import com.javaclaw.util.ProjectAccessPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

/** Canonical one-directory/one-JAR layout for the built-in Deliverance sample plugin. */
final class DeliverancePluginLayout {
    static final String JAR_NAME = "deliverance.jar";
    static final String LEGACY_JAR_NAME = "deliverance-sidecar.jar";

    private final Path pluginsRoot;
    private final Path pluginRoot;
    private final Path activeJar;
    private final Path legacyJar;
    private final Path legacyVersions;

    DeliverancePluginLayout(Path pluginsRoot) {
        this.pluginsRoot = pluginsRoot.toAbsolutePath().normalize();
        pluginRoot = this.pluginsRoot.resolve(DeliveranceServicePluginGateway.PLUGIN_ID)
                .toAbsolutePath().normalize();
        activeJar = pluginRoot.resolve(JAR_NAME).toAbsolutePath().normalize();
        legacyJar = pluginRoot.resolve(LEGACY_JAR_NAME).toAbsolutePath().normalize();
        legacyVersions = pluginRoot.resolve("versions").toAbsolutePath().normalize();
    }

    static DeliverancePluginLayout production() {
        Path root = ProjectAccessPolicy.requireProjectFilePath(
                ProjectAccessPolicy.projectRoot().resolve("plugins"));
        return new DeliverancePluginLayout(root);
    }

    /** Migrates the old sidecar filename atomically without executing or opening plugin classes. */
    void prepare() throws IOException {
        requireUnder(pluginsRoot, pluginRoot);
        Files.createDirectories(pluginsRoot);
        if (Files.isSymbolicLink(pluginsRoot)) throw new IOException("插件根目录不能是符号链接");
        if (Files.exists(pluginRoot, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(pluginRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(pluginRoot))) {
            throw new IOException("Deliverance 插件目录无效");
        }
        if (!Files.exists(pluginRoot, LinkOption.NOFOLLOW_LINKS)) return;
        validateJarIfPresent(activeJar);
        validateJarIfPresent(legacyJar);
        if (Files.exists(legacyJar, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(activeJar, LinkOption.NOFOLLOW_LINKS)) {
                if (!sha256(activeJar).equalsIgnoreCase(sha256(legacyJar))) {
                    throw new IOException("新旧 Deliverance JAR 同时存在且内容不同，请在插件中心处理冲突");
                }
                Files.delete(legacyJar);
            } else {
                atomicMove(legacyJar, activeJar);
            }
        }
        removeLegacyRuntimeCopies();
    }

    void requireCanonicalJar(Path value) {
        Path normalized = value.toAbsolutePath().normalize();
        if (!normalized.equals(activeJar)) {
            throw new SecurityException("Deliverance 插件必须位于 plugins/builtin-deliverance/deliverance.jar");
        }
    }

    private static void validateJarIfPresent(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Deliverance 插件 JAR 必须是普通文件");
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    /** Removes only the obsolete runtime-ZIP cache after a canonical plugin JAR is available. */
    private void removeLegacyRuntimeCopies() throws IOException {
        if (!Files.exists(activeJar, LinkOption.NOFOLLOW_LINKS)
                || !Files.exists(legacyVersions, LinkOption.NOFOLLOW_LINKS)) return;
        requireUnder(pluginRoot, legacyVersions);
        if (!Files.isDirectory(legacyVersions, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(legacyVersions)) {
            throw new IOException("旧 Deliverance runtime 目录无效");
        }
        try (var paths = Files.walk(legacyVersions)) {
            var entries = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    throw new IOException("旧 Deliverance runtime 目录包含符号链接，拒绝自动清理");
                }
            }
            for (Path entry : entries) Files.deleteIfExists(entry);
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireUnder(Path root, Path value) {
        if (!value.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new SecurityException("插件文件路径越界");
        }
    }
}
