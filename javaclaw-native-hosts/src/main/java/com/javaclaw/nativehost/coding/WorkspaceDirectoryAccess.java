package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.javaclaw.nativehost.ffm.WindowsWorkspaceDirectory;

/** Workspace 目录能力；Path 只作展示，所有 IO 均相对仍持有的目录句柄完成。 */
final class WorkspaceDirectoryAccess implements AutoCloseable {
    private final Path path;
    private final WorkspacePosixDirectory posix;
    private final WindowsWorkspaceDirectory windows;

    private WorkspaceDirectoryAccess(Path path, WorkspacePosixDirectory posix, WindowsWorkspaceDirectory windows) {
        this.path = path;
        this.posix = posix;
        this.windows = windows;
    }

    static WorkspaceDirectoryAccess open(Path root) throws IOException {
        if (!root.isAbsolute() || !root.normalize().equals(root)) {
            throw new IOException("Workspace root must be a frozen absolute normalized path");
        }
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
            return new WorkspaceDirectoryAccess(root, null, WindowsWorkspaceDirectory.open(root));
        }
        return new WorkspaceDirectoryAccess(root, WorkspacePosixDirectory.open(root), null);
    }

    WorkspaceDirectoryAccess directory(String relative) throws IOException {
        WorkspaceFileProtocol.requireRelative(relative, true);
        Path childPath = path.resolve(relative);
        if (windows != null) {
            return new WorkspaceDirectoryAccess(childPath, null, windows.directory(relative));
        }
        WorkspacePosixDirectory current = posix.duplicate();
        try {
            if (!relative.isEmpty()) {
                for (Path segment : Path.of(relative)) {
                    WorkspacePosixDirectory next = current.child(segment.toString());
                    current.close();
                    current = next;
                }
            }
            return new WorkspaceDirectoryAccess(childPath, current, null);
        } catch (IOException | RuntimeException failure) {
            current.close();
            throw failure;
        }
    }

    BasicFileAttributes attributes(String leaf) throws IOException {
        requireLeaf(leaf);
        BasicFileAttributes attributes = windows != null ? windows.attributes(leaf) : posix.attributes(leaf);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("Workspace entry must not be a link or reparse point: " + leaf);
        }
        return attributes;
    }

    List<String> names(int maximum) throws IOException {
        WorkspaceFileProtocol.limit(maximum, WorkspaceFileAccess.MAX_ENTRIES, "maximum");
        if (windows != null) {
            return windows.names(maximum);
        }
        ArrayList<String> names = new ArrayList<>();
        // SecureDirectoryStream 只允许取得一次 iterator；每次列举都持有独立描述符。
        try (WorkspacePosixDirectory listing = posix.duplicate()) {
            for (Path entry : listing.stream()) {
                if (names.size() >= maximum) {
                    throw new IOException("Workspace directory exceeds entry limit");
                }
                names.add(entry.getFileName().toString());
            }
        }
        return List.copyOf(names);
    }

    SeekableByteChannel openFile(String leaf, Set<? extends OpenOption> options) throws IOException {
        requireLeaf(leaf);
        if (windows != null) {
            return windows.openFile(leaf, options);
        }
        HashSet<OpenOption> checked = new HashSet<>(options);
        checked.add(LinkOption.NOFOLLOW_LINKS);
        return posix.stream().newByteChannel(Path.of(leaf), checked);
    }

    void createDirectory(String leaf) throws IOException {
        requireLeaf(leaf);
        if (windows != null) {
            windows.createDirectory(leaf);
        } else {
            posix.createDirectory(leaf);
        }
    }

    void moveNoReplace(String leaf, WorkspaceDirectoryAccess target, String targetLeaf) throws IOException {
        requireLeaf(leaf);
        requireLeaf(targetLeaf);
        if (windows != null) {
            windows.moveNoReplace(leaf, target.windows, targetLeaf);
        } else {
            posix.moveNoReplace(leaf, target.posix, targetLeaf);
        }
    }

    boolean supportsExchange() {
        return posix != null;
    }

    void exchange(String leaf, WorkspaceDirectoryAccess target, String targetLeaf) throws IOException {
        requireLeaf(leaf);
        requireLeaf(targetLeaf);
        if (posix == null || target.posix == null) {
            throw new IOException("Atomic Workspace exchange is unavailable on this platform");
        }
        posix.exchange(leaf, target.posix, targetLeaf);
    }

    void deleteFile(String leaf) throws IOException {
        requireLeaf(leaf);
        if (windows != null) {
            windows.deleteFile(leaf);
        } else {
            posix.stream().deleteFile(Path.of(leaf));
        }
    }

    void deleteDirectory(String leaf) throws IOException {
        requireLeaf(leaf);
        if (windows != null) {
            windows.deleteDirectory(leaf);
        } else {
            posix.stream().deleteDirectory(Path.of(leaf));
        }
    }

    void copyAccess(String leaf, WorkspaceDirectoryAccess target, String targetLeaf) throws IOException {
        requireLeaf(leaf);
        requireLeaf(targetLeaf);
        if (windows != null) {
            windows.copyAccess(leaf, target.windows, targetLeaf);
            return;
        }
        var source = posix.stream()
                .getFileAttributeView(Path.of(leaf), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        var destination = target.posix.stream()
                .getFileAttributeView(Path.of(targetLeaf), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        destination.setPermissions(source.readAttributes().permissions());
    }

    void force(SeekableByteChannel channel) throws IOException {
        if (windows != null) {
            windows.force(channel);
        } else if (channel instanceof FileChannel file) {
            file.force(true);
        } else {
            throw new IOException("Workspace channel does not provide durable writes");
        }
    }

    Path path() {
        return path;
    }

    private static void requireLeaf(String leaf) {
        WorkspaceFileProtocol.requireRelative(leaf, false);
        if (leaf.contains("/")) {
            throw new IllegalArgumentException("Workspace entry must be one relative name");
        }
    }

    @Override
    public void close() throws IOException {
        if (windows != null) {
            windows.close();
        } else {
            posix.close();
        }
    }
}
