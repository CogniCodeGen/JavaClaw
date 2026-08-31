package com.javaclaw.server.browser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

/** 仅供固定 Browser Worker 使用的 Linux 本地显示连接；不继承 TCP DISPLAY 或整份宿主认证文件。 */
record BrowserDisplay(Map<String, String> environment, Set<Path> readableRoots) {
    private static final BrowserDisplay NONE = new BrowserDisplay(Map.of(), Set.of());

    static BrowserDisplay discover(String os, Map<String, String> environment, Path privateDirectory)
            throws IOException {
        if (!os.toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            return NONE;
        }
        var owner = Files.getOwner(privateDirectory);
        String wayland = environment.get("WAYLAND_DISPLAY");
        String runtime = environment.get("XDG_RUNTIME_DIR");
        if (wayland != null && wayland.matches("wayland-[0-9]{1,5}") && runtime != null) {
            Path directory = Path.of(runtime).toRealPath();
            Path socket = directory.resolve(wayland);
            if (Files.getOwner(directory).equals(owner)
                    && privatePermissions(directory)
                    && socket(socket)
                    && Files.getOwner(socket, LinkOption.NOFOLLOW_LINKS).equals(owner)) {
                return new BrowserDisplay(
                        Map.of("WAYLAND_DISPLAY", wayland, "XDG_RUNTIME_DIR", directory.toString()),
                        Set.of(socket.toRealPath()));
            }
        }
        String display = environment.get("DISPLAY");
        String authorization = environment.get("XAUTHORITY");
        if (display == null || !display.matches(":[0-9]{1,5}(\\.[0-9]{1,2})?") || authorization == null) {
            return NONE;
        }
        String number = display.substring(1).split("\\.")[0];
        Path socket = Path.of("/tmp/.X11-unix/X" + number);
        Path source = Path.of(authorization);
        if (!socket(socket)
                || (!Files.getOwner(socket, LinkOption.NOFOLLOW_LINKS).equals(owner)
                        && (int) Files.getAttribute(socket, "unix:uid", LinkOption.NOFOLLOW_LINKS) != 0)
                || Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(source).equals(owner)
                || !privatePermissions(source)
                || Files.size(source) > 65_536) {
            return NONE;
        }
        byte[] selected;
        try (var input = Files.newInputStream(source)) {
            selected = selectAuthority(input.readNBytes(65_537), number);
        }
        if (selected.length == 0) {
            return NONE;
        }
        Path copy = privateDirectory.resolve("display.xauthority");
        Files.createFile(
                copy,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        try {
            Files.write(copy, selected);
        } finally {
            java.util.Arrays.fill(selected, (byte) 0);
        }
        return new BrowserDisplay(
                Map.of("DISPLAY", display, "XAUTHORITY", copy.toString()), Set.of(socket.toRealPath()));
    }

    static byte[] selectAuthority(byte[] content, String displayNumber) throws IOException {
        if (content.length > 65_536) {
            java.util.Arrays.fill(content, (byte) 0);
            throw new IOException("display authorization exceeds limit");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(content));
                var output = new ByteArrayOutputStream();
                var writer = new DataOutputStream(output)) {
            int entries = 0;
            while (input.available() > 0) {
                if (++entries > 256) {
                    throw new IOException("display authorization exceeds entry limit");
                }
                int family = input.readUnsignedShort();
                byte[] address = field(input);
                byte[] number = field(input);
                byte[] name = field(input);
                byte[] secret = field(input);
                if ((family == 256 || family == 65535)
                        && displayNumber.equals(new String(number, StandardCharsets.US_ASCII))
                        && "MIT-MAGIC-COOKIE-1".equals(new String(name, StandardCharsets.US_ASCII))
                        && secret.length == 16) {
                    // 只复制当前 display 的 cookie。命名空间内 hostname 可能不同，但唯一挂载的显示 socket 不变。
                    writer.writeShort(65535);
                    for (byte[] part : new byte[][] {address, number, name, secret}) {
                        writer.writeShort(part.length);
                        writer.write(part);
                    }
                }
                java.util.Arrays.fill(secret, (byte) 0);
            }
            return output.toByteArray();
        } finally {
            java.util.Arrays.fill(content, (byte) 0);
        }
    }

    private static byte[] field(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] result = input.readNBytes(length);
        if (result.length != length) {
            throw new IOException("display authorization is truncated");
        }
        return result;
    }

    private static boolean privatePermissions(Path path) throws IOException {
        return Files.getPosixFilePermissions(path).stream()
                .noneMatch(permission -> permission.name().startsWith("GROUP_")
                        || permission.name().startsWith("OTHERS_"));
    }

    private static boolean socket(Path path) throws IOException {
        return !Files.isSymbolicLink(path)
                && Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && ((int) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) & 0170000) == 0140000;
    }
}
