package com.javaclaw.server.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import com.javaclaw.server.security.PrivateDirectories;

/** v4 拒绝旧格式或未标记的非空目录，且拒绝之前不改变原目录内容或权限。 */
final class V4DataRoot {
    private static final String FORMAT = "4";
    private static final String MARKER = ".javaclaw-format";
    private static final Set<PosixFilePermission> OWNER_FILE =
            Set.copyOf(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

    private V4DataRoot() {}

    static Path initialize(Path value) {
        Path root = value.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            root = root.toRealPath();
            Path marker = root.resolve(MARKER);
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException(
                            "JavaClaw 4.0 data format marker must be a regular file: " + marker);
                }
                String actual = Files.readString(marker).strip();
                if (!FORMAT.equals(actual)) {
                    throw new IllegalStateException("JavaClaw 4.0 refuses data format " + actual + " at " + root);
                }
                // 格式确认之前不调整权限；拒绝旧数据根必须是只读检查，不能顺手改变 3.x 用户目录。
                PrivateDirectories.create(root);
                setOwnerOnly(marker, OWNER_FILE);
                return root;
            }
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalStateException("JavaClaw 4.0 refuses an unmarked non-empty data root: " + root);
                }
            }
            PrivateDirectories.create(root);
            Files.writeString(
                    marker, FORMAT + System.lineSeparator(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            setOwnerOnly(marker, OWNER_FILE);
            return root;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot initialize JavaClaw 4 data root: " + root, failure);
        }
    }

    private static void setOwnerOnly(Path path, Set<PosixFilePermission> permissions) throws IOException {
        PosixFileAttributeView view =
                Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            view.setPermissions(permissions);
        }
    }
}
