package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.service.runner.ServicePluginProcessMain;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captured, immutable classpath for the built-in service-plugin child-process host. */
final class ServicePluginHostRuntime {
    private static final String HOST_CLASSES = "com/javaclaw/service";

    private final List<Source> sources;
    private final String classpath;

    static ServicePluginHostRuntime capture() {
        try {
            Path host = codeSource(ServicePluginProcessMain.class);
            return new ServicePluginHostRuntime(host, List.of(
                    host,
                    codeSource(ObjectMapper.class),
                    codeSource(JsonFactory.class),
                    codeSource(JsonInclude.class)));
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalStateException("无法解析 JavaClaw 内置服务插件运行时", failure);
        }
    }

    ServicePluginHostRuntime(Path hostSource, List<Path> classpathSources) throws IOException {
        Path normalizedHost = normalize(hostSource);
        Map<Path, Boolean> unique = new LinkedHashMap<>();
        for (Path source : classpathSources) {
            Path normalized = normalize(source);
            unique.merge(normalized, !normalized.equals(normalizedHost), Boolean::logicalOr);
        }
        if (!unique.containsKey(normalizedHost)) {
            throw new IOException("内置服务插件入口不在子进程 classpath 中");
        }
        List<Source> captured = new ArrayList<>(unique.size());
        for (Map.Entry<Path, Boolean> entry : unique.entrySet()) {
            captured.add(new Source(entry.getKey(), entry.getValue(),
                    fingerprint(entry.getKey(), entry.getValue())));
        }
        sources = List.copyOf(captured);
        classpath = sources.stream().map(value -> value.path().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    String classpath() {
        return classpath;
    }

    String mainClass() {
        return ServicePluginProcessMain.class.getName();
    }

    void verifyUnchanged() throws IOException {
        for (Source source : sources) {
            String current = fingerprint(source.path(), source.wholeDirectory());
            if (!MessageDigest.isEqual(current.getBytes(StandardCharsets.US_ASCII),
                    source.fingerprint().getBytes(StandardCharsets.US_ASCII))) {
                throw new SecurityException("JavaClaw 内置服务插件运行时在应用启动后被修改: "
                        + source.path());
            }
        }
    }

    private static Path codeSource(Class<?> type) throws IOException, URISyntaxException {
        var protection = type.getProtectionDomain();
        var source = protection == null ? null : protection.getCodeSource();
        var location = source == null ? null : source.getLocation();
        if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
            throw new IOException("无法读取代码源: " + type.getName());
        }
        return Path.of(location.toURI());
    }

    private static Path normalize(Path source) throws IOException {
        if (source == null) throw new IOException("服务插件运行时代码源为空");
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("服务插件运行时代码源缺失或为符号链接: " + normalized);
        }
        return normalized;
    }

    private static String fingerprint(Path source, boolean wholeDirectory) throws IOException {
        normalize(source);
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return hashFile(source);
        Path root = wholeDirectory ? source : source.resolve(HOST_CLASSES);
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("服务插件运行时类目录缺失或为符号链接: " + root);
        }
        MessageDigest digest = digest();
        List<Path> entries;
        try (var walked = Files.walk(root)) {
            entries = walked.sorted().toList();
        }
        int files = 0;
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry)) {
                throw new IOException("服务插件运行时包含符号链接: " + entry);
            }
            if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
            files++;
            digest.update(root.relativize(entry).toString().replace('\\', '/')
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            update(digest, entry);
        }
        if (files == 0) throw new IOException("服务插件运行时类目录为空: " + root);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hashFile(Path file) throws IOException {
        MessageDigest digest = digest();
        update(digest, file);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Source(Path path, boolean wholeDirectory, String fingerprint) { }
}
