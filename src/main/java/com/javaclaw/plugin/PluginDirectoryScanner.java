package com.javaclaw.plugin;

import com.javaclaw.plugin.api.PluginDescriptor;
import com.javaclaw.util.PathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Reads static plugin metadata without loading plugin classes or starting plugin processes. */
final class PluginDirectoryScanner {

    private static final Logger log = LoggerFactory.getLogger(PluginDirectoryScanner.class);

    private final Path pluginsDirectory;
    private final PluginDescriptorLoader descriptors;

    PluginDirectoryScanner(Path pluginsDirectory, PluginDescriptorLoader descriptors) {
        this.pluginsDirectory = pluginsDirectory;
        this.descriptors = descriptors;
    }

    List<Candidate> scan() {
        if (!Files.isDirectory(pluginsDirectory)) return List.of();
        List<Candidate> candidates = new ArrayList<>();
        try (Stream<Path> entries = Files.list(pluginsDirectory)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                if (!PathGuard.isInside(pluginsDirectory, directory)) {
                    log.warn("跳过指向插件根外部的目录：{}", directory);
                    continue;
                }
                Candidate candidate = read(directory);
                if (candidate != null) candidates.add(candidate);
            }
        } catch (IOException failure) {
            log.error("扫描插件目录失败：{}", failure.toString());
        }
        return List.copyOf(candidates);
    }

    private Candidate read(Path directory) {
        Path jar = singleTopLevelJar(directory);
        if (jar == null) return null;
        try {
            PluginDescriptor descriptor = descriptors.load(jar);
            if (!descriptor.id().equals(directory.getFileName().toString())) {
                log.warn("插件目录名[{}]与 plugin.json id[{}]不一致，已拒绝发现",
                        directory.getFileName(), descriptor.id());
                return null;
            }
            return new Candidate(directory, jar, descriptor);
        } catch (Exception failure) {
            log.warn("解析插件失败，跳过 {}：{}", directory.getFileName(), failure.toString());
            return null;
        }
    }

    private Path singleTopLevelJar(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> jars = files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .toList();
            if (jars.size() > 1) {
                log.warn("插件目录[{}]包含多个顶层 JAR，已拒绝发现", directory.getFileName());
                return null;
            }
            if (jars.isEmpty()) log.debug("插件子目录无 jar，跳过：{}", directory.getFileName());
            return jars.isEmpty() ? null : jars.getFirst();
        } catch (IOException failure) {
            log.warn("读取插件子目录失败 {}：{}", directory.getFileName(), failure.toString());
            return null;
        }
    }

    record Candidate(Path directory, Path jar, PluginDescriptor descriptor) { }
}
