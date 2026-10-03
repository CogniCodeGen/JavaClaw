package com.javaclaw.infrastructure.inference;

import com.javaclaw.infrastructure.inference.serviceplugin.DeliveranceServicePluginGateway;
import com.javaclaw.platform.data.ApplicationHome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Canonical one-directory/one-JAR layout for the built-in Deliverance sample plugin. */
final class DeliverancePluginLayout {
    static final String JAR_NAME = "deliverance.jar";

    private final Path pluginsRoot;
    private final Path pluginRoot;
    private final Path activeJar;

    DeliverancePluginLayout(Path pluginsRoot) {
        this.pluginsRoot = pluginsRoot.toAbsolutePath().normalize();
        pluginRoot = this.pluginsRoot.resolve(DeliveranceServicePluginGateway.PLUGIN_ID)
                .toAbsolutePath().normalize();
        activeJar = pluginRoot.resolve(JAR_NAME).toAbsolutePath().normalize();
    }

    static DeliverancePluginLayout production() {
        return new DeliverancePluginLayout(ApplicationHome.resolve().pluginsDirectory());
    }

    /** Validates the canonical plugin directory without opening plugin classes. */
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

    private static void requireUnder(Path root, Path value) {
        if (!value.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new SecurityException("插件文件路径越界");
        }
    }
}
