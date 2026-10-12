package com.javaclaw.infrastructure.plugin.v2;

import com.javaclaw.application.plugin.v2.PluginManagementService.PluginException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 插件文件始终限制在已选择的根目录，拒绝路径穿越和符号链接。 */
final class PluginPaths {
    private PluginPaths() { }
    static String relative(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0
                || value.startsWith("/") || value.contains(":")) throw invalid("插件包路径无效");
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        for (String part : normalized.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..")) throw invalid("插件包路径穿越被拒绝");
        return normalized;
    }
    static Path managed(Path root, Path candidate) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path path = candidate.toAbsolutePath().normalize();
        if (!path.startsWith(base)) throw new IOException("插件路径超出管理目录");
        for (Path current = base; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("插件根路径包含符号链接");
        }
        Path current = base;
        for (Path part : base.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("插件管理目录包含符号链接");
        }
        return path;
    }
    static Path directory(Path root, Path candidate) throws IOException {
        Path path = managed(root, candidate);
        Files.createDirectories(path);
        managed(root, path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("插件目录不是普通文件夹");
        return path;
    }
    static String sha256(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[64 * 1024];
            int read;
            while ((read = input.read(bytes)) >= 0) if (read > 0) digest.update(bytes, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void move(Path from, Path to) throws IOException {
        // 管理目录必须位于同一文件系统，不接受可观察到半成品的复制降级。
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }
    static void deleteTree(Path root, Path directory) throws IOException {
        Path checked = managed(root, directory);
        if (!Files.exists(checked, LinkOption.NOFOLLOW_LINKS)) return;
        try (var tree = Files.walk(checked)) {
            for (Path path : tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                managed(root, path);
                Files.delete(path);
            }
        }
    }
    private static PluginException invalid(String message) { return new PluginException("unsafe_path", message); }
}
