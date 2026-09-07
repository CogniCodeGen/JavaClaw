package com.javaclaw.server.extension.thirdparty;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import com.javaclaw.nativehost.ManagedRuntimeDirectory;

/** data-v6 内 staging、installed、Trash 与管理员信任目录的安全布局。 */
final class ThirdPartyBundleDirectories {
    private final Path root;
    private final Path staging;
    private final Path installed;
    private final Path trash;
    private final Clock clock;

    ThirdPartyBundleDirectories(Path dataRoot, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Path supplied =
                Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
        if (supplied.getFileName() == null
                || !"data-v6".equals(supplied.getFileName().toString())) {
            throw new IllegalArgumentException("data root must end with data-v6");
        }
        try {
            ManagedRuntimeDirectory.prepare(supplied);
            root = supplied.toRealPath();
            Path extensions = secureDirectory(root.resolve("extensions"));
            staging = secureDirectory(extensions.resolve("staging"));
            installed = secureDirectory(extensions.resolve("installed"));
            trash = secureDirectory(extensions.resolve("Trash"));
        } catch (IOException failure) {
            throw new IllegalStateException("无法初始化第三方扩展目录", failure);
        }
    }

    Path stagingRoot() {
        return staging;
    }

    Path staging(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid staging digest");
        }
        return child(staging, digest);
    }

    Path installed(String extensionId) {
        return child(installed, extensionId);
    }

    Path install(Path staged, String installDirectory) throws IOException {
        Path source = requireDirectChild(staging, staged);
        Path target = installed(installDirectory);
        if (Files.exists(target)) {
            throw new IllegalStateException("extension install directory already exists: " + installDirectory);
        }
        move(source, target);
        return target.toRealPath();
    }

    String installDirectory(String extensionId, long revision, String manifestDigest) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return extensionId + "-r" + revision + "-" + manifestDigest.substring(0, 12);
    }

    String trashName(String extensionId, long revision, String manifestDigest) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        String timestamp = Long.toUnsignedString(Instant.now(clock).toEpochMilli());
        return extensionId + "-r" + revision + "-" + timestamp + "-" + manifestDigest.substring(0, 12);
    }

    Path requireStaging(Path candidate) throws IOException {
        return requireDirectChild(staging, candidate);
    }

    Path requireInstalled(Path candidate) throws IOException {
        return requireDirectChild(installed, candidate);
    }

    String moveToTrash(String installDirectory, String trashName) throws IOException {
        Path source = installed(installDirectory);
        if (!Files.exists(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Path completed = child(trash, trashName);
            if (Files.isDirectory(completed, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(completed)) {
                return trashName;
            }
            throw new IllegalStateException("extension install directory does not exist: " + installDirectory);
        }
        Path target = child(trash, trashName);
        move(source, target);
        return trashName;
    }

    Path restoreFromTrash(String trashName, String installDirectory) throws IOException {
        Path source = requireDirectChild(trash, child(trash, trashName));
        Path target = installed(installDirectory);
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("extension restore target already exists: " + installDirectory);
        }
        move(source, target);
        return target.toRealPath();
    }

    void purgeTrash(String trashName) throws IOException {
        Path target = child(trash, trashName);
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        SafeBundleFileTree.delete(trash, requireDirectChild(trash, target));
    }

    void purgeInstalled(String installDirectory) throws IOException {
        Path target = child(installed, installDirectory);
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        SafeBundleFileTree.delete(installed, requireDirectChild(installed, target));
    }

    private Path secureDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path real = directory.toRealPath();
        if (!real.startsWith(root)) {
            throw new SecurityException("extension directory escapes data-v6");
        }
        try {
            Files.setPosixFilePermissions(
                    real,
                    java.util.Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows 由安装目录 ACL 管理；此处不扩大权限。
        }
        return real;
    }

    private static Path child(Path parent, String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid extension directory name");
        }
        return parent.resolve(name).normalize();
    }

    private static Path requireDirectChild(Path parent, Path child) throws IOException {
        Path normalized =
                Objects.requireNonNull(child, "child").toAbsolutePath().normalize();
        if (!normalized.getParent().equals(parent) || Files.isSymbolicLink(normalized)) {
            throw new SecurityException("staging path is outside the managed directory");
        }
        return normalized.toRealPath();
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }
}
