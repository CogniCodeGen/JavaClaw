package com.javaclaw.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 真实原生 Browser smoke 的分项能力回执；任一失败路径必须先移除对应旧回执。 */
enum BrowserNativeCapability {
    INTERACTIVE_LOGIN("browser-login-v1.capability", "browser-login-v1", ".browser-login-"),
    MCP_OAUTH("browser-oauth-v1.capability", "browser-oauth-v1", ".browser-oauth-");

    private final String fileName;
    private final String prefix;
    private final String temporaryPrefix;

    BrowserNativeCapability(String fileName, String prefix, String temporaryPrefix) {
        this.fileName = fileName;
        this.prefix = prefix;
        this.temporaryPrefix = temporaryPrefix;
    }

    String fileName() {
        return fileName;
    }

    String receipt() {
        return prefix + ":" + platformId();
    }

    void prepare(Path imageRoot) throws IOException {
        Path marker = marker(imageRoot);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Browser capability marker is unsafe");
        }
        makeWritable(marker);
        Files.delete(marker);
    }

    void publish(Path imageRoot) throws IOException {
        Path marker = marker(imageRoot);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Browser capability marker already exists");
        }
        Path temporary = Files.createTempFile(marker.getParent(), temporaryPrefix, ".tmp");
        try {
            Files.writeString(temporary, receipt() + System.lineSeparator(), StandardCharsets.US_ASCII);
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
            setReadOnly(marker);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String platformId() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "macos";
        }
        if (os.contains("win")) {
            return "windows";
        }
        return "linux";
    }

    static boolean isReadOnly(Path marker) throws IOException {
        if (isWindows()) {
            return Files.readAttributes(marker, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isReadOnly();
        }
        return !Files.getPosixFilePermissions(marker, LinkOption.NOFOLLOW_LINKS)
                .contains(PosixFilePermission.OWNER_WRITE);
    }

    private Path marker(Path imageRoot) throws IOException {
        if (Files.isSymbolicLink(imageRoot)) {
            throw new IOException("Browser Worker image root is a symbolic link");
        }
        Path root = imageRoot.toRealPath();
        Path marker = root.resolve(fileName).normalize();
        if (!marker.getParent().equals(root)) {
            throw new IOException("Browser capability marker escapes its image");
        }
        requireVerifiedImage(root);
        return marker;
    }

    private static void requireVerifiedImage(Path root) throws IOException {
        Path imageMarker = root.resolve(WorkerImageAssemblerMain.IMAGE_MARKER);
        if (Files.isSymbolicLink(imageMarker)
                || !Files.isRegularFile(imageMarker, LinkOption.NOFOLLOW_LINKS)
                || !"worker-image-v1:browser"
                        .equals(Files.readString(imageMarker, StandardCharsets.US_ASCII)
                                .strip())) {
            throw new IOException("Browser Worker image has not been assembled and verified");
        }
    }

    private static void setReadOnly(Path marker) throws IOException {
        if (isWindows()) {
            Files.setAttribute(marker, "dos:readonly", true, LinkOption.NOFOLLOW_LINKS);
            return;
        }
        Files.setPosixFilePermissions(
                marker,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));
    }

    private static void makeWritable(Path marker) throws IOException {
        if (isWindows()) {
            Files.setAttribute(marker, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
            return;
        }
        HashSet<PosixFilePermission> permissions =
                new HashSet<>(Files.getPosixFilePermissions(marker, LinkOption.NOFOLLOW_LINKS));
        permissions.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(marker, permissions);
    }

    private static boolean isWindows() {
        return platformId().equals("windows");
    }
}
