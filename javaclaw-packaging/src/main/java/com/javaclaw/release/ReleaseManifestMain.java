package com.javaclaw.release;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 为发行目录生成或校验确定性的 SHA-256 清单。
 *
 * <p>清单覆盖普通文件和符号链接；符号链接记录其目标文本，不会跟随到发行目录之外。输出文件自身和临时文件不参与计算， 因而相同输入总会得到相同字节。
 */
public final class ReleaseManifestMain {
    private static final HexFormat HEX = HexFormat.of();
    private static final String ALGORITHM = "SHA-256";

    private ReleaseManifestMain() {}

    /**
     * 执行清单命令。
     *
     * @param arguments 三个参数依次为 {@code create|verify}、发行根目录和清单文件
     * @throws Exception 当参数无效、目录包含特殊文件、写入失败或校验不一致时抛出
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("用法：ReleaseManifestMain <create|verify> <root> <manifest>");
        }
        Path root = Path.of(arguments[1]);
        Path manifest = Path.of(arguments[2]);
        switch (arguments[0]) {
            case "create" -> create(root, manifest);
            case "verify" -> verify(root, manifest);
            default -> throw new IllegalArgumentException("未知清单命令：" + arguments[0]);
        }
    }

    static void create(Path root, Path manifest) throws IOException {
        ManifestPaths paths = validatePaths(root, manifest);
        Files.createDirectories(paths.manifest().getParent());
        Files.deleteIfExists(paths.temporary());
        byte[] content = render(paths).getBytes(StandardCharsets.UTF_8);
        writeAtomically(paths, content);
    }

    static void verify(Path root, Path manifest) throws IOException {
        ManifestPaths paths = validatePaths(root, manifest);
        if (!Files.isRegularFile(paths.manifest(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("发行清单不存在：" + paths.manifest());
        }
        Files.deleteIfExists(paths.temporary());
        byte[] expected = render(paths).getBytes(StandardCharsets.UTF_8);
        byte[] actual = Files.readAllBytes(paths.manifest());
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalStateException("发行清单与目录内容不一致：" + paths.manifest());
        }
    }

    private static ManifestPaths validatePaths(Path root, Path manifest) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("发行根目录不存在或不是普通目录：" + normalizedRoot);
        }
        Path normalizedManifest = manifest.toAbsolutePath().normalize();
        if (!normalizedManifest.startsWith(normalizedRoot) || normalizedManifest.equals(normalizedRoot)) {
            throw new IllegalArgumentException("发行清单必须位于发行根目录内");
        }
        Path temporary = normalizedManifest.resolveSibling(normalizedManifest.getFileName() + ".tmp");
        return new ManifestPaths(normalizedRoot, normalizedManifest, temporary);
    }

    private static String render(ManifestPaths paths) throws IOException {
        List<ManifestEntry> entries = readEntries(paths);
        StringBuilder json = new StringBuilder(128 + entries.size() * 128);
        json.append("{\n");
        json.append("  \"schemaVersion\": 1,\n");
        json.append("  \"algorithm\": \"").append(ALGORITHM).append("\",\n");
        json.append("  \"entries\": [\n");
        for (int index = 0; index < entries.size(); index++) {
            appendEntry(json, entries.get(index));
            json.append(index + 1 == entries.size() ? '\n' : ",\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static List<ManifestEntry> readEntries(ManifestPaths paths) throws IOException {
        Set<Path> excluded = Set.of(paths.manifest(), paths.temporary());
        List<Path> candidates;
        try (Stream<Path> walked = Files.walk(paths.root())) {
            candidates = walked.filter(path -> !path.equals(paths.root()))
                    .filter(path -> !excluded.contains(path.toAbsolutePath().normalize()))
                    .filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> relativeName(paths.root(), path)))
                    .toList();
        }
        List<ManifestEntry> entries = new ArrayList<>(candidates.size());
        for (Path candidate : candidates) {
            entries.add(readEntry(paths.root(), candidate));
        }
        return List.copyOf(entries);
    }

    private static ManifestEntry readEntry(Path root, Path path) throws IOException {
        String relativePath = relativeName(root, path);
        if (Files.isSymbolicLink(path)) {
            byte[] target = Files.readSymbolicLink(path).toString().getBytes(StandardCharsets.UTF_8);
            return new ManifestEntry(relativePath, "symlink", target.length, digest(target));
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("发行目录包含不受支持的特殊文件：" + path);
        }
        return new ManifestEntry(relativePath, "file", Files.size(path), digest(path));
    }

    private static String relativeName(Path root, Path path) {
        String relative = root.relativize(path.toAbsolutePath().normalize()).toString();
        return "\\".equals(root.getFileSystem().getSeparator()) ? relative.replace('\\', '/') : relative;
    }

    private static String digest(Path path) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HEX.formatHex(digest.digest());
    }

    private static String digest(byte[] content) {
        return HEX.formatHex(newDigest().digest(content));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    private static void appendEntry(StringBuilder json, ManifestEntry entry) {
        json.append("    {\"path\": \"").append(escapeJson(entry.path())).append("\", ");
        json.append("\"type\": \"").append(entry.type()).append("\", ");
        json.append("\"size\": ").append(entry.size()).append(", ");
        json.append("\"sha256\": \"").append(entry.sha256()).append("\"}");
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> appendJsonCharacter(escaped, character);
            }
        }
        return escaped.toString();
    }

    private static void appendJsonCharacter(StringBuilder escaped, char character) {
        if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
        } else {
            escaped.append(character);
        }
    }

    private static void writeAtomically(ManifestPaths paths, byte[] content) throws IOException {
        try (FileChannel output =
                FileChannel.open(paths.temporary(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer remaining = ByteBuffer.wrap(content);
            while (remaining.hasRemaining()) {
                output.write(remaining);
            }
            output.force(true);
        }
        try {
            Files.move(
                    paths.temporary(),
                    paths.manifest(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(paths.temporary(), paths.manifest(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record ManifestPaths(Path root, Path manifest, Path temporary) {}

    private record ManifestEntry(String path, String type, long size, String sha256) {}
}
